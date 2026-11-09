package jp.himmel.koerewrite

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Text selection menu → 「AIリライト」: rewrites the selected text and replaces it. */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        val prefs = Prefs(this)
        if (text.isBlank()) { finish(); return }
        if (prefs.apiKey.isBlank()) {
            Toast.makeText(this, "こえリライトの設定でAPIキーを登録してください", Toast.LENGTH_LONG).show()
            finish(); return
        }
        Toast.makeText(this, "AIリライト中…", Toast.LENGTH_SHORT).show()
        val screen = if (prefs.useScreen) try { ScreenTextService.instance?.captureText(2500) } catch (e: Exception) { null } else null
        val apiKey = prefs.apiKey
        val model = prefs.model
        val extra = prefs.extraRule
        Thread {
            val result = try {
                Result.success(Gemini.rewriteText(apiKey, model, text, screen, extra))
            } catch (e: Throwable) { Result.failure(e) }
            runOnUiThread {
                result.onSuccess { out ->
                    if (readOnly) {
                        getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("rewrite", out))
                        Toast.makeText(this, "リライト結果をコピーしました", Toast.LENGTH_SHORT).show()
                    } else {
                        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, out))
                    }
                }
                result.onFailure {
                    prefs.lastError = it.message ?: ""
                    Toast.makeText(this, "失敗: ${it.message}", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }.start()
    }
}
