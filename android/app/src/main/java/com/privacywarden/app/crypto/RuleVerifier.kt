package com.privacywarden.app.crypto

import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Verifies Ed25519 signatures on rules pushed by the Tank.
 *
 * The Tank's public key is pinned at build time (BuildConfig.TANK_PUBLIC_KEY_B64),
 * so a man-in-the-middle cannot forge rules even if the WebSocket is intercepted.
 */
object RuleVerifier {
    fun verify(message: ByteArray, signatureB64: String, publicKeyB64: String): Boolean {
        return try {
            val pubBytes = Base64.decode(publicKeyB64, Base64.DEFAULT)
            val sigBytes = Base64.decode(signatureB64, Base64.DEFAULT)
            if (pubBytes.size != 32) {
                Log.w("RuleVerifier", "bad pubkey size: ${pubBytes.size}")
                return false
            }
            if (sigBytes.size != 64) {
                Log.w("RuleVerifier", "bad signature size: ${sigBytes.size}")
                return false
            }
            val signer = Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(pubBytes, 0))
                update(message, 0, message.size)
            }
            signer.verifySignature(sigBytes)
        } catch (t: Throwable) {
            Log.w("RuleVerifier", "verify exception: ${t.message}")
            false
        }
    }
}
