package com.privacywarden.app.assistant

/**
 * Handles trivial conversational input — greetings, thanks, "who are you" —
 * **without** making a network round-trip to Groq.
 *
 * Why: a single "hi" was previously sending the full ~2 KB privacy snapshot
 * to Groq, taking 1-3 seconds end-to-end and frequently dragging the model
 * into reciting status numbers when the user just wanted to say hello.
 * Catching small talk here gives an instant (<10 ms) reply, keeps the
 * conversation natural, and saves API tokens.
 *
 * The matcher is intentionally conservative: it only triggers on short,
 * unambiguous greetings/thanks/"who are you" phrases. Anything that smells
 * like a real privacy question (length > 30 chars, contains keywords like
 * "phishing", "tracker", "scan", etc.) falls through to the LLM.
 */
object SmallTalk {

    /**
     * Privacy-related keywords that should ALWAYS go to the LLM, even if the
     * message also contains a greeting (e.g. "hi, am I safe?").
     */
    private val privacyKeywords = listOf(
        "safe", "phish", "tracker", "block", "scan", "report", "summary",
        "summari", "today", "24", "hour", "whatsapp", "telegram", "signal",
        "email", "sms", "mic", "camera", "vpn", "panic", "stalker", "spy",
        "permission", "privacy", "warden", "threat", "risk", "alert", "warn",
        "show", "list", "find", "explain", "tell me", "what about", "any ",
        "how many", "did ", "have i", "am i", "is my",
    )

    private val greetings = setOf(
        "hi", "hii", "hiii", "hello", "helo", "hey", "heya", "hola", "yo",
        "sup", "wassup", "whatsup", "hi there", "hello there", "hey there",
        "good morning", "morning", "good evening", "evening", "good night",
        "good afternoon", "namaste", "namaskar", "hi assistant", "hello assistant",
        "hi bot", "hello bot",
    )

    private val thanks = setOf(
        "thanks", "thank you", "thanku", "thnks", "thx", "ty", "tysm",
        "thanks!", "thank you!", "thanks bot", "thanks assistant",
        "appreciate it", "appreciated", "great thanks",
    )

    private val acks = setOf(
        "ok", "okay", "k", "kk", "cool", "nice", "great", "awesome", "alright",
        "sure", "got it", "gotcha", "fine", "good",
    )

    private val goodbyes = setOf(
        "bye", "byee", "goodbye", "see you", "see ya", "cya", "later",
        "ttyl", "cu", "bye bot",
    )

    private val whoAreYou = listOf(
        "who are you", "what are you", "what r u", "who r u", "what's your name",
        "whats your name", "your name", "introduce yourself", "tell me about yourself",
        "what can you do", "what do you do", "how can you help", "help me",
        "help", "?",
    )

    /**
     * "What is this app?" style questions. Matched as substrings (case-folded)
     * so phrasings like "tell me about privacy warden" or "what is this app
     * all about" all hit the same canned answer.
     */
    private val aboutAppFragments = listOf(
        "what is this app", "what's this app", "whats this app",
        "what does this app", "what does the app",
        "what is privacy warden", "what's privacy warden", "whats privacy warden",
        "tell me about this app", "tell me about the app",
        "tell me about privacy warden", "tell me about warden",
        "explain this app", "explain the app", "explain privacy warden",
        "about this app", "about the app", "about privacy warden",
        "what is the app", "what's the app", "whats the app",
        "what is this", "whats this", "what's this",
        "describe this app", "describe the app", "describe privacy warden",
        "how does this app work", "how does the app work",
        "what is privacywarden", "what does this do", "what does it do",
    )

    private const val ABOUT_APP_REPLY =
        "Privacy Warden is an on-device privacy & security shield for your Android phone. " +
        "Everything runs locally — your data never leaves the device (except this chat, " +
        "which talks to Groq for the AI replies).\n\n" +
        "What it does, in 6 pillars:\n\n" +
        "• Tracker firewall — a built-in VPN blocks ad/analytics tracker domains at the DNS layer in real time.\n\n" +
        "• Message scanner — scores Gmail, Outlook, WhatsApp, Telegram, Signal, Messenger and SMS for phishing the moment they arrive, and posts a heads-up warning if a scam is detected.\n\n" +
        "• Stalkerware scan — inspects every installed app for spyware-style behaviour and lets you quarantine anything suspicious.\n\n" +
        "• Mic & camera watcher — shows you exactly which app turned on your mic or camera, with timestamps and durations.\n\n" +
        "• Panic mode — one big red button that maxes protections, quarantines flagged apps, and locks things down.\n\n" +
        "• AI assistant — that's me. I can explain findings, summarize the last 24 hours, or generate a full PDF report you can share.\n\n" +
        "Tap any of the suggestion chips above to try it, or ask me something specific like \"any phishing today?\" or \"summarize the last 24 hours\"."

    /**
     * Reply to small talk without hitting the network. Returns null if the
     * text is NOT small talk and should be routed to the LLM as usual.
     */
    fun replyOrNull(rawText: String): String? {
        val text = rawText.trim().lowercase()
        if (text.isEmpty()) return null

        // ── 1. "What is this app?" — checked FIRST, before the privacy-keyword
        // bail-out, because phrasings like "tell me about privacy warden" or
        // "explain this app" contain words ("tell me", "explain") that would
        // otherwise route to the LLM with an under-informed system prompt.
        if (aboutAppFragments.any { it in text }) return ABOUT_APP_REPLY

        // ── 2. Anything substantively long is almost certainly a real question.
        if (text.length > 32) return null

        // ── 3. If a privacy keyword is in there, route to LLM even if there's
        // a "hi" in front (e.g. "hi, am I safe today?").
        if (privacyKeywords.any { it in text }) return null

        // Strip trailing punctuation / emojis for clean matching.
        val core = text.trimEnd('.', '!', '?', ',', ' ').trim()

        return when {
            core in greetings -> randomOf(
                "Hey! I'm here whenever you want to check on your privacy. Tap a suggestion above, or ask me anything — like \"any phishing today?\" or \"summarize the last 24 hours\".",
                "Hi! I'm your Privacy Warden assistant. Ask about trackers, flagged emails, WhatsApp scams, or just say \"summarize today\" for a quick rundown.",
                "Hello! Ready when you are. Try one of the chips above, or ask me something specific about your privacy.",
            )
            core in thanks -> randomOf(
                "Anytime — that's what I'm here for. Anything else you'd like to check?",
                "You're welcome! Ping me whenever you want a privacy check-in.",
                "Glad to help. Tap \"Summarize today\" if you want the day's rundown.",
            )
            core in acks -> randomOf(
                "Cool — let me know if you want to dig into anything else.",
                "Got it. I'm here when you need me.",
            )
            core in goodbyes -> randomOf(
                "Bye! I'll keep watching things in the background — tap me anytime.",
                "Take care! Privacy Warden stays on guard even when we're not chatting.",
            )
            whoAreYou.any { core == it || core.startsWith(it) } -> {
                "I'm the Privacy Warden assistant — an on-device privacy expert with a live view of your device.\n\n" +
                "I can tell you what trackers got blocked, whether any WhatsApp/email/SMS looked like phishing, " +
                "what apps used your mic or camera, and summarize the last 24 hours. I can also generate a full PDF report.\n\n" +
                "Try one of the chips above, or just ask in plain English."
            }
            else -> null
        }
    }

    private fun randomOf(vararg options: String): String =
        options.random()
}
