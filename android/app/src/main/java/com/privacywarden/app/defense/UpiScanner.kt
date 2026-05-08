package com.privacywarden.app.defense

import android.content.Context
import android.net.Uri
import com.privacywarden.app.vpn.WardenState

/**
 * Real on-device UPI scam scanner.
 *
 * Reads SMS bodies via the inbox `ContentResolver` (requires READ_SMS, same
 * permission already used by [SmsScanner]) and extracts every UPI VPA mention
 * (`name@bank` form). Each VPA is scored against:
 *
 *   1) An offline blocklist of impersonation suffixes (kyc., refund., support-paytm, etc.)
 *      that show up overwhelmingly in scam reports.
 *   2) Surrounding-message context — if the SMS is itself flagged as phishing
 *      by [PhishingDetector], every VPA in it is implicated.
 *
 * Findings are written to the MONEY pillar in [WardenState] so the chart,
 * counter and timeline on the Money dashboard reflect actual SMS data from
 * the user's own inbox — not synthetic numbers.
 */
object UpiScanner {

    /** A single VPA finding from the inbox. */
    data class Finding(
        val vpa: String,
        val sender: String,
        val body: String,
        val ts: Long,
        val score: Int,        // 0..100 risk score
        val reasons: List<String>,
    )

    /** Aggregate result of one scan. */
    data class Result(
        val totalSmsScanned: Int,
        val totalVpasFound: Int,
        val flagged: List<Finding>,
        val byVpa: Map<String, Int>,
    )

    /**
     * Substrings (case-insensitive) that appear overwhelmingly in scam VPAs
     * collected by Indian cyber-crime portals. Matched as a simple "contains"
     * check against the local-part of the VPA.
     */
    private val scamLocalPatterns = listOf(
        "kyc", "refund", "support-paytm", "support.paytm",
        "rbi", "verify", "update", "block", "officer", "police",
        "lottery", "prize", "winner", "claim", "reward",
        "fastag-help", "courier-fee", "olxbuy", "olx-buy",
    )

    /** Bank suffixes (after `@`) that we recognise as legit UPI handles. Used
     *  only to pretty-print the bank name; doesn't affect scoring. */
    private val knownBanks = setOf(
        "okaxis", "okhdfcbank", "okicici", "oksbi", "okbizaxis",
        "ybl", "axl", "ibl", "axisbank", "hdfcbank", "icici", "sbi",
        "paytm", "upi", "fbl", "barodampay", "kotak", "yesbank",
    )

    /** VPA pattern: 3+ chars, single @, then 2+ chars, no spaces. */
    private val vpaRegex = Regex("""\b([a-z0-9._\-]{3,})@([a-z]{2,})\b""", RegexOption.IGNORE_CASE)

    /** Scan up to [limit] most-recent inbox messages for UPI VPAs.
     *  Pure read — does not mutate WardenState. */
    fun scan(ctx: Context, limit: Int = 500): Result {
        val findings = mutableListOf<Finding>()
        val byVpa = mutableMapOf<String, Int>()
        var totalSms = 0
        var totalVpas = 0
        val uri = Uri.parse("content://sms/inbox")
        val cols = arrayOf("address", "body", "date")
        val cursor = runCatching {
            ctx.contentResolver.query(uri, cols, null, null, "date DESC LIMIT $limit")
        }.getOrNull() ?: return Result(0, 0, emptyList(), emptyMap())
        cursor.use { c ->
            val iAddr = c.getColumnIndex("address")
            val iBody = c.getColumnIndex("body")
            val iDate = c.getColumnIndex("date")
            while (c.moveToNext()) {
                val addr = if (iAddr >= 0) c.getString(iAddr) ?: "(unknown)" else "(unknown)"
                val body = if (iBody >= 0) c.getString(iBody) ?: "" else ""
                val date = if (iDate >= 0) c.getLong(iDate) else System.currentTimeMillis()
                if (body.isBlank()) continue
                totalSms++

                val matches = vpaRegex.findAll(body).toList()
                if (matches.isEmpty()) continue

                // Run the surrounding text through the phishing detector once
                // — its score boosts every VPA found in the message.
                val phish = PhishingDetector.analyze(body)

                for (m in matches) {
                    val full = m.value.lowercase()
                    val (local, bank) = full.split('@', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
                    totalVpas++
                    val reasons = mutableListOf<String>()
                    var score = 5  // every VPA gets a baseline; legit ones stay near zero
                    for (p in scamLocalPatterns) {
                        if (local.contains(p)) {
                            reasons += "Impersonation pattern: '$p'"
                            score += 35
                            break
                        }
                    }
                    if (bank !in knownBanks) {
                        reasons += "Unrecognised bank handle '@$bank'"
                        score += 20
                    }
                    if (phish.isPhishing) {
                        reasons += "Sent inside a phishing SMS (score ${phish.score})"
                        score += (phish.score / 2).coerceAtMost(40)
                    }
                    score = score.coerceIn(0, 100)
                    if (score >= 35) {
                        findings += Finding(full, addr, body, date, score, reasons)
                        byVpa.merge(full, 1) { a, b -> a + b }
                    }
                }
            }
        }
        return Result(
            totalSmsScanned = totalSms,
            totalVpasFound = totalVpas,
            flagged = findings.sortedByDescending { it.score },
            byVpa = byVpa.toSortedMap(),
        )
    }

    /** Scan AND publish findings to the MONEY pillar in WardenState. */
    fun scanAndPublish(ctx: Context, limit: Int = 500): Result {
        val r = scan(ctx, limit)
        if (r.flagged.isNotEmpty()) {
            WardenState.moneyBlocked.addAndGet(r.flagged.size)
            val top = r.flagged.first()
            WardenState.moneyLast.set("Flagged ${top.vpa} · score ${top.score}")
            for (f in r.flagged.take(20)) {
                WardenState.bumpBreakdown(WardenState.Pillar.MONEY, f.vpa)
                WardenState.pushEvent(
                    WardenState.TimelineEvent(
                        pillar = WardenState.Pillar.MONEY,
                        title = "Suspicious UPI VPA flagged",
                        detail = "${f.vpa} from ${f.sender} · ${f.reasons.firstOrNull().orEmpty()}",
                        ts = f.ts,
                    )
                )
            }
        } else if (r.totalSmsScanned > 0) {
            WardenState.moneyLast.set("Clean · ${r.totalSmsScanned} SMS scanned, ${r.totalVpasFound} UPI handles seen")
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.MONEY,
                    title = "UPI sweep clean",
                    detail = "Scanned ${r.totalSmsScanned} SMS · ${r.totalVpasFound} VPAs · none matched scam patterns",
                )
            )
        }
        return r
    }
}
