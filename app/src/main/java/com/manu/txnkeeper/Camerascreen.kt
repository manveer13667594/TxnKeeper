package com.manu.txnkeeper

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.manu.manudesignsystem.components.ManuButton
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** The flash choices: off, automatic (fires when it is dark) or torch (a steady light while the camera is open). */
private enum class FlashSetting(val label: String) {
    Off("Off"),
    Auto("Auto"),
    Torch("Torch");

    fun next(): FlashSetting = when (this) {
        Off -> Auto
        Auto -> Torch
        Torch -> Off
    }
}

/** Where the screen was tapped to focus (id makes two taps in the same spot count as two). */
private class FocusTap(val offset: Offset, val id: Long)

/**
 * Clears photos left in the camera's temporary folder. Only files older than ten minutes go, so a
 * photo that is still being read is never touched.
 */
private fun clearOldCameraFiles(dir: File) {
    val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
    dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
}

/**
 * Full-screen camera for scanning receipts.
 *
 *  - Top: back arrow, and the flash button (Off / Auto / Torch) when the lens has a flash.
 *  - Bottom: lens flip (bottom-left, only when the phone has both lenses), shutter (bottom-center),
 *    "Gallery" (bottom-right, opens the system photo picker, no storage permission needed).
 *  - On the preview: tap to focus, pinch to zoom (tap the zoom label to go back to 1x).
 *
 * Photos are saved to the app's cache folder (not the gallery) and reported via onPhotoSaved.
 * Swipe right-to-left, the back arrow or the system back closes it.
 *
 * [isOpen] is false while the screen is sliding out, and [enterFinished] turns true once it has
 * finished sliding in. The camera is only started after that, so opening it never makes the
 * slide-in stutter.
 */
@Composable
fun CameraScreen(
    onClose: () -> Unit,
    onPhotoSaved: (Uri) -> Unit,
    // A photo was taken or picked: the caller closes the camera and reads its text
    onImageReady: (Uri) -> Unit,
    isOpen: Boolean = true,
    enterFinished: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current

    // Only handle "back" while the camera is really open (not while it is sliding away)
    BackHandler(enabled = isOpen, onBack = onClose)

    // The camera is black: make the clock / battery icons white while it is open
    DisposableEffect(isOpen) {
        val window = context.findActivity()?.window
        if (!isOpen || window == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val lightStatusIcons = controller.isAppearanceLightStatusBars
        val lightNavIcons = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = lightStatusIcons
            controller.isAppearanceLightNavigationBars = lightNavIcons
        }
    }

    fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED

    var hasCamera by remember { mutableStateOf(isGranted(Manifest.permission.CAMERA)) }
    // The permission was refused with "don't ask again": only the phone's Settings can turn it on now
    var permissionBlocked by remember { mutableStateOf(false) }
    var askedForPermission by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }

    // Camera state
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraAllowed by remember { mutableStateOf(false) }   // set once the slide-in is done
    var streaming by remember { mutableStateOf(false) }       // true once the first frame shows
    var canFlip by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var flashSetting by remember { mutableStateOf(FlashSetting.Off) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var zoomRange by remember { mutableStateOf(1f..1f) }
    var capturing by remember { mutableStateOf(false) }
    var shutterBlink by remember { mutableStateOf(false) }
    var focusTap by remember { mutableStateOf<FocusTap?>(null) }

    LaunchedEffect(enterFinished) { if (enterFinished) cameraAllowed = true }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCamera = result[Manifest.permission.CAMERA] ?: hasCamera
        if (!hasCamera) {
            val activity = context.findActivity()
            permissionBlocked = activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.CAMERA
                    )
        }
    }

    // Ask once, after the screen has finished sliding in (the camera is the only permission the app
    // needs here; picking from the gallery uses the system photo picker, which needs none)
    LaunchedEffect(enterFinished) {
        if (enterFinished && !hasCamera && !askedForPermission) {
            askedForPermission = true
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    // Back from the phone's Settings: pick up a permission that was just turned on.
    // The camera stops itself when the app is stopped and takes its light with it, so show the torch as off.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasCamera = isGranted(Manifest.permission.CAMERA)
                    if (hasCamera) permissionBlocked = false
                }

                Lifecycle.Event.ON_STOP -> {
                    if (flashSetting == FlashSetting.Torch) flashSetting = FlashSetting.Off
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // CameraX
    val previewView = remember {
        PreviewView(context).apply {
            // Compatible mode (a TextureView): unlike the default SurfaceView, it slides and fades
            // together with the screen instead of staying behind while the screen animates
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    // Receipts are all small text: favour a sharp photo over the fastest shutter
    val imageCapture = remember { sharpImageCapture() }

    // The preview fades in when the first frame arrives, so there is no black-to-picture jump
    DisposableEffect(previewView, lifecycleOwner) {
        val observer = Observer<PreviewView.StreamState> {
            streaming = it == PreviewView.StreamState.STREAMING
        }
        previewView.previewStreamState.observe(lifecycleOwner, observer)
        onDispose { previewView.previewStreamState.removeObserver(observer) }
    }
    val previewAlpha by animateFloatAsState(
        targetValue = if (streaming) 1f else 0f,
        animationSpec = tween(250),
        label = "previewAlpha"
    )

    DisposableEffect(hasCamera, cameraAllowed, lensFacing) {
        if (!hasCamera || !cameraAllowed) return@DisposableEffect onDispose { }
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            // The screen may have been closed before the camera finished starting: then do nothing
            if (disposed) return@addListener
            try {
                val p = future.get()
                provider = p
                canFlip = try {
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) &&
                            p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                } catch (e: Exception) {
                    false
                }
                val newPreview = autofocusPreview().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                preview = newPreview
                p.unbind(imageCapture)
                val bound = p.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    newPreview,
                    imageCapture
                )
                val zoom = bound.cameraInfo.zoomState.value
                zoomRange = (zoom?.minZoomRatio ?: 1f)..(zoom?.maxZoomRatio ?: 1f)
                zoomRatio = zoom?.zoomRatio ?: 1f
                camera = bound
            } catch (e: Exception) {
                Toast.makeText(context, "Camera not available", Toast.LENGTH_SHORT).show()
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            camera = null
            streaming = false
            provider?.unbind(*listOfNotNull<UseCase>(preview, imageCapture).toTypedArray())
        }
    }

    // As soon as the camera is running, focus once on the centre (then it carries on focusing by itself)
    LaunchedEffect(camera) {
        val cam = camera ?: return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        if (previewView.width > 0 && previewView.height > 0) {
            try {
                val centre = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
                cam.cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(centre, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                )
            } catch (e: Exception) {
                // Not important: the camera still focuses by itself
            }
        }
    }

    // Apply the flash choice to the running camera (also again after the lens is flipped)
    LaunchedEffect(camera, flashSetting) {
        val cam = camera
        if (cam == null) {
            hasFlash = false
            return@LaunchedEffect
        }
        val flashAvailable = cam.cameraInfo.hasFlashUnit()
        hasFlash = flashAvailable
        if (!flashAvailable) {
            // For example the front lens: nothing to light with
            if (flashSetting != FlashSetting.Off) flashSetting = FlashSetting.Off
            return@LaunchedEffect
        }
        cam.cameraControl.enableTorch(flashSetting == FlashSetting.Torch)
        imageCapture.flashMode =
            if (flashSetting == FlashSetting.Auto) ImageCapture.FLASH_MODE_AUTO
            else ImageCapture.FLASH_MODE_OFF
    }

    // Pick any photo from the gallery (system photo picker, no permission needed)
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onImageReady(uri) }

    fun takePhoto() {
        if (capturing || camera == null) return   // one photo at a time
        capturing = true
        shutterBlink = true
        // No vibration here: it would shake the phone while the picture is being taken.
        // The buzz comes once the photo is saved (see onImageSaved below).

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(System.currentTimeMillis())
        // The photo goes into the app's own cache folder, not the gallery. The app keeps its own shrunk
        // copy with the transaction (ReceiptStore); this full-size temporary file is cleared away later.
        // A photo can be saved to the gallery afterwards from the image viewer.
        val dir = File(context.cacheDir, "camera").also { it.mkdirs() }
        clearOldCameraFiles(dir)
        val photoFile = File(dir, "TXN_$stamp.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        fun shoot() { try {
            imageCapture.takePicture(
                options,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val uri = output.savedUri ?: Uri.fromFile(photoFile)
                        if (photoFile.isFile) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPhotoSaved(uri)
                            onImageReady(uri)
                        } else {
                            capturing = false
                            Toast.makeText(context, "Couldn't save photo", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        capturing = false
                        Toast.makeText(context, "Couldn't save photo", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } catch (e: Exception) {
            capturing = false
            Toast.makeText(context, "Couldn't take photo", Toast.LENGTH_SHORT).show()
        } }

        // Let the camera finish focusing before the shutter fires, so text is sharp. If you tapped to
        // focus a moment ago that focus is kept; otherwise it focuses on the centre first (1.5 s at most).
        val cam = camera
        val tappedRecently = focusTap != null
        if (cam == null || tappedRecently) {
            shoot()
        } else {
            var fired = false
            fun fireOnce() { if (!fired) { fired = true; shoot() } }
            try {
                val centre = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
                val focus = FocusMeteringAction.Builder(
                    centre,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                ).build()
                // Focus on the centre; if the camera says it could not lock focus, try once more, then shoot
                fun attempt(triesLeft: Int) {
                    val result = cam.cameraControl.startFocusAndMetering(focus)
                    result.addListener({
                        val locked = try { result.get().isFocusSuccessful } catch (e: Exception) { false }
                        if (locked || triesLeft <= 0) fireOnce() else attempt(triesLeft - 1)
                    }, mainExecutor)
                }
                attempt(1)
                // Never wait longer than this for the shot
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ fireOnce() }, 2500)
            } catch (e: Exception) {
                fireOnce()
            }
        }
    }

    fun focusAt(offset: Offset) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
        focusTap = FocusTap(offset, System.nanoTime())
    }

    fun applyZoom(factor: Float) {
        val cam = camera ?: return
        val target = (zoomRatio * factor).coerceIn(zoomRange)
        cam.cameraControl.setZoomRatio(target)
        zoomRatio = target
    }

    fun resetZoom() {
        val cam = camera ?: return
        val target = 1f.coerceIn(zoomRange)
        cam.cameraControl.setZoomRatio(target)
        zoomRatio = target
    }

    fun openAppSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    }

    // A quick dark blink when the photo is taken
    val blinkAlpha by animateFloatAsState(
        targetValue = if (shutterBlink) 0.55f else 0f,
        animationSpec = tween(80),
        label = "shutterBlink",
        finishedListener = { shutterBlink = false }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swipe right to left closes
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragCancel = { total = 0f },
                    onDragEnd = { if (total < -100.dp.toPx()) onClose() },
                    onHorizontalDrag = { _, amount -> total += amount }
                )
            }
            // Tap to focus
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusAt(it) })
            }
            // Pinch to zoom. Last in the chain so it sees touches first: while two fingers are
            // down it takes them, which keeps a pinch from also counting as a swipe.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        if (zoomChange != 1f) {
                            applyZoom(zoomChange)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        if (hasCamera) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = previewAlpha }
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (permissionBlocked)
                        "Camera access is turned off for TxnKeeper. Turn it on in Settings to scan receipts."
                    else "Camera permission is needed",
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                ManuButton(
                    onClick = {
                        if (permissionBlocked) openAppSettings()
                        else permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                    },
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(if (permissionBlocked) "Open settings" else "Allow camera")
                }
            }
        }

        // Focus ring where the screen was tapped
        focusTap?.let { tap ->
            key(tap.id) { FocusRing(center = tap.offset, onDone = { focusTap = null }) }
        }

        // Shutter blink
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = blinkAlpha))
        )

        // Top bar: back on the left, flash on the right
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Close camera",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            AnimatedVisibility(visible = hasFlash, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .semantics(mergeDescendants = true) {
                            contentDescription =
                                "Flash ${flashSetting.label}. Tap to change."
                        }
                        .clickable { flashSetting = flashSetting.next() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (flashSetting) {
                            FlashSetting.Off -> Icons.Default.FlashOff
                            FlashSetting.Auto -> Icons.Default.FlashAuto
                            FlashSetting.Torch -> Icons.Default.FlashOn
                        },
                        contentDescription = null,
                        tint = if (flashSetting == FlashSetting.Torch) Color(0xFFFFD54F) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = flashSetting.label,
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }

        // Zoom level (tap it to go back to 1x)
        if (abs(zoomRatio - 1f) > 0.05f) {
            Text(
                text = String.format(Locale.US, "%.1fx", zoomRatio),
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 140.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { resetZoom() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // Bottom controls: flip | shutter | gallery
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Lens flip (only when the phone has a front and a back lens)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (hasCamera && canFlip) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .clickable {
                                lensFacing =
                                    if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                        CameraSelector.LENS_FACING_FRONT
                                    else CameraSelector.LENS_FACING_BACK
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch camera",
                            tint = Color.White
                        )
                    }
                }
            }

            // Shutter: shrinks a little while pressed, dims while the photo is being saved
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val shutterSource = remember { MutableInteractionSource() }
                val shutterPressed by shutterSource.collectIsPressedAsState()
                val shutterScale by animateFloatAsState(
                    targetValue = if (shutterPressed) 0.9f else 1f,
                    label = "shutterScale"
                )
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = shutterScale
                            scaleY = shutterScale
                        }
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(7.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (capturing) 0.5f else 1f))
                        .semantics { contentDescription = "Take photo" }
                        .clickable(
                            interactionSource = shutterSource,
                            indication = null,
                            enabled = hasCamera && camera != null && !capturing
                        ) { takePhoto() }
                )
            }

            // Gallery: opens the system photo picker so the user can choose a receipt / screenshot
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            pickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(2.dp, Color.White, RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null, // the "Gallery" label below describes it
                            tint = Color.White
                        )
                    }
                    Text(
                        "Gallery",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/** The ring that pops in where the screen was tapped to focus, then fades away. */
@Composable
private fun FocusRing(center: Offset, onDone: () -> Unit) {
    val ringSize = 68.dp
    val ringPx = with(LocalDensity.current) { ringSize.toPx() }
    val scaleAnim = remember { Animatable(1.35f) }
    val alphaAnim = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        scaleAnim.animateTo(1f, tween(160))
        delay(700)
        alphaAnim.animateTo(0f, tween(200))
        onDone()
    }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (center.x - ringPx / 2).roundToInt(),
                    (center.y - ringPx / 2).roundToInt()
                )
            }
            .size(ringSize)
            .graphicsLayer {
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
                alpha = alphaAnim.value
            }
            .border(2.dp, Color.White, CircleShape)
    )
}

/** The Activity behind this context (Compose can hand us a wrapped one). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The photo camera set up for small text: the largest picture up to about 12 megapixels (4:3), top JPEG
 * quality, the phone's high-quality sharpening and noise reduction, and image stabilisation when it has it.
 * Settings a phone does not support are ignored by it.
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun sharpImageCapture(): ImageCapture {
    val builder = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setJpegQuality(100)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
        )
    Camera2Interop.Extender(builder)
        .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        .setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
        )
    return builder.build()
}

/** The live preview, asking the camera to keep focusing continuously for still pictures. */
@OptIn(ExperimentalCamera2Interop::class)
private fun autofocusPreview(): Preview {
    val builder = Preview.Builder()
    Camera2Interop.Extender(builder)
        .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    return builder.build()
}