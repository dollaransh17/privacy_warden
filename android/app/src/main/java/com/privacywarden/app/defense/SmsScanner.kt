package com.privacywarden.app.defense

import android.content.Context
import android.net.Uri
import com.privacywarden.app.vpn.WardenState

/**
 * Real on-device SMS inbox scanner.
 *
 * With the user's READ_SMS permission grant we read the SMS inbox via the
 * `content://sms/inbox` content provider and run every message through
 * `PhishingDetector`. We surface aggregate stats + the top suspicious messages
 * back to the caller; flagged messages are also pushed to the timeline.
 *
 * No SMS body ever leaves the device — analysis is fully local.
 */
object SmsScanner {

    data class Result(
        val totalScanned: Int,
        val flagged: List<Flagged>,
        val bySender: Map<String, Int>, // suspicious-count by sender
        val urlDomainsSeen: Map<String, Int>,
    )

    data class Flagged(
        val sender: String,
        val body: String,
        val score: Int,
        val reasons: List<String>,
        val urls: List<String>,
        val ts: Long,
    )

    /** All distinct senders present in the inbox, most-recent first. */
    fun listSenders(ctx: Context, limit: Int = 1000): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        val uri = Uri.parse("content://sms/inbox")
        val cursor = runCatching {
            ctx.contentResolver.query(uri, arrayOf("address"), null, null, "date DESC LIMIT $limit")
        }.getOrNull() ?: return emptyList()
        cursor.use { c ->
            val iAddr = c.getColumnIndex("address")
            while (c.moveToNext()) {
                val a = if (iAddr >= 0) c.getString(iAddr) ?: "(unknown)" else "(unknown)"
                counts.merge(a, 1) { x, y -> x + y }
            }
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** Scan up to [limit] most-recent inbox messages. Returns aggregate result.
     *  If [senderFilter] is non-null and non-"ALL", only that sender's messages are scanned. */
    fun scan(ctx: Context, limit: Int = 500, senderFilter: String? = null): Result {
        val flagged = mutableListOf<Flagged>()
        val bySender = mutableMapOf<String, Int>()
        val urlDomains = mutableMapOf<String, Int>()
        var total = 0
        val uri = Uri.parse("content://sms/inbox")
        val cols = arrayOf("address", "body", "date")
        val cursor = runCatching {
            ctx.contentResolver.query(uri, cols, null, null, "date DESC LIMIT $limit")
        }.getOrNull() ?: return Result(0, emptyList(), emptyMap(), emptyMap())
        cursor.use { c ->
            val iAddr = c.getColumnIndex("address")
            val iBody = c.getColumnIndex("body")
            val iDate = c.getColumnIndex("date")
            while (c.moveToNext()) {
                val addr = if (iAddr >= 0) c.getString(iAddr) ?: "(unknown)" else "(unknown)"
                val body = if (iBody >= 0) c.getString(iBody) ?: "" else ""
                val date = if (iDate >= 0) c.getLong(iDate) else System.currentTimeMillis()
                if (body.isBlank()) continue
                if (senderFilter != null && senderFilter != "ALL" && addr != senderFilter) continue
                total += 1
                val v = PhishingDetector.analyze(body)
                if (v.isPhishing) {
                    flagged += Flagged(addr, body, v.score, v.reasons, v.matchedUrls, date)
                    bySender.merge(addr, 1) { a, b -> a + b }
                    for (u in v.matchedUrls) {
                        val host = runCatching { java.net.URI(u).host }.getOrNull()?.lowercase()
                            ?: continue
                        urlDomains.merge(host, 1) { a, b -> a + b }
                    }
                }
            }
        }
        return Result(
            totalScanned = total,
            flagged = flagged.sortedByDescending { it.score },
            bySender = bySender.toSortedMap(),
            urlDomainsSeen = urlDomains.toSortedMap(),
        )
    }

    /** Scan inbox AND publish stats + per-flagged events to WardenState. */
    fun scanAndPublish(ctx: Context, limit: Int = 500, senderFilter: String? = null): Result {
        val r = scan(ctx, limit, senderFilter)
        if (r.flagged.isNotEmpty()) {
            val top = r.flagged.first()
            WardenState.commsBlocked.addAndGet(r.flagged.size)
            WardenState.commsLast.set(
                "${r.flagged.size} phishing SMS · top from ${top.sender}"
            )
            // bump pie-chart breakdown (sender-of-phishing)
            for (f in r.flagged.take(20)) {
                WardenState.bumpBreakdown(WardenState.Pillar.COMMS, f.sender)
            }
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.COMMS,
                    title = "Phishing SMS sweep: ${r.flagged.size} flagged",
                    detail = "Top: ${top.sender} · score ${top.score} · ${top.reasons.firstOrNull() ?: ""}",
                )
            )
        } else if (r.totalScanned > 0) {
            WardenState.commsLast.set("Inbox clean · ${r.totalScanned} scanned")
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.COMMS,
                    title = "SMS sweep clean",
                    detail = "Scanned ${r.totalScanned} most recent messages, no phishing",
                )
            )
        }
        return r
    }
}
