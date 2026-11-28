package com.manu.txnkeeper

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * A small picture of a transaction's receipt image. The image is decoded off the main thread at
 * a small size and cached, so a long list stays smooth. Shows a plain grey square while it loads
 * (or if the file is missing).
 */
@Composable
fun ReceiptThumb(
    fileName: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    maxSidePx: Int = 160,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = fileName, key2 = maxSidePx) {
        value = ReceiptStore.loadForScreen(context, fileName, maxSidePx)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = "Receipt image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** The receipt image as wide as its container (used in the transaction details). Tap to open it full screen. */
@Composable
fun ReceiptPreview(fileName: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = fileName) {
        value = ReceiptStore.loadForScreen(context, fileName, 900)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = "Receipt image, tap to open",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * The image full screen on black. Pinch to zoom, drag to move, X or Back to close.
 * "Save to gallery" (top left) puts a copy in the phone's gallery: images are kept inside the app only,
 * until the user chooses to save one.
 */
@Composable
fun ReceiptImageViewer(fileName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = fileName) {
        value = ReceiptStore.loadForScreen(context, fileName, ReceiptStore.MAX_SIDE_PX)
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Receipt image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale <= 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }
            TextButton(
                onClick = {
                    if (!saving && !saved) {
                        saving = true
                        scope.launch {
                            val ok = ReceiptStore.saveToGallery(context, fileName)
                            saving = false
                            if (ok) {
                                saved = true
                                Toast.makeText(context, "Saved to your gallery (Pictures/TxnKeeper)", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Couldn't save the image", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                enabled = !saving && !saved && image != null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.6f)),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.7f)
                )
            ) {
                Text(
                    text = when {
                        saving -> "Saving…"
                        saved -> "Saved to gallery"
                        else -> "Save to gallery"
                    },
                    color = Color.White
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

/** Opens the photo picker. [busy] is true while the picked image is being saved. */
private class ImagePicker(val busy: Boolean, val open: () -> Unit)

/**
 * The system photo picker (no permission needed). The picked photo is shrunk and copied into the app
 * ([ReceiptStore]); [onPicked] gets the new file's name.
 */
@Composable
private fun rememberImagePicker(onPicked: (String) -> Unit): ImagePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val name = ReceiptStore.saveFromUri(context, uri)
                busy = false
                if (name == null) {
                    Toast.makeText(context, "Couldn't read that image.", Toast.LENGTH_SHORT).show()
                } else {
                    onPicked(name)
                }
            }
        }
    }
    return ImagePicker(busy) {
        if (!busy) launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/** "Attach image" as a small pill that looks like the pickers (Compactform.kt). Show it while there is no image. */
@Composable
fun AttachImagePill(onPicked: (String) -> Unit, modifier: Modifier = Modifier) {
    val picker = rememberImagePicker(onPicked)
    PickChip(
        icon = Icons.Default.AddPhotoAlternate,
        text = if (picker.busy) "Saving image…" else "Attach image",
        muted = picker.busy,
        modifier = modifier,
        onClick = picker.open
    )
}

/**
 * The image part of the Add and Edit forms.
 *
 * - No image yet: shows nothing (the forms show [AttachImagePill] beside "Add items").
 * - An image: a small picture (tap it to see it full screen), the "Include image" box, and "Change".
 *
 * The picked photo is shrunk and copied into the app ([ReceiptStore]); [onPicked] gets the new file's
 * name. What happens to the image the form had before is up to the caller.
 */
@Composable
fun ReceiptImageField(
    imageFile: String?,
    include: Boolean,
    onIncludeChange: (Boolean) -> Unit,
    onPicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    includeLabel: String = "Include image",
    /** When set, a second small box "Fill from image" is shown (ticked = the image's text fills the form). */
    fill: Boolean? = null,
    onFillChange: (Boolean) -> Unit = {},
    /** Shown in red under the box while it is unticked, so losing the image is never a surprise. */
    excludedWarning: String? = null
) {
    var viewing by remember { mutableStateOf(false) }
    val picker = rememberImagePicker(onPicked)

    // No image: nothing here. AttachImagePill is the way to add one.
    if (imageFile != null) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReceiptThumb(fileName = imageFile, size = 64.dp, maxSidePx = 240, onClick = { viewing = true })
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.clickable { onIncludeChange(!include) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = include, onCheckedChange = onIncludeChange)
                    Text(includeLabel, style = MaterialTheme.typography.bodyLarge)
                }
                if (fill != null) {
                    Row(
                        modifier = Modifier.clickable { onFillChange(!fill) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = fill, onCheckedChange = onFillChange)
                        Text("Fill from image", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (!include && excludedWarning != null) {
                    Text(
                        text = excludedWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                TextButton(onClick = picker.open, enabled = !picker.busy) {
                    Text(if (picker.busy) "Saving image…" else "Change image")
                }
            }
        }
    }

    if (viewing && imageFile != null) {
        ReceiptImageViewer(fileName = imageFile, onDismiss = { viewing = false })
    }
}