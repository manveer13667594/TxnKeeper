package com.manu.txnkeeper

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

private const val CHANNEL_ID = "voice_setup"
private const val NOTIFICATION_ID = 4101
private const val PREFS = "voice_download"
private const val KEY_ASKED = "asked_notification_permission"

/** At most this often, because Android drops notification updates that come too fast. */
private const val UPDATE_EVERY_MS = 700L

/** A progress notification that has not been updated for this long removes itself (app was closed). */
private const val STALE_AFTER_MS = 60_000L

/**
 * The notification for the voice model download: progress, then "ready" or "didn't finish".
 * It is silent. If notifications are not allowed, nothing is shown and the download carries on.
 */
class VoiceDownloadNotification(private val context: Context) {
    private var lastUpdate = 0L

    fun progress(fraction: Float) {
        val now = SystemClock.elapsedRealtime()
        if (lastUpdate != 0L && now - lastUpdate < UPDATE_EVERY_MS) return
        lastUpdate = now
        val percent = (fraction * 100).toInt()
        show(
            builder("Setting up voice entry", "$percent% of about ${VOICE_MODEL.approxSizeMb} MB")
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setTimeoutAfter(STALE_AFTER_MS)
        )
    }

    fun ready() = show(builder("Voice entry is ready", "You can now add transactions by voice.").setAutoCancel(true))

    fun failed() = show(
        builder("Voice setup didn't finish", "Check your internet, then open TxnKeeper again.").setAutoCancel(true)
    )

    private fun builder(title: String, text: String): NotificationCompat.Builder {
        // Tapping the notification opens the app, the same way the app icon does
        val open: Intent? = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            // Status bar: Android only keeps the shape here and paints it one colour
            .setSmallIcon(R.drawable.ic_stat_txnkeeper)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
        if (open != null) {
            builder.setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE))
        }
        return builder
    }

    @SuppressLint("MissingPermission") // checked with areNotificationsEnabled, and guarded by runCatching
    private fun show(builder: NotificationCompat.Builder) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Voice setup")
                    .setDescription("Progress while voice entry is being set up")
                    .build()
            )
            manager.notify(NOTIFICATION_ID, builder.build())
        }
    }
}

/**
 * Android 13 and newer only show notifications after the user allows them. Asks once, and only
 * while the voice model still has to be downloaded. The download does not wait for the answer.
 */
@Composable
fun AskForDownloadNotification() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val needed = !VoiceEngine.isModelReady(context) &&
                !prefs.getBoolean(KEY_ASKED, false) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needed) {
            prefs.edit().putBoolean(KEY_ASKED, true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}