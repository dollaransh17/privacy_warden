package com.privacywarden.app.defense

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.privacywarden.app.vpn.WardenState

/**
 * Listens to system notifications and scores incoming **email** AND **chat**
 * messages using the on-device PhishingDetector.
 *
 * The user must explicitly grant Notification access:
 *   Settings → Notification access → Privacy Warden
 *
 * We only inspect notifications from a small allowlist of email + messenger
 * apps and never copy them off-device. When a phishing message is detected
 * in a messenger, we also post a heads-up alert via [PhishingAlertNotifier]
 * so the user is warned the moment the scam arrives.
 *
 * Beyond live monitoring, this service also exposes [scanActiveInbox] so the
 * Message Scanner UI can score every notification currently sitting on the
 * status bar the moment the user opens the screen — no need to wait for a
 * fresh message.
 */
class WardenNotificationListener : NotificationListenerService() {

    private val emailApps = setOf(
        "com.google.android.gm",                  // Gmail
        "com.google.android.apps.inbox",          // Inbox
        "com.microsoft.office.outlook",           // Outlook
        "com.yahoo.mobile.client.android.mail",   // Yahoo Mail
        "ch.protonmail.android",                  // ProtonMail
        "com.fastmail.app",                       // FastMail
        "me.bluemail.mail",                       // Blue Mail
        "com.fsck.k9",                            // K-9
    )

    /**
     * Chat / messenger apps. Their notifications surface the actual message
     * body in plaintext on the device (that's how the user sees them too),
     * so we can score them with the same PhishingDetector.
     */
    private val messengerApps = setOf(
        "com.whatsapp",                           // WhatsApp
        "com.whatsapp.w4b",                       // WhatsApp Business
        "org.telegram.messenger",                 // Telegram
        "org.telegram.messenger.web",             // Telegram Web variant
        "org.thunderdog.challegram",              // Telegram X
        "org.thoughtcrime.securesms",             // Signal
        "com.facebook.orca",                      // Messenger
        "com.facebook.mlite",                     // Messenger Lite
        "com.instagram.android",                  // Instagram DMs
        "com.discord",                            // Discord
        "com.Slack",                              // Slack
        "com.microsoft.teams",                    // Teams
        "com.google.android.apps.messaging",      // Google Messages (RCS)
    )

    /**
     * Notification text that we should *never* feed to the phishing detector
     * because it isn't actual message content — it's the messenger app's own
     * meta-notifications. Matched case-insensitively as substrings.
     */
    private val messengerNoise = listOf(
        "is typing",
        "new messages from",
        " new message",                  // "3 new messages"
        "new messages",
        "missed voice call",
        "missed video call",
        "missed call",
        "incoming call",
        "ongoing call",
        "checking for new messages",
        "messages might be delayed",
        "backing up",
        "backup",
        "end-to-end encrypted",
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        WardenState.emailListenerActive.set(true)
        instance = this
        // Auto-scan whatever's already on the status bar the moment the
        // listener connects — that way users see results immediately even if
        // they haven't received a fresh email since granting access.
        runCatching { scanActive() }.onFailure {
            Log.w(TAG, "active scan on connect failed: ${it.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        WardenState.emailListenerActive.set(false)
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        processOne(sbn)
    }

    /**
     * Score every notification currently on the status bar that comes from
     * an email or messenger app. Returns the number of *new* scans recorded
     * (already-known ones are skipped).
     */
    fun scanActive(): Int {
        val active = activeNotifications ?: return 0
        var added = 0
        for (sbn in active) {
            if (processOne(sbn)) added++
        }
        return added
    }

    /**
     * Parse + record a single notification. Returns true if a scan was newly
     * recorded; false for duplicates / off-allowlist apps / empty bodies.
     */
    private fun processOne(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName ?: return false
        return when (pkg) {
            in emailApps     -> processEmail(sbn, pkg)
            in messengerApps -> processMessenger(sbn, pkg)
            else             -> false
        }
    }

    private fun processEmail(sbn: StatusBarNotification, pkg: String): Boolean {
        val extras = sbn.notification?.extras ?: return false
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        val blob = buildString {
            if (title.isNotBlank()) appendLine(title)
            if (subText.isNotBlank()) appendLine(subText)
            if (text.isNotBlank()) appendLine(text)
            if (bigText.isNotBlank()) appendLine(bigText)
        }.trim()
        if (blob.length < 8) return false

        val sender = title.ifBlank { sbn.packageName }
        val subject = bigText.ifBlank { text }.ifBlank { "(no subject)" }
        val key = "$sender|${subject.take(140)}"

        // Dedupe: don't re-record an email we've already seen in this session.
        if (WardenState.emailScans.any { "${it.sender}|${it.subject}" == key }) return false

        val verdict = PhishingDetector.analyze(blob)
        WardenState.recordEmailScan(
            WardenState.EmailScan(
                sender = sender,
                subject = subject.take(140),
                score = verdict.score,
                isPhishing = verdict.isPhishing,
                reasons = verdict.reasons,
                urls = verdict.matchedUrls,
                source = sourceLabel(pkg),
                ts = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
        )
        if (verdict.isPhishing) {
            WardenState.commsBlocked.incrementAndGet()
            WardenState.commsLast.set("Email phishing · score ${verdict.score} · ${sender.take(28)}")
            WardenState.bumpBreakdown(WardenState.Pillar.COMMS, "Email")
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.COMMS,
                    title = "Phishing email detected",
                    detail = "$sender · score ${verdict.score} · ${verdict.reasons.firstOrNull().orEmpty()}",
                )
            )
        }
        return true
    }

    /**
     * Score an incoming chat/messenger notification.
     *
     * For WhatsApp etc. the message body lives in `EXTRA_TEXT` (or
     * `EXTRA_BIG_TEXT` for longer messages). The `EXTRA_TITLE` is the
     * sender / chat name. We:
     *   1. Skip noise (typing indicators, missed-call alerts, bundle
     *      summaries like "3 new messages from 2 chats").
     *   2. Run the body through the on-device PhishingDetector.
     *   3. Record every scan in [WardenState.emailScans] (the field is
     *      generic enough — `source` is set to "WhatsApp" / "Telegram" / …).
     *   4. If flagged as phishing, post a heads-up alert so the user sees
     *      the warning right next to the scam message itself.
     */
    private fun processMessenger(sbn: StatusBarNotification, pkg: String): Boolean {
        val extras = sbn.notification?.extras ?: return false
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        // Pick the longest available body — that's the actual message.
        val body = listOf(bigText, text, subText)
            .maxByOrNull { it.length }.orEmpty().trim()
        if (body.length < 8) return false
        if (isMessengerNoise(body)) return false
        // Bundle summaries are usually "N new messages from M chats". Skip
        // them — we'll score the individual messages once they're posted
        // separately by the messenger.
        if (body.matches(Regex("^\\d+\\s+new messages?.*", RegexOption.IGNORE_CASE))) return false

        val sender = title.ifBlank { sourceLabel(pkg) }
        val subject = body.lineSequence().firstOrNull().orEmpty().take(140)
        val key = "$sender|${subject}"
        if (WardenState.emailScans.any { "${it.sender}|${it.subject}" == key }) return false

        // Concatenate sender + body so the detector also picks up impersonation
        // signals (e.g. sender = "Bank Of India", body = "click https://…").
        val blob = if (sender.isNotBlank()) "$sender\n$body" else body
        val verdict = PhishingDetector.analyze(blob)
        WardenState.recordEmailScan(
            WardenState.EmailScan(
                sender = sender,
                subject = subject.ifBlank { "(message)" },
                score = verdict.score,
                isPhishing = verdict.isPhishing,
                reasons = verdict.reasons,
                urls = verdict.matchedUrls,
                source = sourceLabel(pkg),
                ts = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
        )
        if (verdict.isPhishing) {
            WardenState.commsBlocked.incrementAndGet()
            val src = sourceLabel(pkg)
            WardenState.commsLast.set("$src phishing · score ${verdict.score} · ${sender.take(28)}")
            WardenState.bumpBreakdown(WardenState.Pillar.COMMS, src)
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.COMMS,
                    title = "Phishing $src message detected",
                    detail = "$sender · score ${verdict.score} · ${verdict.reasons.firstOrNull().orEmpty()}",
                )
            )
            // Heads-up alert so the user sees the warning the moment the
            // scam message arrives, right next to the WhatsApp / Telegram
            // notification itself.
            runCatching {
                PhishingAlertNotifier.alert(
                    ctx = applicationContext,
                    source = src,
                    sender = sender,
                    subject = body,
                    score = verdict.score,
                    reasons = verdict.reasons,
                )
            }.onFailure { Log.w(TAG, "alert post failed: ${it.message}") }
        }
        return true
    }

    private fun isMessengerNoise(body: String): Boolean {
        val lower = body.lowercase()
        return messengerNoise.any { lower.contains(it) }
    }

    private fun sourceLabel(pkg: String): String = when (pkg) {
        "com.google.android.gm"                -> "Gmail"
        "com.microsoft.office.outlook"         -> "Outlook"
        "com.yahoo.mobile.client.android.mail" -> "Yahoo"
        "ch.protonmail.android"                -> "ProtonMail"
        "com.fastmail.app"                     -> "FastMail"
        "me.bluemail.mail"                     -> "BlueMail"
        "com.fsck.k9"                          -> "K-9"
        "com.whatsapp"                         -> "WhatsApp"
        "com.whatsapp.w4b"                     -> "WhatsApp Business"
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram"            -> "Telegram"
        "org.thoughtcrime.securesms"           -> "Signal"
        "com.facebook.orca",
        "com.facebook.mlite"                   -> "Messenger"
        "com.instagram.android"                -> "Instagram"
        "com.discord"                          -> "Discord"
        "com.Slack"                            -> "Slack"
        "com.microsoft.teams"                  -> "Teams"
        "com.google.android.apps.messaging"    -> "Messages"
        else                                   -> "Mail"
    }

    companion object {
        private const val TAG = "WardenNotifListener"
        @Volatile private var instance: WardenNotificationListener? = null

        /**
         * Scan every email notification currently on the status bar.
         * Safe to call from any thread; no-op if the listener service isn't
         * connected (i.e. the user hasn't granted notification access).
         * Returns the number of new emails scored.
         */
        fun scanActiveInbox(): Int = runCatching {
            instance?.scanActive() ?: 0
        }.getOrDefault(0)
    }
}
