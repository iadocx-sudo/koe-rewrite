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

    private fun shotLabel() = if (prefs.alwaysShot) "📷 ON" else "📷 OFF"

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
        row.addView(pill("そのまま") { insertRawNow() }, LinearLayout.LayoutParams(lp))
        shotBtn = pill(shotLabel()) {
            prefs.alwaysShot = !prefs.alwaysShot
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
        if (state == State.LISTENING) cancel()
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
                else -> "🎤をタップして話す（例：「うまい返信にして、〇〇って伝えたい」）"
            }
            State.LISTENING -> status.text = "● 聞き取り中…　話し終えたら■をタップ"
            State.PROCESSING -> {}
        }
        val screenOn = ScreenTextService.instance != null && prefs.useScreen
        info.text = "画面読み取り: " + (if (screenOn) "ON" else "OFF") +
            "　「そのまま」=AIを通さず文字起こしだけ入力"
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

    // ---- speech recognition (continuous until ■) ----

    private fun transcript(): String {
        val p = partial.trim()
        val c = committed.toString().trim()
        return if (p.isEmpty()) c else if (c.isEmpty()) p else "$c $p"
    }

    private fun recIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
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
        committed.setLength(0)
        partial = ""
        stopRequested = false
        earlyRetries = 0
        listenStart = SystemClock.elapsedRealtime()
        transcriptView.text = ""
        state = State.LISTENING
        render()
        listenOnce()
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
                if (state != State.LISTENING) return
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                partial = list?.firstOrNull() ?: partial
                transcriptView.text = transcript()
            }

            override fun onResults(results: Bundle?) {
                if (state != State.LISTENING) return
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                partial = ""
                if (best.isNotBlank()) {
                    if (committed.isNotEmpty()) committed.append(" ")
                    committed.append(best.trim())
                }
                transcriptView.text = transcript()
                if (stopRequested) finishListening() else main.post { if (state == State.LISTENING) listenOnce() }
            }

            override fun onError(error: Int) {
                if (state != State.LISTENING) return
                if (stopRequested) { finishListening(); return }
                // errors right after start are usually the recognizer not being ready yet: retry silently
                if (transcript().isBlank() && earlyRetries < 3 &&
                    error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                    error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    earlyRetries++
                    main.postDelayed({ if (state == State.LISTENING && !stopRequested) listenOnce() }, 500L * earlyRetries)
                    return
                }
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // silence: keep listening until the user taps ■ (max 3 min)
                        if (SystemClock.elapsedRealtime() - listenStart < 180000) {
                            main.postDelayed({ if (state == State.LISTENING && !stopRequested) listenOnce() }, 150)
                        } else finishListening()
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                        main.postDelayed({ if (state == State.LISTENING && !stopRequested) listenOnce() }, 400)
                    }
                    else -> {
                        if (transcript().isNotBlank()) finishListening()
                        else {
                            destroyRecognizer()
                            state = State.IDLE
                            render()
                            status.text = "音声認識エラー($error)。もう一度どうぞ"
                        }
                    }
                }
            }
        })
        try {
            r.startListening(recIntent())
        } catch (e: Exception) {
            state = State.IDLE
            render()
            status.text = "音声認識を開始できません: ${e.message}"
        }
    }

    private fun requestStop() {
        if (state != State.LISTENING) return
        stopRequested = true
        status.text = "確定中…"
        try { recognizer?.stopListening() } catch (_: Exception) {}
        // safety: if the recognizer never calls back, proceed with what we have
        val myJob = jobId
        main.postDelayed({ if (state == State.LISTENING && jobId == myJob) finishListening() }, 2500)
    }

    private fun finishListening() {
        if (state != State.LISTENING) return
        val text = transcript()
        destroyRecognizer()
        if (text.isBlank()) {
            state = State.IDLE
            render()
            status.text = "聞き取れませんでした。もう一度どうぞ"
            return
        }
        transcriptView.text = text
        startRewrite(text)
    }

    private fun cancel() {
        jobId++
        destroyRecognizer()
        main.removeCallbacks(procTick)
        state = State.IDLE
        if (::status.isInitialized) render()
    }

    /** Insert the raw transcript without AI (works while listening or processing). */
    private fun insertRawNow() {
        val text = transcript().ifBlank { transcriptView.text?.toString().orEmpty() }
        if (text.isBlank()) return
        jobId++
        destroyRecognizer()
        main.removeCallbacks(procTick)
        insert(text, "", replace = false)
    }

    // ---- rewrite ----

    private fun currentFieldText(): String {
        val ic = currentInputConnection ?: return ""
        val ex = try { ic.getExtractedText(ExtractedTextRequest(), 0) } catch (e: Exception) { null }
        ex?.text?.let { return it.toString() }
        val before = ic.getTextBeforeCursor(5000, 0) ?: ""
        val after = ic.getTextAfterCursor(5000, 0) ?: ""
        return before.toString() + after.toString()
    }

    private var procStart = 0L
    private var procModel = ""
    private val procTick = object : Runnable {
        override fun run() {
            if (state == State.PROCESSING) {
                val s = (SystemClock.elapsedRealtime() - procStart) / 1000
                status.text = "AIが文章を作成中… ${s}秒"
                main.postDelayed(this, 1000)
            }
        }
    }

    private fun startRewrite(spoken: String) {
        val field = currentFieldText()
        val svc = if (prefs.useScreen) ScreenTextService.instance else null
        val screen = try { svc?.captureText(2500) } catch (e: Exception) { null }
        val pkg = currentInputEditorInfo?.packageName ?: ""
        val ctx = buildString {
            if (pkg.isNotEmpty()) append("【アプリ】$pkg\n\n")
            if (!screen.isNullOrBlank()) append("【画面の会話（参考）】\n").append(screen).append("\n\n")
            if (field.isNotBlank()) append("【入力欄の既存テキスト】\n").append(field.take(3000)).append("\n\n")
            append("【ユーザーの発話（音声認識結果）】\n").append(spoken)
        }
        state = State.PROCESSING
        render()
        val myJob = ++jobId
        if (svc != null && prefs.alwaysShot) {
            status.text = "画面を撮影中…"
            svc.takeShot { img -> if (myJob == jobId) send(myJob, ctx, field, spoken, img) }
        } else {
            send(myJob, ctx, field, spoken, null)
        }
    }

    private fun send(myJob: Int, ctx: String, field: String, spoken: String, img: ByteArray?) {
        val apiKey = prefs.apiKey
        val model = prefs.model
        val extra = prefs.extraRule
        procStart = SystemClock.elapsedRealtime()
        procModel = model
        main.post(procTick)
        main.postDelayed({
            if (myJob == jobId && state == State.PROCESSING) {
                jobId++
                main.removeCallbacks(procTick)
                prefs.lastError = "タイムアウト（12秒） model=$procModel"
                // fall back to the raw transcript so the user is never left with nothing
                insert(spoken, field, replace = false)
                status.text = "AIが混雑のため、文字起こしをそのまま入力しました"
            }
        }, if (img == null) 12000L else 45000L)
        Thread {
            val result = try {
                if (img == null)
                    Result.success(Gemini.generateFast(apiKey, model, ctx, extra, Gemini.SYSTEM_PROMPT,
                        onWin = { m -> main.post { procModel = m } }))
                else
                    Result.success(Gemini.generate(apiKey, model, null, "", img, ctx, extra,
                        onTry = { m -> main.post { procModel = m } }))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            main.post {
                if (myJob != jobId) return@post
                main.removeCallbacks(procTick)
                val secs = (SystemClock.elapsedRealtime() - procStart) / 1000
                result.onSuccess {
                    prefs.lastError = ""
                    insert(it, field, replace = false)
                    status.text = "✓ 入力しました（AI ${secs}秒・$procModel）"
                }
                result.onFailure {
                    prefs.lastError = it.message ?: it.javaClass.simpleName
                    insert(spoken, field, replace = false)
                    status.text = "AI失敗のため文字起こしを入力: ${it.message}"
                }
            }
        }.start()
    }

    private fun insert(text: String, oldField: String, replace: Boolean) {
        val ic = currentInputConnection
        if (ic == null) {
            state = State.IDLE
            render()
            status.text = "入力欄が見つかりません"
            return
        }
        ic.beginBatchEdit()
        if (replace && prefs.replaceField && oldField.isNotEmpty()) {
            val cur = currentFieldText()
            ic.setSelection(0, cur.length)
        }
        ic.commitText(text, 1)
        ic.endBatchEdit()

        state = State.IDLE
        render()
        status.text = "✓ 入力しました"
        if (prefs.autoReturn) {
            main.postDelayed({ if (state == State.IDLE) goBack() }, 900)
        }
    }
}
