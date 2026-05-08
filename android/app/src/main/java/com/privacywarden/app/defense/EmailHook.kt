package com.privacywarden.app.defense

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.privacywarden.app.vpn.WardenState

/**
 * Email phishing hook.
 *
 * Android does *not* expose a privileged mail-inbox content provider — the
 * Gmail app only allows third-party access via OAuth + Gmail API or via the
 * "Share to" intent. Two integration modes are exposed here:
 *
 *   1. `analyzeShared(text)` — when the user uses Gmail's "Share" / "Share via"
 *      menu and selects Privacy Warden, we run the full message through
 *      `PhishingDetector` and surface a verdict. This is the real, on-device
 *      path; configured by the SHARE intent filter on MainActivity.
 *
 *   2. `openGmail()` — convenience to deep-link to Gmail so the user can
 *      sweep their own inbox today; the OAuth-based bulk scanner is a
 *      planned next step (requires Google API console work + per-user OAuth).
 */
object EmailHook {

    /** Returns Google accounts known to this device. Requires GET_ACCOUNTS perm
     *  on API < 26 or the user's prior runtime grant; on API >= 26 returns empty
     *  if denied. */
    fun listGoogleAccounts(ctx: Context): List<String> = try {
        AccountManager.get(ctx).getAccountsByType("com.google")
            .map { it.name }.distinct().sorted()
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    fun openGmail(ctx: Context) {
        val pkg = "com.google.android.gm"
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    fun analyzeShared(text: String): PhishingDetector.Verdict {
        val v = PhishingDetector.analyze(text)
        if (v.isPhishing) {
            WardenState.commsBlocked.incrementAndGet()
            WardenState.commsLast.set("Email phishing · score ${v.score}")
            WardenState.bumpBreakdown(WardenState.Pillar.COMMS, "Email")
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.COMMS,
                    title = "Phishing email blocked",
                    detail = (v.reasons.firstOrNull() ?: "Suspicious content") +
                        (if (v.matchedUrls.isNotEmpty()) " · ${v.matchedUrls.first()}" else ""),
                )
            )
        }
        return v
    }
}
