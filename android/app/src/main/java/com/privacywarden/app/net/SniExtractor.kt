package com.privacywarden.app.net

import java.nio.ByteBuffer

/**
 * Minimal TLS ClientHello SNI extractor.
 *
 * Reads the unencrypted Server Name Indication from a TLS 1.2/1.3 ClientHello.
 * Returns null if the bytes don't look like a ClientHello or the extension is absent.
 *
 * No decryption performed. This is the same technique used by iOS App Privacy Report.
 */
object SniExtractor {

    fun parse(bytes: ByteArray): String? = try {
        parseInternal(bytes)
    } catch (t: Throwable) {
        null
    }

    private fun parseInternal(bytes: ByteArray): String? {
        if (bytes.size < 43) return null
        val buf = ByteBuffer.wrap(bytes)
        // TLS Record Layer: type(1) version(2) length(2)
        val recordType = buf.get().toInt() and 0xff
        if (recordType != 0x16) return null // not Handshake
        buf.position(buf.position() + 2) // record version
        val recLen = (buf.short.toInt() and 0xffff)
        if (recLen <= 0) return null

        // Handshake: type(1) length(3)
        val hsType = buf.get().toInt() and 0xff
        if (hsType != 0x01) return null // not ClientHello
        // 3-byte length
        buf.position(buf.position() + 3)
        // client_version (2)
        buf.position(buf.position() + 2)
        // random (32)
        buf.position(buf.position() + 32)
        // session_id
        val sidLen = buf.get().toInt() and 0xff
        buf.position(buf.position() + sidLen)
        // cipher_suites
        val csLen = buf.short.toInt() and 0xffff
        buf.position(buf.position() + csLen)
        // compression_methods
        val cmLen = buf.get().toInt() and 0xff
        buf.position(buf.position() + cmLen)
        if (buf.remaining() < 2) return null
        val extLen = buf.short.toInt() and 0xffff
        if (extLen > buf.remaining()) return null

        val end = buf.position() + extLen
        while (buf.position() < end - 4) {
            val extType = buf.short.toInt() and 0xffff
            val extDataLen = buf.short.toInt() and 0xffff
            if (extType == 0x0000) { // server_name
                // server_name_list_length(2) name_type(1) name_length(2) name(n)
                buf.position(buf.position() + 2)
                val nameType = buf.get().toInt() and 0xff
                if (nameType != 0) return null
                val nameLen = buf.short.toInt() and 0xffff
                if (nameLen > buf.remaining()) return null
                val name = ByteArray(nameLen)
                buf.get(name)
                return String(name, Charsets.US_ASCII)
            } else {
                buf.position(buf.position() + extDataLen)
            }
        }
        return null
    }
}
