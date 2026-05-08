package com.privacywarden.app.defense

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.privacywarden.app.EmailScannerActivity
import com.privacywarden.app.R

/**
 * Posts a heads-up notification when the on-device PhishingDetector flags
 * an incoming message — e.g. a WhatsApp / Telegram / Signal scam.
 *
 * The user sees the original notification (from WhatsApp etc.) AND, a
 * fraction of a second later, a Privacy Warden warning sitting right next
 * to it telling them why it looks like phishing.  Tapping the warning
 * deep-links to the Message Scanner so they can read the full breakdown.
 *
 * No personal data ever leaves the device: the sender + a 100-char snippet
 * are shown locally on the notification only.
 */
object PhishingAlertNotifier {

    private const val CHANNEL_ID = "pw_phishing_alerts"
    private const val CHANNEL_NAME = "Phishing alerts"
    private const val CHANNEL_DESC =
        "Heads-up warnings when an incoming SMS, email or chat is flagged as phishing"

    /**
     * Post a heads-up alert for a single phishing finding.
     *
     * @param ctx      any Context (we use applicationContext)
     * @param source   "WhatsApp" / "Telegram" / "Gmail" / "SMS" …
     * @param sender   contact / chat name shown on the notification
     * @param subject  best-effort subject or the message body
     * @param score    0–100 phishing confidence
     * @param reasons  human-readable rule hits (first one is shown)
     */
    fun alert(
        ctx: Context,
        source: String,
        sender: String,
        subject: String,
        score: Int,
        reasons: List<String>,
    ) {
        val app = ctx.applicationContext
        ensureChannel(app)

        val tap = Intent(app, EmailScannerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            app, 0, tap,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = "Possible $source phishing  ·  $score/100"
        val body = buildString {
            append("From: ").append(sender.take(40)).append('\n')
            val firstReason = reasons.firstOrNull()
            if (!firstReason.isNullOrBlank()) {
                append(firstReason).append('\n')
            }
            append('"').append(subject.take(100).replace('\n', ' ')).append('"')
        }

        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setColor(0xFFDC2626.toInt())
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: body.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .build()

        // Stable per-(sender,subject) id so repeats don't pile up.
        val id = (("$source|$sender|$subject").hashCode() and 0x7FFFFFFF) or 0x10000
        runCatching {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(id, n)
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = CHANNEL_DESC
            enableVibration(true)
            setShowBadge(true)
        }
        nm.createNotificationChannel(ch)
    }
}
