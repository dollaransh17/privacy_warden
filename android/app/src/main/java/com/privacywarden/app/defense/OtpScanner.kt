package com.privacywarden.app.defense

import android.content.Context
import android.net.Uri
import com.privacywarden.app.vpn.WardenState

/**
 * Real on-device OTP / breach-keyword SMS scanner.
 *
 * Scans the device's SMS inbox (READ_SMS perm) and surfaces:
 *   1) Recent OTPs — used by the Identity pillar to show OTP volume / sources.
 *   2) **OTP-storms** — bursts of >= 3 OTPs from different senders within a
 *      5-minute window. That pattern is the textbook fingerprint of an
 *      account-takeover / SIM-swap attempt and is reported as a high-severity
 *      Identity event in the timeline.
 *   3) Breach / leak keyword SMS ("your password was leaked", "data breach
 *      involving your account", etc.) — also a real, low-effort signal.
 *
 * Pure on-device. Nothing leaves the phone.
 */
object OtpScanner {

    data class Otp(
        val sender: String,
        val code: String,
        val ts: Long,
        val body: String,
    )

    data class Result(
        val totalScanned: Int,
        val otps: List<Otp>,
        val burstCount: Int,             // number of distinct OTP-storm windows
        val breachHits: List<Pair<String, String>>, // sender → snippet
    )

    /** OTP heuristics: 4-8 digit code preceded/followed by an OTP keyword. */
    private val otpRegex = Regex(
        """(?i)(?:otp|verification code|one[- ]time|passcode|security code)[^0-9]{0,20}([0-9]{4,8})|([0-9]{4,8})[^a-z0-9]{0,20}(?:otp|verification code|one[- ]time|passcode|security code)"""
    )
    private val breachKeywords = listOf(
        "data breach", "your password was leaked", "credentials exposed",
        "your account has been compromised", "leaked in a breach",
        "appeared in a breach", "haveibeenpwned",
    )

    /** Time window (ms) used for OTP-burst detection. */
    private const val BURST_WINDOW_MS = 5 * 60 * 1000L

    fun scan(ctx: Context, limit: Int = 500): Result {
        val otps = mutableListOf<Otp>()
        val breachHits = mutableListOf<Pair<String, String>>()
        var total = 0
        val uri = Uri.parse("content://sms/inbox")
        val cols = arrayOf("address", "body", "date")
        val cursor = runCatching {
            ctx.contentResolver.query(uri, cols, null, null, "date DESC LIMIT $limit")
        }.getOrNull() ?: return Result(0, emptyList(), 0, emptyList())
        cursor.use { c ->
            val iAddr = c.getColumnIndex("address")
            val iBody = c.getColumnIndex("body")
            val iDate = c.getColumnIndex("date")
            while (c.moveToNext()) {
                val addr = if (iAddr >= 0) c.getString(iAddr) ?: "(unknown)" else "(unknown)"
                val body = if (iBody >= 0) c.getString(iBody) ?: "" else ""
                val date = if (iDate >= 0) c.getLong(iDate) else System.currentTimeMillis()
                if (body.isBlank()) continue
                total++

                otpRegex.find(body)?.let { m ->
                    val code = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@let
                    otps += Otp(addr, code, date, body.take(140))
                }
                val low = body.lowercase()
                for (kw in breachKeywords) {
                    if (kw in low) {
                        breachHits += addr to body.take(140)
                        break
                    }
                }
            }
        }
        // Detect bursts: ≥ 3 OTPs from distinct senders within BURST_WINDOW_MS.
        // OTPs are ordered newest → oldest because of `date DESC` query.
        val bursts = mutableListOf<List<Otp>>()
        var i = 0
        while (i < otps.size) {
            val window = mutableListOf(otps[i])
            var j = i + 1
            while (j < otps.size && (otps[i].ts - otps[j].ts) <= BURST_WINDOW_MS) {
                window += otps[j]
                j++
            }
            if (window.map { it.sender }.toSet().size >= 3) {
                bursts += window
                i = j  // skip the consumed window
            } else {
                i++
            }
        }
        return Result(total, otps, bursts.size, breachHits)
    }

    fun scanAndPublish(ctx: Context, limit: Int = 500): Result {
        val r = scan(ctx, limit)
        if (r.otps.isNotEmpty()) {
            WardenState.identityLast.set(
                "Last OTP from ${r.otps.first().sender.take(20)} · ${r.otps.size} in window"
            )
            for (o in r.otps.take(10)) {
                WardenState.bumpBreakdown(WardenState.Pillar.IDENTITY, o.sender)
            }
        }
        if (r.burstCount > 0) {
            WardenState.identityBlocked.addAndGet(r.burstCount)
            WardenState.identityLast.set("OTP-storm × ${r.burstCount} · possible takeover attempt")
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.IDENTITY,
                    title = "OTP storm detected",
                    detail = "${r.burstCount} window(s) with ≥ 3 OTPs from different senders within 5 min",
                )
            )
        }
        for ((sender, snippet) in r.breachHits) {
            WardenState.identityBlocked.incrementAndGet()
            WardenState.bumpBreakdown(WardenState.Pillar.IDENTITY, "Breach SMS")
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.IDENTITY,
                    title = "Breach-notification SMS",
                    detail = "$sender · ${snippet.take(80)}",
                )
            )
        }
        if (r.otps.isEmpty() && r.breachHits.isEmpty() && r.totalScanned > 0) {
            WardenState.identityLast.set("Clean · ${r.totalScanned} SMS scanned, no OTP storm")
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.IDENTITY,
                    title = "Identity sweep clean",
                    detail = "Scanned ${r.totalScanned} SMS · no OTP-storm or breach pattern",
                )
            )
        }
        return r
    }
}
