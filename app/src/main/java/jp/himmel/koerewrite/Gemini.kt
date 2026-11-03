package jp.himmel.koerewrite

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiException(message: String, val code: Int) : Exception(message)

object Gemini {

    val SYSTEM_PROMPT = """
あなたは、ユーザー本人の代わりに送信用の文章を作る日本語の文章アシスタントです。

入力:
(1) 音声: ユーザーの発話。「うまい返信にして」「丁寧に」「要約して」などの指示と、伝えたい内容が混ざっていることがある。
(2) 画面の会話（ある場合）: 表示中アプリから読み取ったテキスト。[左]=相手、[右]=ユーザー本人、[中]=判別不能やUI。チャット以外の画面ならUIの文字も混ざる。
(3) 入力欄の既存テキスト（ある場合）: 貼り付けた相手のメッセージ、または下書き。

ルール:
- 発話から「指示」と「内容」を分けて解釈する。
- 指示がない場合: 発話内容を、そのまま送れる自然な文章に整える（フィラー・言い直し・重複を除去、句読点を整える。意味は変えない）。
- 「うまい返信」「返信して」等の場合: 画面の会話・入力欄の文脈を踏まえ、ユーザーが言いたい趣旨を保ったまま、自然で感じのよい返信文にする。
- 口調・距離感・絵文字の有無は、これまでのやり取りとユーザー本人の過去の発言に合わせる。友人なら砕けた口語、仕事なら簡潔なビジネス口調。
- AIっぽい過剰な丁寧さ、長い前置き、美辞麗句は避ける。
- ユーザーが言っていない事実・約束・日時を作らない。
- 出力は完成した本文だけ。説明、前置き、引用符、見出し、複数案は出さない。
""".trimIndent()

    /** Returns the generated text. Tries the given model, then fallbacks on 404. */
    fun generate(
        apiKey: String,
        model: String,
        audio: ByteArray,
        audioMime: String,
        contextText: String,
        extraRule: String,
    ): String {
        val models = (listOf(model) + Prefs.FALLBACK_MODELS).distinct()
        var last: GeminiException? = null
        for (m in models) {
            try {
                return call(apiKey, m, audio, audioMime, contextText, extraRule)
            } catch (e: GeminiException) {
                last = e
                if (e.code != 404 && e.code != 400) throw e
                if (e.code == 400 && !e.message.orEmpty().contains("model", ignoreCase = true)) throw e
            }
        }
        throw last ?: GeminiException("不明なエラー", -1)
    }

    private fun call(
        apiKey: String,
        model: String,
        audio: ByteArray,
        audioMime: String,
        contextText: String,
        extraRule: String,
    ): String {
        val sys = if (extraRule.isBlank()) SYSTEM_PROMPT
        else SYSTEM_PROMPT + "\n\nユーザー固有の追加ルール:\n" + extraRule.trim()

        val userText = buildString {
            if (contextText.isNotBlank()) {
                append(contextText.trim())
                append("\n\n")
            }
            append("添付音声の指示と内容に従って、送信する本文だけを出力してください。")
        }

        val body = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", sys))))
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray()
                    .put(JSONObject().put("text", userText))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", audioMime)
                        .put("data", Base64.encodeToString(audio, Base64.NO_WRAP)))))
            }))
            put("generationConfig", JSONObject().put("temperature", 0.4))
        }

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).getJSONObject("error").getString("message")
                } catch (e: Exception) { text.take(300) }
                val friendly = when (code) {
                    429 -> "無料枠の上限に達しました。少し待つか翌日に再試行してください。($msg)"
                    401, 403 -> "APIキーが無効です。設定を確認してください。($msg)"
                    else -> "Geminiエラー $code: $msg"
                }
                throw GeminiException(friendly, code)
            }
            return parse(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): String {
        val root = JSONObject(json)
        val cands = root.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val reason = root.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""
            throw GeminiException("結果が空でした $reason", -2)
        }
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw GeminiException("結果が空でした", -2)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        val out = sb.toString().trim().trim('「', '」').trim()
        if (out.isEmpty()) throw GeminiException("結果が空でした", -2)
        return out
    }
}
