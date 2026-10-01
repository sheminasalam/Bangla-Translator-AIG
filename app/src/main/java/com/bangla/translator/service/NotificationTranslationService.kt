package com.bangla.translator.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bangla.translator.R
import com.bangla.translator.data.AppPreferences
import com.bangla.translator.translation.BengaliDetector
import com.bangla.translator.translation.TranslationEngine

/**
 * Independent notification listener service that detects incoming Bengali messages
 * in WhatsApp and WhatsApp Business notifications and posts translated companions.
 */
class NotificationTranslationService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationTrans"
        private const val CHANNEL_ID = "bangla_translated_notifications"
        private const val CHANNEL_NAME = "Translated WhatsApp Messages"

        val SUPPORTED_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }

    private lateinit var appPreferences: AppPreferences
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        appPreferences = AppPreferences(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val pkgName = sbn.packageName ?: return
        if (pkgName !in SUPPORTED_PACKAGES) {
            return
        }

        // Verify user preference
        if (!appPreferences.isNotificationTranslationEnabled) {
            return
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString()
            ?: extras.getCharSequence("android.bigText")?.toString()
            ?: ""

        if (text.isBlank()) return

        // Check if message text is Bengali
        if (!BengaliDetector.isBengali(text, appPreferences.bengaliRatioThreshold)) {
            return
        }

        // Translate locally
        TranslationEngine.translate(
            text = text,
            onSuccess = { translatedText ->
                postTranslatedNotification(
                    originalPkg = pkgName,
                    senderTitle = title,
                    originalText = text,
                    translatedText = translatedText,
                    notificationId = sbn.id
                )
            },
            onFailure = { error ->
                Log.w(TAG, "Failed to translate notification: ${error.message}")
            }
        )
    }

    private fun postTranslatedNotification(
        originalPkg: String,
        senderTitle: String,
        originalText: String,
        translatedText: String,
        notificationId: Int
    ) {
        // Create intent to open originating WhatsApp variant
        val launchIntent = packageManager.getLaunchIntentForPackage(originalPkg)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                notificationId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val appLabel = if (originalPkg == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp"
        val displayTitle = if (senderTitle.isNotBlank()) {
            "$senderTitle ($appLabel Translated)"
        } else {
            "$appLabel (Bengali Translated)"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle(displayTitle)
            .setContentText(translatedText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$translatedText\n\nOriginal: $originalText")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        try {
            notificationManager.notify(notificationId + 100000, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing notification permission to post translated notification", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows local Bengali to English translations of incoming WhatsApp messages"
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
