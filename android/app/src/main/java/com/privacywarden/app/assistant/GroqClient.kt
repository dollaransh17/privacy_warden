package com.privacywarden.app.assistant

import com.privacywarden.app.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around Groq's OpenAI-compatible chat-completions endpoint.
 *
 * The API key is injected at build time from `local.properties` via
 * [BuildConfig.GROQ_API_KEY] so it never appears in source or version
 * control. If the key is missing (empty string) the client short-circuits
 * with a friendly error message instead of a 401.
 *
 * This client is intentionally stateless — the caller manages the
 * conversation history and passes the full message list on every turn.
 * That keeps the AssistantActivity simple and makes the client trivially
 * reusable for other one-shot queries (e.g. intent detection).
 */
object GroqClient {

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val DEFAULT_MODEL = "llama-3.3-70b-versatile"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Message(val role: String, val content: String)

    data class ChatResult(
        val ok: Boolean,
        val content: String,
        val error: String? = null,
    )

    fun hasKey(): Boolean =
        BuildConfig.GROQ_API_KEY.isNotBlank() &&
        BuildConfig.GROQ_API_KEY != "PASTE_NEW_GROQ_KEY_HERE"

    /**
     * Send a multi-turn chat. Must be called off the main thread.
     *
     * @param messages ordered conversation history including the new user turn
     * @param model    Groq model name. Defaults to Llama 3.3 70B (fast + smart)
     * @param maxTokens hard cap on assistant reply length
     * @param temperature 0.0–1.0, lower = more deterministic
     */
    fun chat(
        messages: List<Message>,
        model: String = DEFAULT_MODEL,
        maxTokens: Int = 1024,
        temperature: Double = 0.4,
    ): ChatResult {
        if (!hasKey()) {
            return ChatResult(
                ok = false,
                content = "",
                error = "No Groq key configured. Add GROQ_API_KEY to " +
                    "local.properties and rebuild the app.",
            )
        }
        val payload = buildString {
            append('{')
            append("\"model\":\"").append(model).append("\",")
            append("\"temperature\":").append(temperature).append(',')
            append("\"max_tokens\":").append(maxTokens).append(',')
            append("\"messages\":[")
            for ((i, m) in messages.withIndex()) {
                if (i > 0) append(',')
                append('{')
                    .append("\"role\":\"").append(escape(m.role)).append("\",")
                    .append("\"content\":\"").append(escape(m.content)).append("\"")
                    .append('}')
            }
            append(']')
            append('}')
        }
        val body = payload.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    ChatResult(false, "", "Groq HTTP ${resp.code}: ${raw.take(300)}")
                } else {
                    val text = parseContent(raw)
                    if (text.isNullOrBlank()) {
                        ChatResult(false, "", "Empty response from Groq.")
                    } else {
                        ChatResult(true, text.trim())
                    }
                }
            }
        } catch (t: Throwable) {
            ChatResult(false, "", "Network error: ${t.message ?: t::class.java.simpleName}")
        }
    }

    /**
     * Extract choices[0].message.content from the OpenAI-shaped response.
     * Tolerant of extra fields — we use kotlinx.serialization's dynamic tree.
     */
    private fun parseContent(raw: String): String? = try {
        val root = json.parseToJsonElement(raw).jsonObject
        val choices = root["choices"]?.jsonArray ?: return null
        val first = choices.firstOrNull()?.jsonObject ?: return null
        first["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
    } catch (_: Throwable) {
        null
    }

    /** JSON-escape a string literal safely. */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"'  -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
