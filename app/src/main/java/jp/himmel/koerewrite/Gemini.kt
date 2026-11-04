package jp.himmel.koerewrite

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class GeminiException(message: String, val code: Int) : Exception(message)

object Gemini {

    val SYSTEM_PROMPT = """
あなたは、ユーザー本人の代わりに送信用の文章を作る日本語の文章アシスタントです。

入力:
(1) 音声: ユーザーの発話。「うまい返信にして」「丁寧に」「要約して」などの指示と、伝えたい内容が混ざっていることがある。
(2) 画面の会話（ある場合）: 表示中アプリから読み取ったテキスト。[左]=相手、[右]=ユーザー本人、[中]=判別不能やUI。
(3) 画面のスクリーンショット（ある場合）: 会話画面の画像。チャットでは右側の吹き出し=ユーザー本人、左側=相手。下部のキーボードは無視する。
(4) 入力欄の既存テキスト（ある場合）: 貼り付けた相手のメッセージ、または下書き。

ルール:
- 発話から「指示」と「内容」を分けて解釈する。
- 指示がない場合: 発話内容を、そのまま送れる自然な文章に整える（フィラー・言い直し・重複を除去、句読点を整える。意味は変えない）。
- 「うまい返信」「返信して」等の場合: 画面の会話・入力欄の文脈を踏まえ、ユーザーが言いたい趣旨を保ったまま、自然で感じのよい返信文にする。
- 口調・距離感・絵文字の有無は、これまでのやり取りとユーザー本人の過去の発言に合わせる。友人なら砕けた口語、仕事なら簡潔なビジネス口調。
- AIっぽい過剰な丁寧さ、長い前置き、美辞麗句は避ける。
- ユーザーが言っていない事実・約束・日時を作らない。
- 出力は完成した本文だけ。説明、前置き、引用符、見出し、複数案は出さない。
""".trimIndent()

    /** Tries the given model, then fallbacks only when the model is not found (404). */
    fun generate(
        apiKey: String,
        model: String,
        audio: ByteArray?,
        audioMime: String,
        image: ByteArray?,
        contextText: String,
        extraRule: String,
        onTry: (String) -> Unit = {},
    ): String {
        val models = (listOf(model) + Prefs.FALLBACK_MODELS).distinct()
        var last: GeminiException? = null
        for (m in models) {
            onTry(m)
            try {
                return call(apiKey, m, audio, audioMime, image, contextText, extraRule)
            } catch (e: GeminiException) {
                last = e
                if (e.code != 404) throw e
            }
        }
        throw last ?: GeminiException("不明なエラー", -1)
    }

    /** Text-only connectivity test. Returns "model: reply". */
    fun test(apiKey: String, model: String): String {
        var used = model
        val out = generate(apiKey, model, null, "", null,
            "【接続テスト】「OK」とだけ出力してください。", "", onTry = { used = it })
        return "$used → $out"
    }

    private fun call(
        apiKey: String,
        model: String,
        audio: ByteArray?,
        audioMime: String,
        image: ByteArray?,
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
            if (audio != null) append("添付音声の指示と内容に従って、送信する本文だけを出力してください。")
        }

        val parts = JSONArray().put(JSONObject().put("text", userText))
        if (image != null) {
            parts.put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(image, Base64.NO_WRAP))))
        }
        if (audio != null) {
            parts.put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", audioMime)
                .put("data", Base64.encodeToString(audio, Base64.NO_WRAP))))
        }

        val body = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", sys))))
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }))
            put("generationConfig", JSONObject().put("temperature", 0.4))
        }

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 40000
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
                    401, 403 -> "APIキーが無効です。AI Studioでキーを確認してください。($msg)"
                    400 -> if (msg.contains("API key", true)) "APIキーが無効です。AI Studioでキーの全文をコピーし直してください。($msg)" else "リクエストエラー: $msg"
                    404 -> "モデル $model が見つかりません"
                    else -> "Geminiエラー $code: $msg"
                }
                throw GeminiException(friendly, code)
            }
            return parse(text)
        } catch (e: SocketTimeoutException) {
            throw GeminiException("Geminiの応答がタイムアウトしました（通信状況を確認してください）", -3)
        } catch (e: GeminiException) {
            throw e
        } catch (e: Exception) {
            throw GeminiException("通信エラー: ${e.javaClass.simpleName} ${e.message ?: ""}", -4)
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
            ?: throw GeminiException("結果が空でした（${cands.getJSONObject(0).optString("finishReason")}）", -2)
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
