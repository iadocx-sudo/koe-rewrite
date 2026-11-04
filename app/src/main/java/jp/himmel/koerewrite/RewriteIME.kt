package jp.himmel.koerewrite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class RewriteIME : InputMethodService() {

    private enum class State { IDLE, RECORDING, PROCESSING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var state = State.IDLE
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var recStart = 0L
    private var jobId = 0

    private lateinit var status: TextView
    private lateinit var micBtn: TextView
    private lateinit var info: TextView
    private lateinit var lastResult: TextView
    private lateinit var shotBtn: TextView

    private fun shotLabel() = if (prefs.alwaysShot) "📷 常時" else "📷 自動"

    private val tick = object : Runnable {
        override fun run() {
            if (state == State.RECORDING) {
                val s = (SystemClock.elapsedRealtime() - recStart) / 1000
                status.text = "● 録音中 %d:%02d　話し終えたらマイクをタップ".format(s / 60, s % 60)
                main.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun pill(text: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(fg)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(bg)
            }
            setOnClickListener { onClick() }
        }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FDF1EE"))
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#5A3A35"))
            gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        micBtn = TextView(this).apply {
            text = "🎤"
            textSize = 40f
            gravity = Gravity.CENTER
            setOnClickListener { onMic() }
        }
        val micLp = LinearLayout.LayoutParams(dp(110), dp(110)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10); bottomMargin = dp(8)
        }
        root.addView(micBtn, micLp)

        lastResult = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#7A5A55"))
            gravity = Gravity.CENTER
            maxLines = 3
        }
        root.addView(lastResult, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val lp = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(8), dp(4), 0) }
        row.addView(pill("⌨戻る", Color.parseColor("#E8D6D2"), Color.parseColor("#3A2A28")) { cancel(); goBack() }, lp)
        row.addView(pill("✕取消", Color.parseColor("#E8D6D2"), Color.parseColor("#3A2A28")) { cancel() }, LinearLayout.LayoutParams(lp))
        shotBtn = pill(shotLabel(), Color.parseColor("#E8D6D2"), Color.parseColor("#3A2A28")) {
            prefs.alwaysShot = !prefs.alwaysShot
            shotBtn.text = shotLabel()
        }
        row.addView(shotBtn, LinearLayout.LayoutParams(lp))
        row.addView(pill("⚙", Color.parseColor("#E8D6D2"), Color.parseColor("#3A2A28")) { openSettings() }, LinearLayout.LayoutParams(lp))
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
        render()
        if (!restarting && state == State.IDLE && prefs.autoStart && ready()) {
            startRecording()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (state == State.RECORDING) cancel()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopRecorder()
        super.onDestroy()
    }

    // ---- UI state ----

    private fun render() {
        val (color, label) = when (state) {
            State.IDLE -> "#FA8072" to "🎤"
            State.RECORDING -> "#E53935" to "■"
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
                else -> "マイクをタップして話す（例：「うまい返信にして、〇〇って伝えたい」）"
            }
            State.RECORDING -> status.text = "● 録音中　話し終えたらマイクをタップ"
            State.PROCESSING -> status.text = "AIが文章を作成中…"
        }
        val screenOn = ScreenTextService.instance != null && prefs.useScreen
        info.text = "画面読み取り: " + (if (screenOn) "ON" else "OFF") + "　/　入力欄の文字も文脈に使用"
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun ready() = prefs.apiKey.isNotBlank() && hasMic()

    private fun onMic() {
        when (state) {
            State.IDLE -> {
                if (!ready()) { openSettings(); return }
                startRecording()
            }
            State.RECORDING -> finishAndSend()
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

    // ---- recording ----

    private fun startRecording() {
        try {
            val f = File(cacheDir, "rec.aac")
            if (f.exists()) f.delete()
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(32000)
            r.setMaxDuration(5 * 60 * 1000)
            r.setOutputFile(f.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) main.post { finishAndSend() }
            }
            r.prepare()
            r.start()
            recorder = r
            audioFile = f
            recStart = SystemClock.elapsedRealtime()
            state = State.RECORDING
            render()
            main.post(tick)
        } catch (e: Exception) {
            stopRecorder()
            state = State.IDLE
            render()
            status.text = "録音を開始できません: ${e.message}"
        }
    }

    private fun stopRecorder(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try { r.stop(); true } catch (e: Exception) { false } finally { r.release() }
    }

    private fun cancel() {
        jobId++
        main.removeCallbacks(tick)
        stopRecorder()
        audioFile?.delete()
        state = State.IDLE
        if (::status.isInitialized) render()
    }

    // ---- send ----

    private fun currentFieldText(): String {
        val ic = currentInputConnection ?: return ""
        val ex = try { ic.getExtractedText(ExtractedTextRequest(), 0) } catch (e: Exception) { null }
        ex?.text?.let { return it.toString() }
        val before = ic.getTextBeforeCursor(5000, 0) ?: ""
        val after = ic.getTextAfterCursor(5000, 0) ?: ""
        return before.toString() + after.toString()
    }

    private fun finishAndSend() {
        if (state != State.RECORDING) return
        main.removeCallbacks(tick)
        val elapsed = SystemClock.elapsedRealtime() - recStart
        val ok = stopRecorder()
        val f = audioFile
        if (!ok || f == null || !f.exists() || f.length() < 800 || elapsed < 700) {
            state = State.IDLE
            render()
            status.text = "音声が短すぎます。もう一度どうぞ"
            return
        }

        val field = currentFieldText()
        val svc = if (prefs.useScreen) ScreenTextService.instance else null
        val screen = try { svc?.captureText() } catch (e: Exception) { null }
        val pkg = currentInputEditorInfo?.packageName ?: ""

        val ctx = buildString {
            if (pkg.isNotEmpty()) append("【アプリ】$pkg\n\n")
            if (!screen.isNullOrBlank()) append("【画面の会話】\n").append(screen).append("\n\n")
            if (field.isNotBlank()) append("【入力欄の既存テキスト】\n").append(field.take(4000)).append("\n")
        }
        val screenLen = screen?.length ?: 0
        val needShot = svc != null && (prefs.alwaysShot || screenLen < 40)

        state = State.PROCESSING
        render()
        val myJob = ++jobId
        if (needShot) {
            status.text = "画面を撮影中…"
            svc!!.takeShot { img ->
                if (myJob == jobId) send(myJob, f, ctx, field, img, screenLen)
            }
        } else {
            send(myJob, f, ctx, field, null, screenLen)
        }
    }

    private var procStart = 0L
    private var procModel = ""
    private val procTick = object : Runnable {
        override fun run() {
            if (state == State.PROCESSING) {
                val s = (SystemClock.elapsedRealtime() - procStart) / 1000
                status.text = "AIが文章を作成中… ${s}秒（$procModel）"
                main.postDelayed(this, 1000)
            }
        }
    }

    private fun send(myJob: Int, f: File, ctx: String, field: String, img: ByteArray?, screenLen: Int) {
        val apiKey = prefs.apiKey
        val model = prefs.model
        val extra = prefs.extraRule
        procStart = SystemClock.elapsedRealtime()
        procModel = model
        main.post(procTick)
        val diag = "画面文字:${screenLen} / スクショ:${if (img != null) "あり" else "なし"} / 入力欄:${field.length}"
        // watchdog: never stay in "作成中" forever
        main.postDelayed({
            if (myJob == jobId && state == State.PROCESSING) {
                jobId++
                state = State.IDLE
                render()
                status.text = "タイムアウト（70秒）。⚙設定の「接続テスト」で原因を確認できます"
                prefs.lastError = "タイムアウト（70秒） model=$procModel"
            }
        }, 70000)
        Thread {
            val result = try {
                Result.success(Gemini.generate(apiKey, model, f.readBytes(), "audio/aac", img, ctx, extra,
                    onTry = { m -> main.post { procModel = m } }))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            main.post {
                if (myJob != jobId) return@post
                main.removeCallbacks(procTick)
                f.delete()
                result.onSuccess {
                    prefs.lastError = ""
                    insert(it, field)
                    lastResult.text = diag + "\n" + it.replace("\n", " ").take(60)
                }
                result.onFailure {
                    state = State.IDLE
                    render()
                    status.text = "失敗: ${it.message}"
                    lastResult.text = diag
                    prefs.lastError = it.message ?: it.javaClass.simpleName
                }
            }
        }.start()
    }

    private fun insert(text: String, oldField: String) {
        val ic = currentInputConnection
        if (ic == null) {
            state = State.IDLE
            render()
            status.text = "入力欄が見つかりません"
            return
        }
        ic.beginBatchEdit()
        if (prefs.replaceField && oldField.isNotEmpty()) {
            val cur = currentFieldText()
            ic.setSelection(0, cur.length)
        }
        ic.commitText(text, 1)
        ic.endBatchEdit()

        state = State.IDLE
        render()
        status.text = "✓ 入力しました"
        if (prefs.autoReturn) {
            main.postDelayed({ if (state == State.IDLE) goBack() }, 700)
        }
    }
}
