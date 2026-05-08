package com.privacywarden.app.net

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Extracts the SNI from a QUIC v1 Initial packet (RFC 9000, RFC 9001).
 *
 * QUIC Initial keys are deterministically derived from the Destination Connection ID,
 * so we can decrypt any Initial packet on the wire without seeing the handshake.
 *
 * Steps:
 *   1. Recognise long header + version 1 + Initial packet type.
 *   2. Parse DCID, SCID, token, length fields.
 *   3. Derive client_initial_secret -> key/iv/hp using HKDF-SHA256.
 *   4. Use the HP key to remove header protection on the first byte and the packet number.
 *   5. AES-128-GCM-decrypt the payload (AAD = unprotected header).
 *   6. Reassemble CRYPTO frames into the TLS ClientHello, then reuse [SniExtractor].
 *
 * Returns null if the packet is not a parseable QUIC v1 Initial or has no SNI.
 *
 * Note: this is a single-packet decoder. Large ClientHellos (TLS 1.3 with many
 * extensions) sometimes span multiple Initial packets; handling that needs CRYPTO
 * frame reassembly across UDP datagrams which is out of scope for the v0 demo.
 */
object QuicSniExtractor {

    private val INITIAL_SALT_V1 = hex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")

    /**
     * Try to parse [pkt] (length [n]) as a QUIC v1 Initial packet and extract its SNI.
     */
    fun parse(pkt: ByteArray, n: Int): String? {
        if (n < 7) return null
        val first = pkt[0].toInt() and 0xff
        // Long header bit + fixed bit + Initial packet type (00) → high nibble == 0xC
        if ((first and 0xf0) != 0xc0) return null

        // Version (4 bytes) — must be 1.
        val version =
            ((pkt[1].toInt() and 0xff) shl 24) or
            ((pkt[2].toInt() and 0xff) shl 16) or
            ((pkt[3].toInt() and 0xff) shl 8) or
            (pkt[4].toInt() and 0xff)
        if (version != 1) return null

        var off = 5
        if (off >= n) return null
        val dcil = pkt[off].toInt() and 0xff; off += 1
        if (dcil > 20 || off + dcil > n) return null
        val dcid = pkt.copyOfRange(off, off + dcil); off += dcil

        if (off >= n) return null
        val scil = pkt[off].toInt() and 0xff; off += 1
        if (scil > 20 || off + scil > n) return null
        off += scil

        // Token length (varint) + token
        val tokLen = readVarint(pkt, off, n) ?: return null
        off = tokLen.nextOff
        if (off + tokLen.value.toInt() > n) return null
        off += tokLen.value.toInt()

        // Length (varint) — covers PN + ciphertext + auth tag
        val lenVar = readVarint(pkt, off, n) ?: return null
        off = lenVar.nextOff
        val pnOffset = off
        val payloadEnd = pnOffset + lenVar.value.toInt()
        if (payloadEnd > n) return null

        // Sample for header protection: 16 bytes starting at pnOffset+4.
        if (pnOffset + 4 + 16 > n) return null
        val sample = pkt.copyOfRange(pnOffset + 4, pnOffset + 4 + 16)

        // Derive Initial keys.
        val keys = deriveInitialKeys(dcid) ?: return null

        // Header protection mask = AES-128-ECB-encrypt(hp, sample)[0..5].
        val mask = aesEcbEncryptBlock(keys.hp, sample) ?: return null

        val firstUnprot = first xor (mask[0].toInt() and 0x0f)
        val pnLen = (firstUnprot and 0x03) + 1
        if (pnOffset + pnLen > n) return null

        // Unprotect packet number.
        val pnBytes = ByteArray(pnLen)
        for (i in 0 until pnLen) {
            pnBytes[i] = (pkt[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        }
        var packetNumber = 0L
        for (b in pnBytes) packetNumber = (packetNumber shl 8) or (b.toLong() and 0xff)

        // Reconstruct AAD = unprotected header (everything up to end of PN).
        val aad = ByteArray(pnOffset + pnLen)
        System.arraycopy(pkt, 0, aad, 0, aad.size)
        aad[0] = firstUnprot.toByte()
        for (i in 0 until pnLen) aad[pnOffset + i] = pnBytes[i]

        val ciphertext = pkt.copyOfRange(pnOffset + pnLen, payloadEnd)
        val nonce = xorIv(keys.iv, packetNumber)
        val plaintext = aesGcmDecrypt(keys.key, nonce, ciphertext, aad) ?: return null

        // Walk frames; collect CRYPTO frame data into a buffer.
        val cryptoData = collectCryptoFrames(plaintext) ?: return null

        // The CRYPTO frame data IS the TLS ClientHello handshake message
        // (handshake type 0x01 + 24-bit length + body). SniExtractor expects a TLS
        // record, so wrap it in a synthetic TLS record header.
        val tlsRecord = wrapAsTlsRecord(cryptoData) ?: return null
        return SniExtractor.parse(tlsRecord)
    }

    // ── crypto helpers ────────────────────────────────────────────────────────

    private data class InitialKeys(val key: ByteArray, val iv: ByteArray, val hp: ByteArray)

    private fun deriveInitialKeys(dcid: ByteArray): InitialKeys? = try {
        val initialSecret = hkdfExtract(INITIAL_SALT_V1, dcid)
        val cis = hkdfExpandLabel(initialSecret, "client in", 32)
        InitialKeys(
            key = hkdfExpandLabel(cis, "quic key", 16),
            iv  = hkdfExpandLabel(cis, "quic iv", 12),
            hp  = hkdfExpandLabel(cis, "quic hp", 16),
        )
    } catch (t: Throwable) { null }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        // HKDF-Extract using HMAC-SHA256.
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /** TLS 1.3 HKDF-Expand-Label per RFC 8446 §7.1, used by QUIC v1. */
    private fun hkdfExpandLabel(secret: ByteArray, label: String, length: Int): ByteArray {
        val full = "tls13 $label".toByteArray()
        val info = ByteArray(2 + 1 + full.size + 1)
        info[0] = (length ushr 8).toByte()
        info[1] = length.toByte()
        info[2] = full.size.toByte()
        System.arraycopy(full, 0, info, 3, full.size)
        info[3 + full.size] = 0  // empty context

        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters.skipExtractParameters(secret, info))
        val out = ByteArray(length)
        gen.generateBytes(out, 0, length)
        return out
    }

    private fun aesEcbEncryptBlock(key: ByteArray, sample: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.doFinal(sample.copyOf(16))
    } catch (t: Throwable) { null }

    private fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray, aad: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(ct)
    } catch (t: Throwable) { null }

    private fun xorIv(iv: ByteArray, packetNumber: Long): ByteArray {
        val nonce = iv.copyOf()
        // Right-align packet number into 12 bytes and XOR.
        for (i in 0 until 8) {
            val shift = (7 - i) * 8
            nonce[nonce.size - 8 + i] =
                (nonce[nonce.size - 8 + i].toInt() xor ((packetNumber ushr shift).toInt() and 0xff)).toByte()
        }
        return nonce
    }

    // ── frame parsing ─────────────────────────────────────────────────────────

    /** Walk QUIC frames in [plaintext] and return concatenated CRYPTO data, in offset order. */
    private fun collectCryptoFrames(plaintext: ByteArray): ByteArray? {
        // Map offset → bytes; assemble in offset order at the end.
        val pieces = mutableListOf<Pair<Long, ByteArray>>()
        var p = 0
        while (p < plaintext.size) {
            val type = plaintext[p].toInt() and 0xff
            when (type) {
                0x00 -> { p++ }                     // PADDING
                0x01 -> { p++ }                     // PING
                0x02, 0x03 -> {                     // ACK
                    p++
                    val largest = readVarint(plaintext, p, plaintext.size) ?: return null
                    p = largest.nextOff
                    val delay = readVarint(plaintext, p, plaintext.size) ?: return null
                    p = delay.nextOff
                    val rangeCount = readVarint(plaintext, p, plaintext.size) ?: return null
                    p = rangeCount.nextOff
                    val firstAck = readVarint(plaintext, p, plaintext.size) ?: return null
                    p = firstAck.nextOff
                    repeat(rangeCount.value.toInt()) {
                        val gap = readVarint(plaintext, p, plaintext.size) ?: return null
                        p = gap.nextOff
                        val len = readVarint(plaintext, p, plaintext.size) ?: return null
                        p = len.nextOff
                    }
                    if (type == 0x03) {
                        // ECN counts: 3 varints
                        repeat(3) {
                            val v = readVarint(plaintext, p, plaintext.size) ?: return null
                            p = v.nextOff
                        }
                    }
                }
                0x06 -> {                           // CRYPTO
                    p++
                    val off = readVarint(plaintext, p, plaintext.size) ?: return null
                    p = off.nextOff
                    val len = readVarint(plaintext, p, plaintext.size) ?: return null
                    p = len.nextOff
                    val end = p + len.value.toInt()
                    if (end > plaintext.size) return null
                    pieces.add(off.value to plaintext.copyOfRange(p, end))
                    p = end
                }
                else -> return null  // unsupported frame in Initial
            }
        }
        if (pieces.isEmpty()) return null
        pieces.sortBy { it.first }
        // Concat (we ignore gaps — single-packet ClientHellos won't have them).
        var total = 0
        for ((_, b) in pieces) total += b.size
        val out = ByteArray(total)
        var w = 0
        for ((_, b) in pieces) { System.arraycopy(b, 0, out, w, b.size); w += b.size }
        return out
    }

    /** Wrap a TLS handshake message (e.g. ClientHello) as a TLS record so SniExtractor can read it. */
    private fun wrapAsTlsRecord(handshake: ByteArray): ByteArray? {
        if (handshake.isEmpty()) return null
        val rec = ByteArray(5 + handshake.size)
        rec[0] = 0x16                  // content type = handshake
        rec[1] = 0x03; rec[2] = 0x03   // legacy version TLS 1.2
        rec[3] = (handshake.size ushr 8).toByte()
        rec[4] = handshake.size.toByte()
        System.arraycopy(handshake, 0, rec, 5, handshake.size)
        return rec
    }

    // ── varint + hex ──────────────────────────────────────────────────────────

    private data class Varint(val value: Long, val nextOff: Int)

    private fun readVarint(b: ByteArray, off: Int, end: Int): Varint? {
        if (off >= end) return null
        val first = b[off].toInt() and 0xff
        val len = 1 shl (first ushr 6)
        if (off + len > end) return null
        var v = (first and 0x3f).toLong()
        for (i in 1 until len) v = (v shl 8) or (b[off + i].toLong() and 0xff)
        return Varint(v, off + len)
    }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
