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
- 【ユーザーの発話（音声認識結果）】: 最重要。「うまい返信にして」などの指示と、伝えたい内容が混ざる。音声認識の誤変換を含むことがある。
- 【画面の会話（参考）】: 表示中アプリの文字。[左]=相手、[右]=ユーザー本人。
- 【入力欄の既存テキスト】: 貼り付けた相手のメッセージや下書き。
- 画像（ある場合）: 画面のスクリーンショット。参考情報。

最重要ルール:
- 本文の中身は「発話」から作る。画面の会話は、口調・相手との距離感・「それ」「あれ」等の指す内容を補うためだけに使う。
- 発話にない話題・事実・提案・約束を足さない。画面の会話から返信内容を推測して創作しない。
- 発話に指示がなければ、発話内容をそのまま送れる文章に整えるだけ（誤変換・句読点・言い直し・フィラーの修正のみ。意味や情報量は変えない）。
- 「うまい返信にして」「丁寧に」等の指示があれば、その指示語自体は本文に含めず、発話の内容をその指示どおりに整える。
- 口調は、ユーザー本人の過去の発言（[右]）に合わせる。友人なら砕けた口語、仕事なら簡潔なビジネス口調。
- AIっぽい過剰な丁寧さ、前置き、美辞麗句は避ける。発話より大幅に長くしない。
- 出力は完成した本文だけ。説明、引用符、見出し、複数案は出さない。
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
            for (attempt in 0..1) {
                onTry(m)
                try {
                    return try {
                        call(apiKey, m, audio, audioMime, image, contextText, extraRule, thinkingOff = true)
                    } catch (e: GeminiException) {
                        if (e.code == 400 && e.message.orEmpty().contains("thinking", true))
                            call(apiKey, m, audio, audioMime, image, contextText, extraRule, thinkingOff = false)
                        else throw e
                    }
                } catch (e: GeminiException) {
                    last = e
                    // overloaded: retry same model once after a short wait
                    if ((e.code == 503 || e.code == 500) && attempt == 0) { Thread.sleep(1500); continue }
                    // model missing / overloaded / per-model quota: try next model
                    if (e.code == 404 || e.code == 503 || e.code == 500 || e.code == 429 || e.code == -3) break
                    throw e
                }
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
        thinkingOff: Boolean,
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
            val gc = JSONObject().put("temperature", 0.3)
            if (thinkingOff) gc.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            put("generationConfig", gc)
        }

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
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
                    500, 503 -> "Geminiが混雑中です。少し時間をおいて再試行してください。($msg)"
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
