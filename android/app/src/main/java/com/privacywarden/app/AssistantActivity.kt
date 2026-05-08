package com.privacywarden.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import com.privacywarden.app.assistant.AssistantContext
import com.privacywarden.app.assistant.GroqClient
import com.privacywarden.app.assistant.SmallTalk
import com.privacywarden.app.report.AnalysisReportGenerator
import java.io.File
import kotlin.concurrent.thread

/**
 * Chat UI for the on-device AI Assistant.
 *
 * Layout:
 *   ┌──────── top bar: back · "AI Assistant" · info ──────────┐
 *   │  scroll view                                             │
 *   │    welcome bubble                                        │
 *   │    (chips row: "Am I safe?", "Summarize", "Report")      │
 *   │    …message bubbles…                                     │
 *   │    typing indicator (while Groq call is in flight)       │
 *   └─ input row: EditText + send button ─────────────────────┘
 *
 * A single `<<GENERATE_REPORT>>` token anywhere in the assistant's reply
 * triggers PDF generation via [AnalysisReportGenerator], and the PDF is
 * offered to the user via Android's share sheet.
 */
class AssistantActivity : ComponentActivity() {

    // Palette (blue chrome, red reserved for threats) — kept in sync with MainActivity.
    private val BG          = 0xFF08090C.toInt()
    private val CARD        = 0xFF101420.toInt()
    private val CARD_BORDER = 0xFF1C2438.toInt()
    private val SOFT_BADGE  = 0xFF11213D.toInt()
    private val TEXT_HI     = 0xFFF2F5F7.toInt()
    private val TEXT_MID    = 0xFF9AA3B2.toInt()
    private val TEXT_LO     = 0xFF5F6A7D.toInt()
    private val ACCENT      = 0xFFEF4444.toInt()     // red-500 (reserved for threats)
    private val ACCENT_DEEP = 0xFFDC2626.toInt()
    private val BRAND       = 0xFF3B82F6.toInt()     // blue-500 (chrome, user bubble, send button)
    private val BRAND_DEEP  = 0xFF2563EB.toInt()     // blue-600

    private val handler = Handler(Looper.getMainLooper())

    // ── chat state ─────────────────────────────────────────────────────────
    private val messages = mutableListOf<GroqClient.Message>()
    private lateinit var chatHolder: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: ImageButton
    private lateinit var suggestionsRow: HorizontalScrollView
    private var pendingAssistantBubble: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") window.statusBarColor = BG
        @Suppress("DEPRECATION") window.navigationBarColor = CARD
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Seed the conversation with the system prompt. This is kept in
        // messages[] so every turn sees it — Groq enforces system message
        // in slot 0.
        messages.add(GroqClient.Message("system", AssistantContext.SYSTEM_PROMPT))

        setContentView(buildView())
        renderWelcome()
        checkKeyAndWarn()
    }

    // ── layout ─────────────────────────────────────────────────────────────
    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }

        // Top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(16), dp(14))
        }
        val back = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            imageTintList = android.content.res.ColorStateList.valueOf(TEXT_HI)
            val pad = dp(8); setPadding(pad, pad, pad, pad)
            background = roundedRect(CARD, dp(18)).also { it.setStroke(1, CARD_BORDER) }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { finish() }
        }
        top.addView(back)
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            lp.setMargins(dp(12), 0, 0, 0)
            layoutParams = lp
            addView(TextView(this@AssistantActivity).apply {
                text = "AI Assistant"
                setTextColor(TEXT_HI)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = -0.01f
            })
            addView(TextView(this@AssistantActivity).apply {
                text = "On-device privacy expert · powered by Groq"
                setTextColor(TEXT_MID)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        })
        val statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (GroqClient.hasKey()) 0xFF10B981.toInt() else 0xFFDC2626.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9))
        }
        top.addView(statusDot)
        root.addView(top)

        // Suggestion chips row (above the chat so it's always reachable)
        suggestionsRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        val chipsLp = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (s in listOf(
            "Am I safe right now?",
            "Summarize today",
            "Explain Radio time",
            "Generate full report PDF",
            "Which app is riskiest?",
        )) {
            chipsLp.addView(buildChip(s))
        }
        suggestionsRow.addView(chipsLp)
        root.addView(suggestionsRow)

        // Chat scroll area
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isVerticalScrollBarEnabled = false
        }
        chatHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(chatHolder)
        root.addView(scrollView)

        // Input row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(14))
            background = GradientDrawable().apply {
                setColor(CARD)
                setStroke(1, CARD_BORDER)
            }
        }
        input = EditText(this).apply {
            hint = "Ask anything about your privacy…"
            setHintTextColor(TEXT_LO)
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = roundedRect(SOFT_BADGE, dp(24)).also { it.setStroke(1, CARD_BORDER) }
            setPadding(dp(18), dp(12), dp(18), dp(12))
            maxLines = 4
            val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            layoutParams = lp
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { onSendClicked(); true } else false
            }
        }
        inputRow.addView(input)
        sendBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            background = roundedRect(BRAND_DEEP, dp(22))
            val lp = LinearLayout.LayoutParams(dp(44), dp(44))
            lp.marginStart = dp(8)
            layoutParams = lp
            setOnClickListener { onSendClicked() }
        }
        inputRow.addView(sendBtn)
        root.addView(inputRow)

        return root
    }

    private fun buildChip(text: String): View {
        val chip = TextView(this).apply {
            this.text = text
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedRect(CARD, dp(16)).also { it.setStroke(1, CARD_BORDER) }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            lp.marginEnd = dp(8)
            layoutParams = lp
            setOnClickListener {
                input.setText(text)
                input.setSelection(text.length)
                onSendClicked()
            }
        }
        return chip
    }

    // ── message rendering ──────────────────────────────────────────────────
    private fun renderWelcome() {
        addAssistantBubble(
            "Hi! I'm your Privacy Warden Assistant.\n\n" +
            "I can see your live privacy data — trackers blocked, flagged emails, risky " +
            "apps, mic/camera activity — all staying on this device. Ask me anything, or " +
            "say \"generate a report\" to get a PDF you can share."
        )
    }

    private fun addUserBubble(text: String) {
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = roundedRect(BRAND_DEEP, dp(18))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(dp(40), dp(6), 0, dp(6))
            layoutParams = lp
            addView(bubble)
        }
        chatHolder.addView(row)
        scrollToBottom()
    }

    private fun addAssistantBubble(text: String): TextView {
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(TEXT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = roundedRect(CARD, dp(18)).also { it.setStroke(1, CARD_BORDER) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setLineSpacing(0f, 1.25f)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(0, dp(6), dp(40), dp(6))
            layoutParams = lp
            addView(bubble)
        }
        chatHolder.addView(row)
        scrollToBottom()
        return bubble
    }

    private fun addTypingIndicator(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(0, dp(6), dp(40), dp(6))
            layoutParams = lp
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedRect(CARD, dp(18)).also { it.setStroke(1, CARD_BORDER) }
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        inner.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(BRAND)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        inner.addView(TextView(this).apply {
            text = "  Thinking…"
            setTextColor(TEXT_MID)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        row.addView(inner)
        chatHolder.addView(row)
        scrollToBottom()
        return row
    }

    private fun scrollToBottom() {
        handler.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    // ── send logic ─────────────────────────────────────────────────────────
    private fun onSendClicked() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        addUserBubble(text)

        // Fast-path: trivial small talk ("hi", "thanks", "who are you", …)
        // is handled instantly on-device, with no network call. This keeps
        // greetings snappy and stops the model from being primed by 2 KB of
        // privacy data when the user just wanted to say hello.
        SmallTalk.replyOrNull(text)?.let { quickReply ->
            addAssistantBubble(quickReply)
            // Keep the conversation history coherent so follow-ups still flow
            // naturally to the LLM.
            messages.add(GroqClient.Message("user", text))
            messages.add(GroqClient.Message("assistant", quickReply))
            return
        }

        if (!GroqClient.hasKey()) {
            addAssistantBubble(
                "I can't reach Groq right now — no API key is configured.\n\n" +
                "Add your key to local.properties as GROQ_API_KEY=gsk_… and rebuild."
            )
            return
        }

        // Add grounding context + user turn. Put the user's actual question
        // FIRST so the model focuses on it; the live snapshot follows as
        // reference material it can pull from when (and only when) the
        // question is about their privacy data.
        val snapshot = AssistantContext.buildSnapshot()
        val userWithCtx = buildString {
            append("USER MESSAGE: ").append(text).append("\n\n")
            append("(For grounding only — don't recite this unless the user actually asks about their privacy state.)\n")
            append(snapshot)
        }
        messages.add(GroqClient.Message("user", userWithCtx))

        sendBtn.isEnabled = false
        val typing = addTypingIndicator()

        thread(name = "groq-chat") {
            val result = GroqClient.chat(messages)
            handler.post {
                chatHolder.removeView(typing)
                sendBtn.isEnabled = true
                if (!result.ok) {
                    addAssistantBubble("Sorry, I hit an error:\n${result.error}")
                    // Rollback the user message so the history stays clean
                    messages.removeAt(messages.lastIndex)
                    return@post
                }

                val reply = result.content
                // Strip the action token before displaying so the user doesn't
                // see the machine marker in their chat.
                val (visibleReply, shouldReport) = extractReportIntent(reply)
                addAssistantBubble(visibleReply.ifBlank { "(no response)" })
                messages.add(GroqClient.Message("assistant", reply))

                if (shouldReport) {
                    generateAndShareReport()
                }
            }
        }
    }

    private fun extractReportIntent(reply: String): Pair<String, Boolean> {
        val token = "<<GENERATE_REPORT>>"
        return if (token in reply) {
            reply.replace(token, "").trim() to true
        } else {
            reply to false
        }
    }

    // ── PDF generation ─────────────────────────────────────────────────────
    private fun generateAndShareReport() {
        val banner = addAssistantBubble("Generating your PDF report…")
        thread(name = "pdf-gen") {
            var outFile: File? = null
            var err: String? = null
            try {
                outFile = AnalysisReportGenerator.generate(this)
            } catch (t: Throwable) {
                err = t.message ?: t::class.java.simpleName
            }
            val finalFile = outFile
            val finalErr = err
            handler.post {
                if (finalFile != null && finalFile.exists()) {
                    banner.text = "Report saved: ${finalFile.name}\nOpening share sheet…"
                    sharePdf(finalFile)
                } else {
                    banner.text = "Could not generate report: ${finalErr ?: "unknown error"}"
                }
            }
        }
    }

    private fun sharePdf(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Privacy Warden Report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share Privacy Warden report"))
    }

    // ── helpers ────────────────────────────────────────────────────────────
    private fun checkKeyAndWarn() {
        if (!GroqClient.hasKey()) {
            Toast.makeText(
                this,
                "Add GROQ_API_KEY to local.properties and rebuild to enable chat.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }

    private fun roundedRect(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }
    }
}
