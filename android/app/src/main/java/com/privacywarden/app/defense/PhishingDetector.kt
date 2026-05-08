package com.privacywarden.app.defense

import com.privacywarden.app.vpn.WardenState

/**
 * Lightweight rule-based phishing detector.
 *
 * Real product would use a TFLite model + URL-reputation API; for the demo we
 * apply layered string rules that catch the SMS/WhatsApp scams Indian users
 * actually receive day-to-day:
 *
 *   • Brand impersonation ("axisbank" inside a domain that isn't axisbank.com)
 *   • URL shorteners + bank context (bit.ly + "bank/UPI/account/KYC")
 *   • Typosquats (sbi-banking, axxis-bank, hdfc-online-secure, etc.)
 *   • Urgency phrases ("account locked", "click immediately", "in 2 hours")
 *   • OTP harvesters ("share OTP", "verification code", "confirm OTP")
 *   • Money-mule lures ("KYC pending", "refund", "credit card upgrade")
 */
object PhishingDetector {

    private val urgencyPhrases = listOf(
        "account locked", "account will be blocked", "blocked in 2 hours",
        "click immediately", "act now", "urgent action", "verify within 24",
        "kyc pending", "kyc expired", "dormant account", "your card is blocked",
    )

    private val otpPhrases = listOf(
        "share otp", "share the otp", "tell us your otp", "verification code",
        "confirm otp", "send otp", "enter the otp on call", "otp on this number",
    )

    private val moneyLures = listOf(
        "refund", "cashback", "lottery", "kbc winner", "prize", "click to claim",
        "credit card upgrade", "loan approved", "free gift", "courier pending",
    )

    private val shortenerHosts = listOf(
        "bit.ly", "tinyurl.com", "rb.gy", "t.co", "cutt.ly", "is.gd",
        "ow.ly", "rebrand.ly", "shorturl.at", "tiny.cc", "buff.ly",
    )

    /** Legitimate Indian bank/govt domains. */
    private val legitDomains = listOf(
        "sbi.co.in", "onlinesbi.sbi", "yonosbi.sbi", "hdfcbank.com", "icicibank.com",
        "axisbank.com", "kotak.com", "pnbindia.in", "bankofbaroda.in",
        "uidai.gov.in", "incometax.gov.in", "epfindia.gov.in", "irctc.co.in",
        "phonepe.com", "paytm.com", "gpay.app", "bhimupi.org.in",
    )

    /** Brand fragments we expect to see only inside the legit domains above. */
    private val protectedBrands = listOf(
        "sbi", "hdfc", "icici", "axis", "kotak", "pnb", "boi", "bob",
        "uidai", "aadhaar", "phonepe", "paytm", "gpay", "upi",
    )

    data class Verdict(
        val message: String,
        val score: Int,             // 0–100, >= 60 considered phishing
        val reasons: List<String>,
        val matchedUrls: List<String>,
    ) {
        val isPhishing: Boolean get() = score >= 60
    }

    private val urlRegex = Regex("""https?://[\w.\-/?=&%#:]+""", RegexOption.IGNORE_CASE)

    fun analyze(message: String): Verdict {
        val lower = message.lowercase()
        val reasons = mutableListOf<String>()
        var score = 0

        // Phrase-level signals
        urgencyPhrases.firstOrNull { it in lower }?.let {
            score += 25; reasons += "Urgency phrase: \"$it\""
        }
        otpPhrases.firstOrNull { it in lower }?.let {
            score += 60; reasons += "Asks for OTP: \"$it\""
        }
        moneyLures.firstOrNull { it in lower }?.let {
            score += 20; reasons += "Money/refund lure: \"$it\""
        }

        // URL signals
        val urls = urlRegex.findAll(message).map { it.value }.toList()
        for (url in urls) {
            val host = host(url) ?: continue
            val lhost = host.lowercase()
            if (shortenerHosts.any { lhost == it || lhost.endsWith(".$it") }) {
                score += 30
                reasons += "URL shortener used: $host"
            }
            val isLegit = legitDomains.any { lhost == it || lhost.endsWith(".$it") }
            val brandHit = protectedBrands.firstOrNull { brand ->
                brand in lhost && !isLegit
            }
            if (brandHit != null) {
                score += 50
                reasons += "Brand impersonation: '$brandHit' on '$host'"
            }
            if (lhost.count { it == '-' } >= 2) {
                score += 10
                reasons += "Suspicious hyphenated host: $host"
            }
        }

        return Verdict(
            message = message,
            score = score.coerceAtMost(100),
            reasons = reasons,
            matchedUrls = urls,
        )
    }

    private fun host(url: String): String? = try {
        java.net.URI(url).host
    } catch (_: Throwable) { null }

    /** Analyze and publish if phishing. */
    fun analyzeAndPublish(message: String, source: String = "SMS"): Verdict {
        val v = analyze(message)
        if (v.isPhishing) {
            WardenState.commsBlocked.incrementAndGet()
            WardenState.commsLast.set("$source phishing · score ${v.score}")
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.COMMS,
                    title = "Phishing $source blocked",
                    detail = (v.reasons.firstOrNull() ?: "Suspicious content") +
                        if (v.matchedUrls.isNotEmpty()) " · ${v.matchedUrls.first()}" else "",
                )
            )
        }
        return v
    }
}
