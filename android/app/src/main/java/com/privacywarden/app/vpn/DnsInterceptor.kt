package com.privacywarden.app.vpn

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Minimal in-VPN DNS interceptor.
 *
 *  - Reads IPv4 + UDP/53 packets coming out of the TUN.
 *  - Parses the question domain (real, the actual name the app looked up).
 *  - Forwards the original DNS query to a real upstream resolver via a
 *    `protect()`-bypassed socket so it reaches the internet outside our VPN.
 *  - Wraps the upstream response in a fresh IPv4+UDP packet (src/dst swapped,
 *    checksums computed) ready to be written back into the TUN — that gives
 *    Android the answer it expects so apps' DNS resolution still works.
 *
 * This means we see *real* domains for almost every app on the device while
 * leaving the rest of the network path untouched (so the phone keeps internet).
 */
class DnsInterceptor(
    private val protect: (DatagramSocket) -> Boolean,
    private val upstreamIp: String = "1.1.1.1",
) {

    /** Parsed view of an inbound DNS request packet. */
    data class Parsed(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val ihl: Int,
        val dnsBytes: ByteArray,
        val domain: String,
    )

    /** Try to parse a DNS query packet. Returns null if not a UDP/53 query. */
    fun parse(buf: ByteArray, n: Int): Parsed? {
        if (n < 28) return null
        if ((buf[0].toInt() ushr 4) != 4) return null  // IPv4 only
        val ihl = (buf[0].toInt() and 0x0f) * 4
        if (ihl < 20 || ihl + 8 > n) return null
        val proto = buf[9].toInt() and 0xff
        if (proto != 17) return null  // UDP only

        val srcPort = u16(buf, ihl)
        val dstPort = u16(buf, ihl + 2)
        if (dstPort != 53) return null

        val dnsStart = ihl + 8
        if (dnsStart + 12 > n) return null
        val dnsBytes = buf.copyOfRange(dnsStart, n)
        val domain = parseQName(dnsBytes) ?: return null
        if (domain.isBlank()) return null

        return Parsed(
            srcIp = buf.copyOfRange(12, 16),
            dstIp = buf.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            ihl = ihl,
            dnsBytes = dnsBytes,
            domain = domain,
        )
    }

    /**
     * Forward the parsed query to upstream resolver and build a complete
     * IPv4 + UDP packet wrapping the response, ready to be written into the TUN.
     */
    fun forwardAndPackage(parsed: Parsed): ByteArray? {
        val upstream = forward(parsed.dnsBytes) ?: return null
        return buildResponsePacket(parsed, upstream)
    }

    /**
     * Build a synthetic NXDOMAIN response for blocked domains. The original
     * question section is preserved, the response flag is set to "no such
     * domain", and answer counts are zeroed — apps' resolvers will treat the
     * lookup as a hard failure and the connection is never made. This is the
     * actual blocking primitive used when [com.privacywarden.app.vpn.RuleStore]
     * matches a domain.
     */
    fun buildBlockedResponse(parsed: Parsed): ByteArray? {
        val q = parsed.dnsBytes
        if (q.size < 12) return null
        val resp = q.copyOf()
        // Header layout: id (2), flags (2), qdcount (2), ancount (2), nscount (2), arcount (2)
        resp[2] = 0x81.toByte()   // QR=1 (response), AA=0, TC=0, RD=1
        resp[3] = 0x83.toByte()   // RA=1, Z=0, RCODE=3 (NXDOMAIN)
        // Zero out answer/authority/additional sections — keep the question.
        resp[6] = 0; resp[7] = 0
        resp[8] = 0; resp[9] = 0
        resp[10] = 0; resp[11] = 0
        // Truncate any extra bytes after the question — we only keep header + question.
        val questionEnd = findQuestionEnd(resp) ?: resp.size
        val trimmed = resp.copyOfRange(0, questionEnd)
        return buildResponsePacket(parsed, trimmed)
    }

    /** Walk the QNAME + type + class to find the byte just past the question section. */
    private fun findQuestionEnd(dns: ByteArray): Int? {
        var pos = 12
        var safety = 0
        while (pos < dns.size && safety++ < 64) {
            val len = dns[pos].toInt() and 0xff
            if (len == 0) { pos += 1; break }
            if ((len and 0xc0) != 0) return null
            if (pos + 1 + len > dns.size) return null
            pos += 1 + len
        }
        // qtype (2) + qclass (2)
        return if (pos + 4 <= dns.size) pos + 4 else null
    }

    private fun forward(query: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { sock ->
                protect(sock)
                sock.soTimeout = 3000
                val target = InetAddress.getByName(upstreamIp) as Inet4Address
                sock.send(DatagramPacket(query, query.size, target, 53))
                val buf = ByteArray(2048)
                val resp = DatagramPacket(buf, buf.size)
                sock.receive(resp)
                buf.copyOfRange(0, resp.length)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "upstream forward failed: ${t.message}")
            null
        }
    }

    /** Build IPv4 (no options) + UDP packet from upstream DNS response bytes. */
    private fun buildResponsePacket(p: Parsed, dns: ByteArray): ByteArray {
        val totalLen = 20 + 8 + dns.size
        val out = ByteArray(totalLen)
        // ── IPv4 header (20 bytes) ─────────────────────────────────────────
        out[0] = 0x45.toByte()                 // version 4, IHL 5
        out[1] = 0                             // TOS
        out[2] = ((totalLen ushr 8) and 0xff).toByte()
        out[3] = (totalLen and 0xff).toByte()
        out[4] = 0; out[5] = 0                 // ID
        out[6] = 0x40.toByte(); out[7] = 0     // DF flag, no fragment
        out[8] = 64                            // TTL
        out[9] = 17                            // proto UDP
        // checksum at [10..11] computed below
        // src IP = original dst (we are the resolver from the app's POV)
        System.arraycopy(p.dstIp, 0, out, 12, 4)
        // dst IP = original src
        System.arraycopy(p.srcIp, 0, out, 16, 4)
        val ipCsum = checksum(out, 0, 20)
        out[10] = ((ipCsum ushr 8) and 0xff).toByte()
        out[11] = (ipCsum and 0xff).toByte()
        // ── UDP header (8 bytes) ───────────────────────────────────────────
        out[20] = ((p.dstPort ushr 8) and 0xff).toByte()  // src port = 53
        out[21] = (p.dstPort and 0xff).toByte()
        out[22] = ((p.srcPort ushr 8) and 0xff).toByte()  // dst port = app ephemeral
        out[23] = (p.srcPort and 0xff).toByte()
        val udpLen = 8 + dns.size
        out[24] = ((udpLen ushr 8) and 0xff).toByte()
        out[25] = (udpLen and 0xff).toByte()
        out[26] = 0; out[27] = 0               // UDP checksum optional for IPv4
        // ── DNS payload ────────────────────────────────────────────────────
        System.arraycopy(dns, 0, out, 28, dns.size)
        return out
    }

    private fun parseQName(dns: ByteArray): String? {
        if (dns.size < 13) return null
        val qdcount = u16(dns, 4)
        if (qdcount < 1) return null
        val sb = StringBuilder()
        var pos = 12
        var safety = 0
        while (pos < dns.size && safety++ < 64) {
            val len = dns[pos].toInt() and 0xff
            if (len == 0) break
            if ((len and 0xc0) != 0) return null  // pointer in question — bail
            if (len > 63 || pos + 1 + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, pos + 1, len, Charsets.US_ASCII))
            pos += 1 + len
        }
        return sb.toString().lowercase()
    }

    private fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xff) shl 8) or (buf[off + 1].toInt() and 0xff)

    private fun checksum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        val end = off + len
        while (i < end - 1) {
            sum += ((buf[i].toInt() and 0xff) shl 8) or (buf[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xff) shl 8
        while ((sum ushr 16) != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }

    companion object { private const val TAG = "DnsInterceptor" }
}
