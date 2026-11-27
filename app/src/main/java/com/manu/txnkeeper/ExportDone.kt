package com.manu.txnkeeper

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuOutlineButton
import com.manu.manudesignsystem.components.ManuTextButton
import com.manu.manudesignsystem.theme.ManuDesignSystem

/*
 * What happens after an export: the "Exporting… / Exported" popup with Open, Share and Done, and the
 * "Last export" card that helps to find the file again later.
 */

private const val PREFS = "last_export"
private const val KEY_URI = "uri"
private const val KEY_NAME = "name"
private const val KEY_LOCATION = "location"
private const val KEY_MIME = "mime"

/** A saved export. [location] is the folder in words, for example "Internal storage › Documents". */
data class ExportedFile(val uri: Uri, val name: String, val location: String, val mime: String)

/** Where the export popup is. null = closed. */
sealed interface ExportProgress {
    data object Working : ExportProgress
    data class Done(val file: ExportedFile, val summary: String) : ExportProgress
    data class Failed(val message: String) : ExportProgress
}

/** Looks up the saved file's name and folder, and keeps access to it so it can be opened later. */
fun rememberExport(context: Context, uri: Uri, mime: String, fallbackName: String): ExportedFile {
    // Without this, the app may lose access to the file once it is closed
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val realName = displayName(context, uri)
    val location = realName?.let { folderFromFile(context, uri, it) } ?: folderInWords(context, uri)
    val file = ExportedFile(uri, realName ?: fallbackName, location, mime)
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_URI, uri.toString())
        .putString(KEY_NAME, file.name)
        .putString(KEY_LOCATION, file.location)
        .putString(KEY_MIME, mime)
        .apply()
    return file
}

/** The newest export, or null if nothing was exported yet. */
fun lastExport(context: Context): ExportedFile? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val uri = prefs.getString(KEY_URI, null) ?: return null
    return ExportedFile(
        uri = Uri.parse(uri),
        name = prefs.getString(KEY_NAME, null) ?: return null,
        location = prefs.getString(KEY_LOCATION, null).orEmpty(),
        mime = prefs.getString(KEY_MIME, null) ?: "*/*"
    )
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull()

/**
 * The folder the file is really in: opens it and asks the system where the open file lives. This works
 * for files on the phone or SD card on most phones, whichever way the folder was picked in the "save"
 * screen. Null when the answer is not a normal folder (cloud storage, or a phone that hides it).
 */
private fun folderFromFile(context: Context, uri: Uri, name: String): String? = runCatching {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { open ->
        folderFromPath(Os.readlink("/proc/self/fd/${open.fd}"), name)
    }
}.getOrNull()

/** "/storage/emulated/0/Download/Bills/a.csv" -> "Internal storage › Download › Bills". */
private fun folderFromPath(path: String, name: String): String? {
    // A different file name means this is a temporary copy, not the saved file
    if (path.substringAfterLast('/') != name) return null
    val phone = Regex("^/(?:storage/emulated|mnt/(?:.*/)?emulated|data/media)/\\d+/(.*)$").find(path)
    val card = Regex("^/(?:storage|mnt)/(?:.*/)?[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}/(.*)$").find(path)
    val (root, rest) = when {
        phone != null -> "Internal storage" to phone.groupValues[1]
        card != null -> "SD card" to card.groupValues[1]
        else -> return null
    }
    val folders = rest.split('/').dropLast(1)
    // Android/... holds apps' private copies, not a folder the user picked
    if (folders.firstOrNull() == "Android") return null
    return (listOf(root) + folders).joinToString(" › ")
}

/**
 * Used when the real folder can't be read. The folder in words, from what the storage app tells. Phone storage and SD cards give the real folder path. Other places (Drive and
 * similar apps) don't share a path, so their app name is shown.
 */
private fun folderInWords(context: Context, uri: Uri): String {
    val authority = uri.authority.orEmpty()
    if (authority == "com.android.externalstorage.documents") {
        // The id looks like "primary:Documents/Backups/file.csv"
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        if (id != null && ':' in id) {
            val volume = id.substringBefore(':')
            val folders = id.substringAfter(':').split('/').dropLast(1)
            val root = when (volume) {
                "primary" -> listOf("Internal storage")
                "home" -> listOf("Internal storage", "Documents")
                else -> listOf("SD card")
            }
            return (root + folders).joinToString(" › ")
        }
    }
    // This one does not say which folder inside Downloads
    if (authority == "com.android.providers.downloads.documents") return "Downloads (or a folder inside it)"
    return context.packageManager.resolveContentProvider(authority, 0)
        ?.loadLabel(context.packageManager)?.toString()
        ?: "The place you chose"
}

/** False when the file was moved or deleted, or the app can no longer reach it. */
private fun stillThere(context: Context, file: ExportedFile): Boolean {
    val there = displayName(context, file.uri) != null
    if (!there) toast(context, "File not found. It may have been moved or deleted.")
    return there
}

private fun openExport(context: Context, file: ExportedFile) {
    if (!stillThere(context, file)) return
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(file.uri, file.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(view) }
        .onFailure { toast(context, "No app can open this file. Try Share.") }
}

private fun shareExport(context: Context, file: ExportedFile) {
    if (!stillThere(context, file)) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = file.mime
        putExtra(Intent.EXTRA_STREAM, file.uri)
        clipData = ClipData.newRawUri("", file.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, "Share export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast(context, "Couldn't share this file.") }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

/** Shown when the app comes back from the "save" screen. It stays until Done (or Back). */
@Composable
fun ExportDialog(progress: ExportProgress, onDone: () -> Unit) {
    val context = LocalContext.current
    val working = progress is ExportProgress.Working
    Dialog(
        onDismissRequest = { if (!working) onDone() },
        properties = DialogProperties(dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (progress) {
                    is ExportProgress.Working -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text("Exporting…", style = MaterialTheme.typography.titleMedium)
                    }

                    is ExportProgress.Done -> {
                        Text("Exported", style = MaterialTheme.typography.titleLarge)
                        Text(progress.summary, style = MaterialTheme.typography.bodyMedium)
                        FileDetails(progress.file)
                        Row(horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)) {
                            ManuOutlineButton(
                                modifier = Modifier.weight(1f),
                                onClick = { openExport(context, progress.file) }
                            ) { Text("Open", maxLines = 1) }
                            ManuOutlineButton(
                                modifier = Modifier.weight(1f),
                                onClick = { shareExport(context, progress.file) }
                            ) { Text("Share", maxLines = 1) }
                        }
                        ManuButton(modifier = Modifier.fillMaxWidth(), onClick = onDone) { Text("Done") }
                    }

                    is ExportProgress.Failed -> {
                        Text("Export didn't finish", style = MaterialTheme.typography.titleLarge)
                        Text(progress.message, style = MaterialTheme.typography.bodyMedium)
                        ManuButton(modifier = Modifier.fillMaxWidth(), onClick = onDone) { Text("Done") }
                    }
                }
            }
        }
    }
}

/** On the Export page, so a saved file can be found again later. */
@Composable
fun LastExportCard(file: ExportedFile) {
    val context = LocalContext.current
    ManuCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.md),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Last export",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FileDetails(file)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManuTextButton(onClick = { openExport(context, file) }) { Text("Open") }
                ManuTextButton(onClick = { shareExport(context, file) }) { Text("Share") }
            }
        }
    }
}

@Composable
private fun FileDetails(file: ExportedFile) {
    Column {
        Text(file.name, style = MaterialTheme.typography.titleSmall)
        if (file.location.isNotBlank()) {
            Text(
                text = "Saved in: ${file.location}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}