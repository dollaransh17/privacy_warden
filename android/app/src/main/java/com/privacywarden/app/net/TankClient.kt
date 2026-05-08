package com.privacywarden.app.net

import android.util.Log
import com.privacywarden.app.BuildConfig
import com.privacywarden.app.crypto.RuleVerifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.privacywarden.app.vpn.WardenState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistent WebSocket connection to the Tank.
 * Streams flow events up; receives signed rules down.
 */
class TankClient(
    private val deviceId: String,
    private val protect: (Socket) -> Boolean = { true },
    private val onRule: (Rule) -> Unit,
) {
    private val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = Socket().also { protect(it) }
        override fun createSocket(host: String, port: Int): Socket =
            Socket().also { protect(it); it.connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
            Socket().also { protect(it); it.bind(InetSocketAddress(localHost, localPort)); it.connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: java.net.InetAddress, port: Int): Socket =
            Socket().also { protect(it); it.connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): Socket =
            Socket().also { protect(it); it.bind(InetSocketAddress(localAddress, localPort)); it.connect(InetSocketAddress(address, port)) }
    }
    private val client = OkHttpClient.Builder()
        .socketFactory(socketFactory)
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val ws = AtomicReference<WebSocket?>()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true   // emit null fields so the bytes match the Tank's signature
        explicitNulls = true
    }

    fun connect() {
        val url = BuildConfig.TANK_WS_URL + "?device_id=" + deviceId
        val req = Request.Builder().url(url).build()
        client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws.set(webSocket)
                WardenState.tankConnected.set(true)
                Log.i(TAG, "tank connected")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleInbound(text) }
                    .onFailure { Log.w(TAG, "bad inbound: ${it.message}") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "tank dropped: ${t.message}")
                ws.set(null)
                WardenState.tankConnected.set(false)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws.set(null)
                WardenState.tankConnected.set(false)
            }
        })
    }

    fun close() {
        ws.getAndSet(null)?.close(1000, "stopped")
        client.dispatcher.executorService.shutdown()
    }

    fun sendFlow(event: FlowEvent) {
        val sock = ws.get() ?: return
        sock.send(json.encodeToString(FlowEvent.serializer(), event))
        WardenState.flowsSent.incrementAndGet()
    }

    private fun handleInbound(text: String) {
        Log.d(TAG, "inbound (${text.length}b): ${text.take(400)}")
        val msg = json.decodeFromString(InboundMessage.serializer(), text)
        if (msg.type == "rule" && msg.data != null) {
            val sp = msg.data.signed_payload
            val payload = if (sp.isNotEmpty()) sp.toByteArray()
                          else json.encodeToString(Rule.serializer(), msg.data.rule).toByteArray()
            Log.d(TAG, "verify: signed_payload_present=${sp.isNotEmpty()} bytes=${payload.size} preview=${String(payload).take(120)}")
            val ok = RuleVerifier.verify(payload, msg.data.signature, BuildConfig.TANK_PUBLIC_KEY_B64)
            if (!ok) {
                Log.w(TAG, "rule signature INVALID — discarding (sp_len=${sp.length})")
                return
            }
            Log.i(TAG, "rule signature OK — applying ${msg.data.rule.action} ${msg.data.rule.domain}")
            onRule(msg.data.rule)
        }
    }

    companion object { private const val TAG = "TankClient" }
}

@Serializable
data class FlowEvent(
    val device_id: String,
    val app_package: String,
    val app_label: String? = null,
    val sni: String,
    val dst_ip: String? = null,
    val dst_port: Int = 443,
    val bytes_up: Int = 0,
    val bytes_down: Int = 0,
    val ts: String,
)

@Serializable
data class Rule(
    val id: String,
    val device_id: String,
    val app_package: String? = null,
    val domain: String,
    val action: String,
    val expires_at: String? = null,
    val issued_at: String,
)

@Serializable
data class SignedRule(
    val rule: Rule,
    val signature: String,
    val public_key: String,
    val signed_payload: String = "",
)

@Serializable
data class InboundMessage(val type: String, val data: SignedRule? = null)
