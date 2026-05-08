package com.privacywarden.app.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import android.util.Log
import java.net.Inet4Address
import java.net.InetSocketAddress

/**
 * Resolves outgoing TCP/UDP packets to the app that owns the connection,
 * using ConnectivityManager.getConnectionOwnerUid (API 29+).
 *
 * Caches uid → (package, label) so PackageManager isn't hit per-packet.
 */
class AppResolver(private val ctx: Context) {

    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val pm: PackageManager = ctx.packageManager
    private val uidCache = HashMap<Int, Pair<String, String>>()  // uid → (pkg, label)
    private val ownUid = Process.myUid()

    /** Look up app for an outgoing IPv4 packet. Returns null if unknown or our own VPN. */
    fun resolve(buf: ByteArray, n: Int): App? {
        if (n < 28) return null
        val ipHdrLen = (buf[0].toInt() and 0x0f) * 4
        if (ipHdrLen < 20 || ipHdrLen + 4 > n) return null
        val proto = buf[9].toInt() and 0xff
        if (proto != 6 && proto != 17) return null  // not TCP/UDP

        val srcIp = ipv4(buf, 12)
        val dstIp = ipv4(buf, 16)
        val srcPort = u16(buf, ipHdrLen)
        val dstPort = u16(buf, ipHdrLen + 2)

        val local = InetSocketAddress(srcIp, srcPort)
        val remote = InetSocketAddress(dstIp, dstPort)

        val uid = try {
            cm.getConnectionOwnerUid(proto, local, remote)
        } catch (t: Throwable) {
            return null
        }
        if (uid <= 0) return null  // -1 = unknown
        if (uid == ownUid) return null  // skip our own warden traffic

        val cached = uidCache[uid]
        if (cached != null) return App(uid, cached.first, cached.second)

        val packages = pm.getPackagesForUid(uid) ?: return null
        if (packages.isEmpty()) return null
        val pkg = packages[0]
        val label = try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Throwable) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
        uidCache[uid] = pkg to label
        Log.d(TAG, "resolved uid=$uid → $pkg ($label)")
        return App(uid, pkg, label)
    }

    private fun ipv4(buf: ByteArray, off: Int): Inet4Address {
        val b = byteArrayOf(buf[off], buf[off + 1], buf[off + 2], buf[off + 3])
        return Inet4Address.getByAddress(b) as Inet4Address
    }

    private fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xff) shl 8) or (buf[off + 1].toInt() and 0xff)

    data class App(val uid: Int, val pkg: String, val label: String)

    companion object { private const val TAG = "AppResolver" }
}
