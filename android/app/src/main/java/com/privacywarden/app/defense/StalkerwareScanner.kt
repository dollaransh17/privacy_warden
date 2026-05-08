package com.privacywarden.app.defense

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.privacywarden.app.vpn.WardenState

/**
 * Real on-device stalkerware detector.
 *
 * Scans every installed app and scores it against three signal classes that
 * commercial spyware (mSpy, FlexiSPY, Cocospy, Hoverwatch, Cerberus, etc.)
 * historically share:
 *
 *   1. Surveillance permissions — a *cluster* of high-risk perms that
 *      legitimate apps almost never request together
 *      (READ_SMS + ACCESS_FINE_LOCATION + RECORD_AUDIO + READ_CONTACTS + ...)
 *
 *   2. Stealth — no launcher icon, hidden label, or auto-start at boot
 *
 *   3. Naming / signature flags — package or app name matching known
 *      stalkerware fingerprints (case-insensitive)
 *
 * Apps are scored 0–100; >= 60 = flagged. Bundled with the app, no network call.
 */
object StalkerwareScanner {

    /** Permission combinations that scream "watch the user". */
    private val surveillancePerms = setOf(
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.READ_CONTACTS",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
    )

    /** Signature fragments (lowercase) drawn from known stalkerware lineage. */
    private val knownFingerprints = listOf(
        "mspy", "flexispy", "cocospy", "hoverwatch", "spyera", "spyzie",
        "spybubble", "thetruthspy", "highstermobile", "spyhuman",
        "cerberusapp", "kidlogger", "imobispy", "phonelogspy", "trackmyfone",
        "mobilespy", "easylogger", "totalspy", "couplevow", "stealthgenie",
        "xnspy", "spyhide", "webwatcher", "trackview", "copy9",
    )

    /** Package-name prefixes for OS, OEM and trusted vendors — never flagged. */
    private val safePackagePrefixes = listOf(
        "com.google.", "com.android.", "android",
        "com.samsung.", "com.sec.", "com.samsung",
        "com.qualcomm.", "com.qti.", "qualcomm.",
        "com.miui.", "com.xiaomi.", "com.mi.",
        "com.oneplus.", "net.oneplus.",
        "com.oppo.", "com.coloros.", "com.heytap.",
        "com.vivo.", "com.bbk.", "com.iqoo.",
        "com.realme.",
        "com.huawei.", "com.hihonor.", "com.honor.", "com.emui.",
        "com.motorola.", "com.lenovo.",
        "com.lge.",
        "com.sonyericsson.", "com.sonymobile.",
        "com.htc.", "com.asus.", "com.nothing.",
        "com.transsion.", "com.tcl.",
        "com.dolby.", "com.qualcomm",
    )

    /** Specific apps known to be legitimate — prefixes / exact matches. */
    private val safeKnownApps = listOf(
        // Indian carriers / utilities (often pre-installed)
        "com.airtel.", "com.myairtel", "com.jio.", "com.reliance.jio.",
        "com.idea.", "com.vodafone.", "com.bsnl.",
        // Truecaller (controversial but legitimate identification app)
        "com.truecaller",
        // Major messengers / social
        "com.whatsapp", "com.whatsapp.w4b",
        "com.facebook.", "com.instagram.",
        "org.telegram.", "org.thoughtcrime.securesms",
        "com.discord", "com.snapchat.android",
        "com.zhiliaoapp.musically", "com.ss.android.",
        "com.linkedin.", "com.twitter.", "com.x.android",
        // India payment / banking (need lots of perms legitimately)
        "com.paytm", "net.one97.paytm", "com.phonepe.",
        "com.csam.icici.", "com.icicibank.", "com.sbi.", "com.onlinesbi.",
        "com.axis.", "com.axisbank.", "com.hdfcbank.", "com.kotak.",
        "com.bankofbaroda.", "com.idfcbank.", "com.yesbank.",
        "in.org.npci.", "com.dreamplug.", "com.cred.", "com.bharatpe.",
        "com.razorpay.", "com.amazon.mShop", "com.amazon.",
        // Indian e-commerce / mobility
        "com.flipkart.", "com.myntra", "in.swiggy.", "com.application.zomato",
        "com.olacabs.", "com.ubercab", "com.rapido.",
        "in.startv.hotstar", "com.tv.v18.viola", "com.tencent.",
        "com.spotify.", "com.netflix.mediaclient", "com.amazon.avod.",
        // Browsers
        "org.mozilla.", "com.brave.browser", "com.opera.",
        "com.duckduckgo.mobile.android", "com.microsoft.emmx",
        // Productivity
        "com.microsoft.", "com.dropbox.", "com.evernote", "com.notion.",
        "com.zoho.", "us.zoom.videomeetings", "com.cisco.webex.",
        "com.skype.",
        // Mainstream AVs / security — they legitimately ask for many perms
        "com.avast.android.", "com.avg.", "com.symantec.",
        "com.norton.", "com.kaspersky.", "com.bitdefender.",
        "com.malwarebytes.", "com.eset.", "com.lookout",
        "com.mcafee.", "com.trendmicro.", "com.sophos.",
        // Common Indian system / launcher pre-installs
        "com.sec.android.", "com.smarttouchcall", "com.continuity",
        "com.heytap.themestore",
    )

    /** Trusted installer packages — if app came from these, skip flagging. */
    private val trustedInstallers = setOf(
        "com.android.vending",                 // Google Play Store
        "com.google.android.feedback",         // Play system updates
        "com.samsung.android.samsungapps",     // Galaxy Store
        "com.amazon.venezia",                  // Amazon Appstore
        "com.huawei.appmarket",                // AppGallery
        "com.xiaomi.market", "com.mi.global.shop",
    )

    data class Finding(
        val pkg: String,
        val label: String,
        val score: Int,
        val reasons: List<String>,
        val hidden: Boolean,
    )

    /** Run a full scan. Cheap enough for the UI thread on most phones, but the
     *  caller is encouraged to dispatch to IO. */
    fun scan(ctx: Context): List<Finding> {
        val pm = ctx.packageManager
        val findings = mutableListOf<Finding>()
        val installed: List<PackageInfo> = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (_: Throwable) {
            return emptyList()
        }
        for (pi in installed) {
            val ai = pi.applicationInfo ?: continue
            val pkg = pi.packageName
            if (pkg == ctx.packageName) continue

            val pkgLower = pkg.lowercase()
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
            val labelLower = label.lowercase()

            // ── Allowlist ─────────────────────────────────────────────────
            // Skip OS / OEM / well-known legitimate apps regardless of perms.
            if (isSafePackage(pkgLower)) continue
            // Skip pure-OS pre-installs that haven't been updated (no risk).
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdated = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdated) continue

            // ── Signals ───────────────────────────────────────────────────
            val reasons = mutableListOf<String>()
            var score = 0

            // 1) Definitive: known stalkerware fingerprint → instant flag.
            val fpHit = knownFingerprints.firstOrNull { it in pkgLower || it in labelLower }
            if (fpHit != null) {
                score += 90
                reasons += "Matches known stalkerware fingerprint: $fpHit"
            }

            // 2) Surveillance-permission cluster.
            val perms = pi.requestedPermissions?.toSet() ?: emptySet()
            val matched = perms.intersect(surveillancePerms)
            if (matched.size >= 6) {
                score += 30; reasons += "Requests ${matched.size} high-risk permissions"
            } else if (matched.size == 5) {
                score += 18; reasons += "Requests 5 high-risk permissions"
            } else if (matched.size == 4) {
                score += 8
            }

            // 3) Hidden from launcher — only meaningful if NOT a system app
            //    AND the app already shows a permission cluster.
            val launchIntent = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
            val hidden = launchIntent == null
            if (hidden && !isSystem && matched.size >= 3) {
                score += 22; reasons += "No launcher icon (hidden) + tracking permissions"
            }

            // 4) Side-loaded (not installed via a trusted store).
            val installer = runCatching { pm.getInstallerPackageName(pkg) }.getOrNull()
            val sideloaded = installer == null || installer !in trustedInstallers
            if (sideloaded && !isSystem && matched.size >= 4) {
                score += 18; reasons += "Side-loaded (not from a trusted store)"
            }

            // 5) Accessibility binding combined with sideload + cluster.
            if ("android.permission.BIND_ACCESSIBILITY_SERVICE" in perms &&
                sideloaded && matched.size >= 3
            ) {
                score += 10; reasons += "Binds the Accessibility Service"
            }

            // Threshold raised to 70 to kill noise. Fingerprint hits start at 90
            // so they always pass; everything else needs multiple corroborating signals.
            if (score >= 70) {
                findings += Finding(
                    pkg = pkg,
                    label = label,
                    score = score.coerceAtMost(100),
                    reasons = reasons,
                    hidden = hidden,
                )
            }
        }

        findings.sortByDescending { it.score }
        return findings
    }

    private fun isSafePackage(pkgLower: String): Boolean {
        if (safePackagePrefixes.any { pkgLower.startsWith(it) }) return true
        if (safeKnownApps.any { pkgLower == it || pkgLower.startsWith(it) }) return true
        return false
    }

    /** Run a scan and update WardenState pillar counters / timeline. */
    fun scanAndPublish(ctx: Context): List<Finding> {
        val results = scan(ctx)
        if (results.isNotEmpty()) {
            val top = results.first()
            WardenState.appsBlocked.addAndGet(results.size)
            WardenState.appsLast.set("Flagged: ${top.label} · score ${top.score}")
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.APPS,
                    title = "Stalkerware-pattern app detected",
                    detail = "${top.label} (${top.pkg}) · risk ${top.score}/100",
                )
            )
        } else {
            WardenState.appsLast.set("No suspicious apps")
            WardenState.pushEvent(
                WardenState.TimelineEvent(
                    pillar = WardenState.Pillar.APPS,
                    title = "Scan clean",
                    detail = "No stalkerware patterns found across installed apps",
                )
            )
        }
        return results
    }
}
