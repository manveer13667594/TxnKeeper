package com.manu.txnkeeper

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuDialog
import com.manu.manudesignsystem.components.ManuOutlineButton
import com.manu.manudesignsystem.components.ManuScaffold
import com.manu.manudesignsystem.components.ManuTopBar
import com.manu.manudesignsystem.theme.ManuDesignSystem
import java.util.Calendar

private const val PRIVACY_POLICY_URL = "https://txnkeeper.github.io/txnkeeper-privacy/"

/**
 * The address that "Send feedback" writes to. Put yours here.
 * While it is empty, the Send feedback button is hidden.
 */
private const val FEEDBACK_EMAIL = ""

/** One line of the highlights card: an icon, a short title and one line of detail. */
private class Highlight(val icon: ImageVector, val title: String, val detail: String)

private val HIGHLIGHTS = listOf(
    Highlight(Icons.Default.CloudOff, "Works offline", "No account and no internet needed."),
    Highlight(AppIcons.CameraAlt, "Scan receipts", "Fill a transaction from a photo or screenshot."),
    Highlight(Icons.Default.Mic, "Voice entry", "Say a transaction instead of typing it."),
    Highlight(Icons.Default.Lock, "Private", "Everything stays on your phone.")
)

/** The libraries the app is built with, and their licenses. Shown by "Open-source licenses". */
private val LICENSES = listOf(
    "Jetpack Compose, Room, CameraX, Material Icons (AndroidX)" to "Apache License 2.0",
    "Google ML Kit Text Recognition" to "ML Kit Terms of Service",
    "whisper.cpp" to "MIT License",
    "OpenAI Whisper speech model" to "MIT License"
)

/**
 * Full-screen About page (opened from the three-dot menu on the home screen, or from Settings):
 * the app icon, name and version, what the app does, privacy, feedback and licenses.
 */
@Composable
fun AboutScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var licensesOpen by remember { mutableStateOf(false) }

    // Read from the app itself, so it is always the real version and never needs editing here
    val versionName = remember {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    }
    // The launcher icon, as set in the manifest (an adaptive icon can't be loaded with painterResource)
    val iconBitmap = remember {
        try {
            context.packageManager.getApplicationIcon(context.packageName)
                .toBitmap(width = 192, height = 192)
                .asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    ManuScaffold(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp),
        topBar = {
            ManuTopBar(
                title = "About",
                modifier = Modifier.padding(horizontal = 20.dp),
                navigationIcon = Icons.Default.ArrowBack,
                onNavigationClick = onClose
            )
        }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ---- Icon, name, version and a one-line description
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                iconBitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(20.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = "TxnKeeper",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                if (versionName != null) {
                    Text(
                        text = "Version $versionName",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Keep track of your income and expenses, privately, on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // ---- Highlights
            AboutSection(title = "Highlights") {
                HIGHLIGHTS.forEach { HighlightRow(it) }
            }

            // ---- Privacy
            AboutSection(title = "Privacy") {
                Text(
                    text = "Your data stays on your phone. The privacy policy explains the details.",
                    style = MaterialTheme.typography.bodyMedium
                )
                ManuOutlineButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { openLink(context, PRIVACY_POLICY_URL) }
                ) { Text("Privacy policy") }
            }

            // ---- Feedback and licenses
            AboutSection(title = "Support") {
                if (FEEDBACK_EMAIL.isNotBlank()) {
                    Text(
                        text = "Found a problem or have an idea? Tell us. Your email app opens with the app version filled in.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    ManuOutlineButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { sendFeedback(context, versionName) }
                    ) { Text("Send feedback") }
                }
                ManuOutlineButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { licensesOpen = true }
                ) { Text("Open-source licenses") }
            }

            Text(
                text = "Made by Manu · © ${Calendar.getInstance().get(Calendar.YEAR)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    if (licensesOpen) {
        ManuDialog(
            visible = true,
            title = "Open-source licenses",
            confirmText = "Close",
            onConfirm = { licensesOpen = false },
            onDismiss = { licensesOpen = false }
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "TxnKeeper is built with these open-source libraries.",
                    style = MaterialTheme.typography.bodyMedium
                )
                LICENSES.forEach { (name, license) ->
                    Column {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            license,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = "The full license texts are on each project's website.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** An icon with a short title and one line of detail under it. */
@Composable
private fun HighlightRow(highlight: Highlight) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = highlight.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column {
            Text(highlight.title, style = MaterialTheme.typography.titleSmall)
            Text(
                highlight.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A small heading with a card under it. */
@Composable
private fun AboutSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ManuCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content
            )
        }
    }
}

/** Opens the email app with a new message to [FEEDBACK_EMAIL]. Nothing from the app's data is added. */
private fun sendFeedback(context: Context, versionName: String?) {
    val version = versionName ?: "?"
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "TxnKeeper feedback (v$version)")
        putExtra(
            Intent.EXTRA_TEXT,
            "\n\n--\nApp version: $version\nAndroid: ${Build.VERSION.RELEASE}\nDevice: ${Build.MODEL}"
        )
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No email app found.", Toast.LENGTH_SHORT).show()
    }
}

/** Opens a web page in the browser; says so if the phone has no app that can open it. */
private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open this link.", Toast.LENGTH_SHORT).show()
    }
}