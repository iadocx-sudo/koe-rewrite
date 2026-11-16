package jp.himmel.koerewrite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Flow: speech -> Android/Google speech recognizer (same engine family as Gboard voice typing,
 * fast & accurate) -> transcript + screen text -> Gemini (text only, thinking off) -> insert.
 */
class RewriteIME : InputMethodService() {

    private enum class State { IDLE, LISTENING, PROCESSING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var state = State.IDLE
    private var jobId = 0

    private var recognizer: SpeechRecognizer? = null
    private val committed = StringBuilder()
    private var partial = ""
    private var stopRequested = false
    private var listenStart = 0L
    private var earlyRetries = 0

    private lateinit var status: TextView
    private lateinit var micBtn: TextView
    private lateinit var info: TextView
    private lateinit var transcriptView: TextView
    private lateinit var shotBtn: TextView

    private fun shotLabel() = if (prefs.aiPolish) "AI清書ON" else "AI清書OFF"

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun pill(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#3A2A28"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#E8D6D2"))
            }
            setOnClickListener { onClick() }
        }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FDF1EE"))
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#5A3A35"))
            gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        transcriptView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#2A1A18"))
            gravity = Gravity.CENTER
            maxLines = 4
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        root.addView(transcriptView, LinearLayout.LayoutParams(-1, -2))

        micBtn = TextView(this).apply {
            textSize = 34f
            gravity = Gravity.CENTER
            setOnClickListener { onMic() }
        }
        val micLp = LinearLayout.LayoutParams(dp(96), dp(96)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6); bottomMargin = dp(4)
        }
        root.addView(micBtn, micLp)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val lp = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(6), dp(4), 0) }
        row.addView(pill("⌨戻る") { cancel(); goBack() }, lp)
        row.addView(pill("✕取消") { cancel() }, LinearLayout.LayoutParams(lp))
        shotBtn = pill(shotLabel()) {
            prefs.aiPolish = !prefs.aiPolish
            shotBtn.text = shotLabel()
        }
        row.addView(shotBtn, LinearLayout.LayoutParams(lp))
        row.addView(pill("⚙") { openSettings() }, LinearLayout.LayoutParams(lp))
        root.addView(row, LinearLayout.LayoutParams(-1, -2))

        info = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#9A7A75"))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(info, LinearLayout.LayoutParams(-1, -2))

        render()
        return root
    }

    override fun onStartInputView(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        if (!::status.isInitialized) return
        shotBtn.text = shotLabel()
        render()
        if (!restarting && state == State.IDLE && prefs.autoStart && ready()) {
            // give the mic / recognizer a moment after the keyboard switch
            status.text = "準備中…"
            main.postDelayed({ if (state == State.IDLE && isInputViewShown) startListening() }, 450)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (state != State.IDLE) cancel()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        destroyRecognizer()
        super.onDestroy()
    }

    // ---- UI ----

    private fun render() {
        val (color, label) = when (state) {
            State.IDLE -> "#FA8072" to "🎤"
            State.LISTENING -> "#E53935" to "■"
            State.PROCESSING -> "#BDBDBD" to "…"
        }
        micBtn.text = label
        micBtn.setTextColor(Color.WHITE)
        micBtn.typeface = Typeface.DEFAULT_BOLD
        micBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(color))
        }
        when (state) {
            State.IDLE -> status.text = when {
                prefs.apiKey.isBlank() -> "⚙ 設定でGemini APIキーを登録してください"
                !hasMic() -> "⚙ 設定でマイクを許可してください"
                else -> "🎤をタップして話す（間が空いても■を押すまで続きます）"
            }
            State.LISTENING -> status.text = "● 聞き取り中（間が空いても続きます）　終わったら■"
            State.PROCESSING -> {}
        }
        info.text = "話した言葉はすぐ入力 → 数秒後にAIが清書　／　最後に「エンター」で送信"
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun ready() = prefs.apiKey.isNotBlank() && hasMic()

    private fun onMic() {
        when (state) {
            State.IDLE -> {
                if (!ready()) { openSettings(); return }
                startListening()
            }
            State.LISTENING -> requestStop()
            State.PROCESSING -> {}
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun goBack() {
        val ok = try { switchToPreviousInputMethod() } catch (e: Exception) { false }
        if (!ok) try { switchToNextInputMethod(false) } catch (_: Exception) {}
    }

    // ---- continuous dictation: listens until ■, inserts each phrase at once, polishes it in the background ----

    private val rawQueue = ArrayList<String>()
    private var busy = false
    private var sessionCtx = ""
    private var sessionId = 0
    private var errStreak = 0
    private var lastSpeech = 0L

    private fun recIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        try { r.cancel() } catch (_: Exception) {}
        try { r.destroy() } catch (_: Exception) {}
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "音声認識サービスがありません（Googleアプリを入れて既定の音声認識に設定してください）"
            return
        }
        Gemini.prewarm(prefs.apiKey)
        autoSend = false
        partial = ""
        stopRequested = false
        errStreak = 0
        rawQueue.clear()
        sessionId++
        lastSpeech = SystemClock.elapsedRealtime()
        // screen context is captured once per session
        val screen = if (prefs.useScreen) try { ScreenTextService.instance?.captureText(2000) } catch (e: Exception) { null } else null
        sessionCtx = if (screen.isNullOrBlank()) "" else "【画面の会話（参考）】\n$screen\n\n"
        transcriptView.text = ""
        state = State.LISTENING
        render()
        listenOnce()
    }

    private fun relisten(delay: Long) {
        val sid = sessionId
        main.postDelayed({ if (state == State.LISTENING && !stopRequested && sid == sessionId) listenOnce() }, delay)
    }

    private fun listenOnce() {
        destroyRecognizer()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (state != State.LISTENING || recognizer !== r) return
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (p.isNotBlank()) {
                    partial = p
                    lastSpeech = SystemClock.elapsedRealtime()
                    errStreak = 0
                    transcriptView.text = p
                }
            }

            override fun onResults(results: Bundle?) {
                if (state != State.LISTENING || recognizer !== r) return
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                partial = ""
                transcriptView.text = ""
                errStreak = 0
                if (best.isNotBlank()) {
                    lastSpeech = SystemClock.elapsedRealtime()
                    handleSegment(best)
                }
                if (stopRequested) afterStop() else relisten(0)
            }

            override fun onError(error: Int) {
                if (state != State.LISTENING || recognizer !== r) return
                if (stopRequested) { flushPartial(); afterStop(); return }
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    stopWithMessage("マイクの許可がありません（⚙設定で許可してください）")
                    return
                }
                val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                // a phrase that was heard but not finalized must not be lost
                flushPartial()
                if (!silence) errStreak++
                val idle = SystemClock.elapsedRealtime() - lastSpeech
                when {
                    errStreak > 8 -> stopWithMessage("音声認識エラー($error)が続いたため停止しました")
                    idle > 90000 -> { stopRequested = true; afterStop() }   // 90s without speech
                    else -> relisten(if (silence) 100L else minOf(1500L, 300L * errStreak))
                }
            }
        })
        try {
            r.startListening(recIntent())
        } catch (e: Exception) {
            errStreak++
            if (errStreak > 8) stopWithMessage("音声認識を開始できません: ${e.message}") else relisten(500)
        }
    }

    private fun flushPartial() {
        val p = partial.trim()
        partial = ""
        transcriptView.text = ""
        if (p.isNotEmpty()) handleSegment(p)
    }

    /** One recognized phrase: insert it immediately, then polish it in the background. */
    private fun handleSegment(text: String) {
        var t = text.trim()
        if (prefs.voiceEnter) {
            val m = ENTER_RE.find(t)
            if (m != null) {
                autoSend = true
                stopRequested = true
                t = t.substring(0, m.range.first).trim()
            }
        }
        if (t.isEmpty()) return
        val ic = currentInputConnection ?: return
        ic.commitText(t, 1)
        if (prefs.aiPolish && prefs.apiKey.isNotBlank()) {
            rawQueue.add(t)
            pump()
        }
    }

    /** Sends everything waiting as one request (phrases spoken while a request was running are merged). */
    private fun pump() {
        if (busy || rawQueue.isEmpty()) return
        val raw = rawQueue.joinToString("")
        rawQueue.clear()
        busy = true
        val sid = sessionId
        val before = currentInputConnection?.getTextBeforeCursor(3000, 0)?.toString() ?: ""
        val idx = before.lastIndexOf(raw)
        val existing = if (idx > 0) before.substring(0, idx) else ""
        val ctx = buildString {
            append(sessionCtx)
            if (existing.isNotBlank()) append("【直前までの文章】\n").append(existing.takeLast(1500)).append("\n\n")
            append("【今回の発話】\n").append(raw)
        }
        val apiKey = prefs.apiKey
        val model = prefs.model
        val extra = prefs.extraRule
        Thread {
            val result = try {
                Result.success(Gemini.generateFast(apiKey, model, ctx, extra, Gemini.DICTATION_PROMPT, deadlineMs = 8000))
            } catch (e: Throwable) { Result.failure(e) }
            main.post {
                busy = false
                if (sid == sessionId) {
                    result.onSuccess { prefs.lastError = ""; replaceRaw(raw, it) }
                    result.onFailure { prefs.lastError = it.message ?: it.javaClass.simpleName }
                    pump()
                    maybeFinish()
                }
            }
        }.start()
    }

    /** Swap the raw phrase for the polished one, keeping anything typed/spoken after it. */
    private fun replaceRaw(raw: String, out: String) {
        if (out == raw || out.isBlank()) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(4000, 0)?.toString() ?: return
        val idx = before.lastIndexOf(raw)
        if (idx < 0) return
        val tail = before.substring(idx + raw.length)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(raw.length + tail.length, 0)
        ic.commitText(out + tail, 1)
        ic.endBatchEdit()
    }

    private fun requestStop() {
        if (state != State.LISTENING) return
        stopRequested = true
        status.text = "確定中…"
        try { recognizer?.stopListening() } catch (_: Exception) {}
        // safety: if the recognizer never calls back, finish with what was heard
        val sid = sessionId
        main.postDelayed({
            if (state == State.LISTENING && sid == sessionId) { flushPartial(); afterStop() }
        }, 1800)
    }

    private fun afterStop() {
        if (state != State.LISTENING) return
        destroyRecognizer()
        state = State.PROCESSING
        render()
        status.text = "仕上げ中…"
        maybeFinish()
    }

    private fun maybeFinish() {
        if (state != State.PROCESSING || busy || rawQueue.isNotEmpty()) return
        state = State.IDLE
        render()
        status.text = "✓ 完了"
        val sending = autoSend
        if (sending) main.postDelayed({ doSend(0) }, 300)
        if (prefs.autoReturn) {
            main.postDelayed({ if (state == State.IDLE) goBack() }, if (sending) 1500L else 400L)
        }
    }

    private fun stopWithMessage(msg: String) {
        sessionId++
        destroyRecognizer()
        rawQueue.clear()
        state = State.IDLE
        render()
        status.text = msg
    }

    private fun cancel() {
        sessionId++
        destroyRecognizer()
        rawQueue.clear()
        partial = ""
        state = State.IDLE
        if (::status.isInitialized) { render(); transcriptView.text = "" }
    }

    private fun currentFieldText(): String {
        val ic = currentInputConnection ?: return ""
        val ex = try { ic.getExtractedText(ExtractedTextRequest(), 0) } catch (e: Exception) { null }
        ex?.text?.let { return it.toString() }
        val before = ic.getTextBeforeCursor(5000, 0) ?: ""
        val after = ic.getTextAfterCursor(5000, 0) ?: ""
        return before.toString() + after.toString()
    }

    private var autoSend = false
    private val ENTER_RE = Regex("[\\s、。,.]*(エンター|えんたー|エンタ|enter)[\\s、。,.!！]*$", RegexOption.IGNORE_CASE)

    /** Press the app's send button (via the accessibility service); fall back to the Enter action. */
    private fun doSend(attempt: Int) {
        autoSend = false
        val clicked = try { ScreenTextService.instance?.clickSend() == true } catch (e: Throwable) { false }
        if (clicked) return
        if (attempt == 0 && ScreenTextService.instance != null) {
            main.postDelayed({ doSend(1) }, 450)
            return
        }
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val opts = ei?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnter = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnter && (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_GO ||
                action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE)) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ENTER)
        }
    }
}
