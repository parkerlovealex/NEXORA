package com.nexora.browser.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nexora.browser.NexoraApp
import com.nexora.browser.R
import com.nexora.browser.ui.BrowserActivity

/** Download notifications are local to the device and never upload file metadata. */
object BrowserNotifications {
    const val CHANNEL_ID = "nexora_downloads"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Browser downloads",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Download completion and failure alerts from the browser"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun notifyDownload(
        context: Context,
        profileId: String,
        downloadId: Long,
        fileName: String,
        success: Boolean,
        detail: String? = null
    ) {
        val prefs = context.getSharedPreferences(NexoraApp.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(NexoraApp.KEY_DOWNLOAD_NOTIFICATIONS, true)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        createChannel(context)
        val intent = Intent(context, BrowserActivity::class.java).apply {
            putExtra(BrowserActivity.EXTRA_PROFILE_ID, profileId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            profileId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (success) "Download complete" else "Download failed"
        val body = detail?.takeIf { it.isNotBlank() } ?: fileName
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        val notificationId = (downloadId xor (downloadId ushr 32)).toInt()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }
}
