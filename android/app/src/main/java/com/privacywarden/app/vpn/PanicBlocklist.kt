package com.privacywarden.app.vpn

/**
 * Curated list of tracker / analytics / advertising / fingerprinting domains.
 *
 * When [WardenState.panicMode] is on, [RuleStore.shouldBlock] returns true for
 * any DNS lookup whose host equals or ends with one of these suffixes — the
 * VPN layer then synthesises an NXDOMAIN, sending the connection to a dead
 * end (the resolver returns "no such domain" so the underlying TCP/UDP
 * connection is never opened). First-party domains of normal apps (WhatsApp,
 * banking apps, Gmail, etc.) are *not* on this list — they keep working.
 *
 * Entries are stored as suffixes (no leading dot). A query for
 * `app-measurement-prod-frc.firebase-settings.googleapis.com` matches
 * `firebase-settings.googleapis.com` via suffix.
 *
 * Sources distilled from public lists (EasyPrivacy, AdGuard Tracking-Protection,
 * Pi-hole defaults, oisd-light) — narrowed to the highest-signal entries that
 * cause the most outbound chatter on a typical Indian user's phone.
 */
object PanicBlocklist {

    val SUFFIXES: Set<String> = setOf(
        // ── Google ad / analytics / measurement ───────────────────────────
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "googletagmanager.com",
        "googletagservices.com",
        "google-analytics.com",
        "analytics.google.com",
        "adservice.google.com",
        "adservice.google.co.in",
        "pagead2.googlesyndication.com",
        "stats.g.doubleclick.net",
        "app-measurement.com",
        "firebase-settings.crashlytics.com",
        "firebaselogging-pa.googleapis.com",
        "firebaseinstallations.googleapis.com",
        "android.clients.google.com",
        "play.googleapis.com",
        "crashlyticsreports-pa.googleapis.com",

        // ── Meta / Facebook ───────────────────────────────────────────────
        "graph.facebook.com",
        "connect.facebook.net",
        "graph.instagram.com",
        "an.facebook.com",
        "edge-mqtt.facebook.com",
        "pixel.facebook.com",

        // ── Microsoft / Bing / LinkedIn ─────────────────────────────────
        "telemetry.microsoft.com",
        "vortex.data.microsoft.com",
        "settings-win.data.microsoft.com",
        "linkedin-aware-loaderbalancer.linkedin.com",

        // ── Apple analytics (only relevant on iOS bridges, harmless on Android) ─
        "iadsdk.apple.com",
        "metrics.icloud.com",

        // ── Amazon ad / metrics ──────────────────────────────────────────
        "amazon-adsystem.com",
        "aax.amazon-adsystem.com",
        "fls-na.amazon.com",
        "aan.amazon.com",
        "ad.amazon.com",

        // ── Mobile ad networks / SDKs ─────────────────────────────────────
        "applovin.com",
        "ironsrc.com",
        "ironsrc.mobi",
        "is.adjust.com",
        "adjust.com",
        "appsflyer.com",
        "appsflyersdk.com",
        "branch.io",
        "api.branch.io",
        "bnc.lt",
        "kochava.com",
        "tenjin.com",
        "singular.net",
        "singular.live",
        "vungle.com",
        "ads.vungle.com",
        "unity3d.com/ads",
        "unityads.unity3d.com",
        "auction.unityads.unity3d.com",
        "config.unityads.unity3d.com",
        "publisher-config.unityads.unity3d.com",
        "stats.unity3d.com",
        "perf-events.cloud.unity3d.com",
        "chartboost.com",
        "live.chartboost.com",
        "ads.mopub.com",
        "mopub.com",
        "inner-active.mobi",
        "inner-active.com",
        "supersonicads.com",
        "tapjoyads.com",
        "tapjoy.com",
        "rfihub.com",
        "rfihub.net",
        "advertising.com",
        "adsrvr.org",
        "adnxs.com",
        "rubiconproject.com",
        "openx.net",
        "criteo.com",
        "criteo.net",
        "smartadserver.com",
        "smartclip.net",
        "pubmatic.com",
        "scorecardresearch.com",
        "comscore.com",
        "moatads.com",
        "moatpixel.com",
        "doubleverify.com",
        "ias.aol.com",
        "integralads.com",
        "adtechus.com",
        "yieldlab.net",

        // ── Mobile measurement / product analytics ────────────────────────
        "mixpanel.com",
        "api.mixpanel.com",
        "amplitude.com",
        "api.amplitude.com",
        "api2.amplitude.com",
        "segment.io",
        "api.segment.io",
        "api.segment.com",
        "segment-apis.com",
        "heap.io",
        "heapanalytics.com",
        "fullstory.com",
        "rs.fullstory.com",
        "logrocket.com",
        "r.logrocket.io",
        "hotjar.com",
        "static.hotjar.com",
        "vc.hotjar.io",
        "smartlook.com",
        "track.customer.io",
        "iterable.com",
        "links.iterable.com",
        "onesignal.com",
        "api.onesignal.com",
        "cdn.onesignal.com",
        "leanplum.com",
        "api.leanplum.com",
        "newrelic.com",
        "bam.nr-data.net",
        "datadoghq.com",
        "browser-intake-datadoghq.com",
        "sentry.io",
        "ingest.sentry.io",
        "bugsnag.com",
        "notify.bugsnag.com",
        "sessions.bugsnag.com",
        "raygun.io",
        "instabug.com",

        // ── Push / engagement vendors ─────────────────────────────────────
        "swrve.com",
        "braze.com",
        "appboy.com",
        "iad.appboy.com",
        "moengage.com",
        "sdk-01.moengage.com",
        "control.kochava.com",

        // ── Indian ad / analytics common on subcontinent apps ─────────────
        "inmobi.com",
        "i.w.inmobi.com",
        "sdkconfig.ssp.inmobi.com",
        "telemetry.flipkart.com",
        "tracker.flipkart.com",
        "fkint.flipkart.com",
        "perf.flipkart.com",
        "events.hotstarext.com",
        "logger.hotstarext.com",
        "track.swiggy.com",
        "metrics.swiggy.com",
        "akm.zomato.com",
        "tracking.zomato.com",
        "events.olacabs.com",
        "log-collector.olacabs.com",
        "ad.paytm.com",
        "ads.paytm.com",
        "track.paytm.com",
        "log-collector.phonepe.com",
        "events.phonepe.com",
        "events.snapdeal.com",
        "tracker.snapdeal.com",

        // ── ByteDance / TikTok ────────────────────────────────────────────
        "log-api.tiktok.com",
        "ads-api.tiktok.com",
        "analytics.tiktok.com",
        "log.byteoversea.com",
        "log-api.musical.ly",
        "tiktokv.com",
        "muscdn.com",

        // ── Yandex / VK ad ────────────────────────────────────────────────
        "mc.yandex.ru",
        "yandexadexchange.net",
        "an.yandex.ru",
        "vk.com/rtrg",

        // ── Common CDN-style trackers ─────────────────────────────────────
        "everesttech.net",
        "everestjs.net",
        "demdex.net",
        "omtrdc.net",
        "2o7.net",
        "adobe.demdex.net",
        "sstats.adobe.com",
        "metric.gstatic.com",

        // ── Crashlytics / fabric (legacy + current) ───────────────────────
        "settings.crashlytics.com",
        "reports.crashlytics.com",
        "fabric.io",
        "e.crashlytics.com",
    )

    /** True if `host` matches any blocklisted suffix (case-insensitive). */
    fun matches(host: String): Boolean {
        val h = host.lowercase()
        for (suf in SUFFIXES) {
            if (h == suf || h.endsWith(".$suf")) return true
        }
        return false
    }
}
