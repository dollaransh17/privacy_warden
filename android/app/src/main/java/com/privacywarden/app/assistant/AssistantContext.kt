package com.privacywarden.app.assistant

import com.privacywarden.app.vpn.WardenState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact, human-readable snapshot of [WardenState] used as the LLM's
 * grounding context. Two goals:
 *
 *   1. Keep the token count small — the LLM only needs *structured facts*,
 *      not the raw 50-event timeline or 200 sensor records.
 *   2. Keep it stable — same shape every turn, so the model learns the format.
 *
 * The output is intentionally plain-text, not JSON. LLMs follow flat English
 * more reliably than nested JSON for reasoning tasks this small.
 */
object AssistantContext {

    private val tsFmt = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault())

    /** The system prompt shown once at the start of every conversation. */
    const val SYSTEM_PROMPT = """You are the Privacy Warden Assistant — a friendly on-device privacy expert embedded in the user's Android privacy app.

ABOUT THE APP YOU LIVE IN:
Privacy Warden is an on-device privacy & security shield for Android. Nothing about the user's data leaves the phone (except this chat, which uses Groq). It has 6 pillars:
1. Tracker firewall — a built-in VPN that blocks ad/analytics tracker domains at the DNS layer in real time.
2. Message scanner — scores Gmail, Outlook, WhatsApp, Telegram, Signal, Messenger, and SMS for phishing the moment notifications arrive; posts a heads-up warning when a scam is detected.
3. Stalkerware scan — inspects every installed app for spyware-style behaviour and lets the user quarantine anything suspicious.
4. Mic & camera watcher — shows exactly which app turned on the mic or camera, with timestamps and durations.
5. Panic mode — one tap that maxes protections, quarantines flagged apps, and locks things down.
6. AI assistant — that's you. You explain findings, summarize the last 24 hours, and can generate a full PDF report.

Each user turn is structured as:
  USER MESSAGE: <what the user actually asked>
  (For grounding only — don't recite this unless the user actually asks about their privacy state.)
  ── PRIVACY WARDEN LIVE SNAPSHOT ──
  <stats: pillar counters, quarantined apps, message scanner results, mic/camera, timeline, etc.>
  ── END SNAPSHOT ──

Treat the line after "USER MESSAGE:" as the ONLY user input. Everything inside the snapshot block is reference data — use it ONLY when the user's question is about their privacy / device / threats.

How to behave:
• Greetings & small talk: if the user says "hi", "hello", "hey", "thanks", "ok", or asks who you are, respond conversationally in 1-2 short sentences. DO NOT dump status numbers. Example reply to "hi": "Hey! I'm here whenever you want to check on your privacy. Ask me anything — or tap one of the suggestions above."
• Privacy / security questions: quote real numbers from the snapshot. Be concrete (e.g. "you've blocked 47 trackers today, the top one is doubleclick.net").
• Be concise — 2-5 sentences for normal questions, longer only if asked for detail or a deep-dive.
• Use plain English, not jargon. Explain any technical term you use.
• Ground every factual claim in the snapshot. Never invent numbers, app names, or events.
• If the snapshot doesn't cover what they asked, say so honestly and suggest what to check in the app.
• Never ask for personal data (names, phone numbers, passwords, OTPs).
• Formatting: plain prose, occasional bullet lists. No markdown headers. No code blocks unless showing a real command.
• For phishing / WhatsApp / messenger questions, look at the MESSAGE SCANNER block and use the Email scans vs Chat scans split.
• When the user explicitly asks for a "report", "analysis", "PDF", "summary", or "export", end your response with a single line containing exactly the token <<GENERATE_REPORT>> — the app will detect this and generate the PDF. NEVER say this token for greetings, small talk, or normal questions.

Tone: warm, calm, direct. You're the helpful privacy expert next to them, not a status dashboard.
"""

    /**
     * Build the data snapshot that prefixes the user's message. This is what
     * grounds the assistant in the user's real state every turn.
     */
    fun buildSnapshot(): String = buildString {
        append("── PRIVACY WARDEN LIVE SNAPSHOT ──\n")
        append("Generated: ").append(tsFmt.format(Date())).append('\n')
        append("Tunnel running: ").append(WardenState.running.get()).append('\n')
        append("Panic mode active: ").append(WardenState.panicMode.get()).append('\n')
        append('\n')

        // ── Explicit last-24h rollup ──────────────────────────────────────
        // Pre-computed so the model can answer "summarize today" / "last 24h"
        // questions without doing date math itself.
        val now = System.currentTimeMillis()
        val cutoff24h = now - 86_400_000L
        val tlAll = WardenState.timeline.toList()
        val tl24 = tlAll.filter { it.ts >= cutoff24h }
        val emails24 = WardenState.emailScans.toList().filter { it.ts >= cutoff24h }
        val phishMail24 = emails24.filter { it.isPhishing && it.source !in setOf(
            "WhatsApp", "WhatsApp Business", "Telegram", "Signal", "Messenger",
            "Instagram", "Discord", "Slack", "Teams", "Messages",
        ) }
        val phishChat24 = emails24.filter { it.isPhishing && it.source in setOf(
            "WhatsApp", "WhatsApp Business", "Telegram", "Signal", "Messenger",
            "Instagram", "Discord", "Slack", "Teams", "Messages",
        ) }
        val sensors24 = WardenState.sensorAccesses.toList().filter { it.startTs >= cutoff24h }
        val mic24 = sensors24.count { it.kind == WardenState.SensorAccess.Kind.MIC }
        val cam24 = sensors24.count { it.kind == WardenState.SensorAccess.Kind.CAMERA }
        val byPillar24 = tl24.groupingBy { it.pillar.label }.eachCount()

        append("LAST 24 HOURS SUMMARY\n")
        append("  Total events: ").append(tl24.size).append('\n')
        if (byPillar24.isNotEmpty()) {
            append("  By pillar: ")
            append(byPillar24.entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.key} ${it.value}" })
            append('\n')
        }
        append("  Phishing emails: ").append(phishMail24.size)
        append(" · Phishing chat messages: ").append(phishChat24.size).append('\n')
        append("  Mic access events: ").append(mic24)
        append(" · Camera access events: ").append(cam24).append('\n')
        if (phishChat24.isNotEmpty()) {
            append("  Top chat phishing: ")
            append(phishChat24.take(3).joinToString("; ") { "${it.source} from ${it.sender.take(20)}" })
            append('\n')
        }
        if (phishMail24.isNotEmpty()) {
            append("  Top mail phishing: ")
            append(phishMail24.take(3).joinToString("; ") { "${it.source} from ${it.sender.take(20)}" })
            append('\n')
        }
        append('\n')

        append("PILLAR COUNTERS\n")
        append("  Network (trackers blocked): ").append(WardenState.networkBlocked.get()).append('\n')
        append("  Comms (phishing SMS/email): ").append(WardenState.commsBlocked.get()).append('\n')
        append("  Money (UPI scam flags): ").append(WardenState.moneyBlocked.get()).append('\n')
        append("  Apps (stalkerware flags): ").append(WardenState.appsBlocked.get()).append('\n')
        append("  Identity (OTP / breach events): ").append(WardenState.identityBlocked.get()).append('\n')
        append("  Physical: ").append(WardenState.physicalBlocked.get()).append('\n')
        append('\n')

        val quarantined = WardenState.quarantinedPackages.toList()
        append("QUARANTINED APPS (").append(quarantined.size).append(")\n")
        if (quarantined.isEmpty()) append("  (none)\n")
        else for (p in quarantined.take(10)) append("  • ").append(p).append('\n')
        append('\n')

        val emails = WardenState.emailScans.toList()
        val phishingEmails = emails.count { it.isPhishing }
        // Split by source so the assistant can talk about email vs chat
        // phishing separately.
        val chatSources = setOf(
            "WhatsApp", "WhatsApp Business", "Telegram", "Signal",
            "Messenger", "Instagram", "Discord", "Slack", "Teams", "Messages",
        )
        val chats = emails.filter { it.source in chatSources }
        val mails = emails.filter { it.source !in chatSources }
        append("MESSAGE SCANNER (email + chat)\n")
        append("  Listener active: ").append(WardenState.emailListenerActive.get()).append('\n')
        append("  Total messages scanned: ").append(emails.size)
        append(" · flagged as phishing: ").append(phishingEmails).append('\n')
        append("  Email scans: ").append(mails.size)
        append("  · Chat scans: ").append(chats.size).append('\n')
        for (e in emails.take(6)) {
            append("  · ").append(if (e.isPhishing) "PHISH" else "clean")
            append(" [").append(e.score).append("/100]  ")
            append(e.source).append("  ")
            append(e.sender.ifBlank { "(no sender)" }).append(" — ")
            append(e.subject.take(60).ifBlank { "(no subject)" }).append('\n')
        }
        append('\n')

        val sensors = WardenState.sensorAccesses.toList()
        val liveNow = sensors.filter { it.active }
        val last24h = sensors.filter { it.startTs > System.currentTimeMillis() - 86_400_000L }
        append("MIC / CAMERA ACCESS\n")
        append("  Watcher active: ").append(WardenState.sensorWatcherActive.get()).append('\n')
        append("  Currently recording: ").append(liveNow.size).append('\n')
        for (s in liveNow) {
            append("  · ").append(s.kind.name).append(" — ").append(s.label).append(" (live)\n")
        }
        append("  In last 24h: ").append(last24h.size).append(" access events\n")
        val by24h = last24h.groupingBy { "${it.kind.name}:${it.label}" }.eachCount()
        for ((k, v) in by24h.entries.sortedByDescending { it.value }.take(5)) {
            append("  · ").append(k).append(" × ").append(v).append('\n')
        }
        append('\n')

        append("TOP TRACKER DOMAINS (last session)\n")
        val netBreak = WardenState.breakdown[WardenState.Pillar.NETWORK]
        if (netBreak == null || netBreak.isEmpty()) {
            append("  (none yet)\n")
        } else {
            for ((domain, n) in netBreak.entries.sortedByDescending { it.value }.take(8)) {
                append("  · ").append(domain).append(" × ").append(n).append('\n')
            }
        }
        append('\n')

        append("RECENT TIMELINE (last 8 events)\n")
        val tl = WardenState.timeline.toList()
        if (tl.isEmpty()) {
            append("  (no events yet)\n")
        } else {
            for (e in tl.take(8)) {
                append("  · ").append(tsFmt.format(Date(e.ts))).append("  [")
                append(e.pillar.label).append("]  ").append(e.title)
                if (e.detail.isNotBlank()) append(" — ").append(e.detail.take(80))
                append('\n')
            }
        }
        append("── END SNAPSHOT ──\n")
    }
}
