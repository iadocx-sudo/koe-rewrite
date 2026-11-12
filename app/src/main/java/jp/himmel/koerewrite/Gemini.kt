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
- 【入力欄の既存テキスト】: すでに入力済みの文章。これは消されずに残り、あなたの出力はその続きとして追記される。既存テキストを繰り返さず、今回の発話分だけを、続きとして自然につながる形で出力する。
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

    val REWRITE_PROMPT = """
あなたは日本語の文章整形アシスタントです。
【下書き】は、ユーザーが思いつくまま入力した文章です（音声入力の誤変換、句読点なし、言い直し、話し言葉を含む）。
これを、そのまま送信できる自然で読みやすい文章に整えてください。

ルール:
- 内容・意図・情報量は変えない。話題や事実を足さない。大事な内容を削らない。
- 誤変換、句読点、改行、語順を整え、重複・言い直し・フィラー（えーと等）を除く。
- 下書きの中に「うまい返信にして」「丁寧に」「短く」などAIへの指示が含まれていれば、それに従う。指示の文自体は出力に含めない。
- 口調は下書きの口調に合わせる（友人向けならくだけたまま、仕事なら簡潔なビジネス口調）。指示がない限り、勝手に敬語化しない。
- 【画面の会話】がある場合は、相手との関係や口調、「それ」「あれ」の指す内容の参考にだけ使う。[左]=相手、[右]=ユーザー本人。
- AIっぽい過剰な丁寧さ、前置き、美辞麗句は避ける。
- 出力は整えた本文だけ。説明、引用符、見出し、複数案は出さない。
""".trimIndent()

    fun rewriteText(apiKey: String, model: String, draft: String, screen: String?, extraRule: String): String {
        val ctx = buildString {
            if (!screen.isNullOrBlank()) append("【画面の会話（参考）】\n").append(screen).append("\n\n")
            append("【下書き】\n").append(draft)
        }
        return generateFast(apiKey, model, ctx, extraRule, REWRITE_PROMPT)
    }

    // ------------------------------------------------------------------
    // Fast path (text only): race several light models, first answer wins.
    // ------------------------------------------------------------------

    private var store: android.content.SharedPreferences? = null
    @Volatile private var discovered: List<String> = emptyList()
    @Volatile var lastWinner: String? = null
        private set
    private val dead = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var lastWarm = 0L

    private val STATIC_FAST = listOf(
        "gemini-flash-lite-latest", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-flash-latest")

    @Synchronized
    fun attach(sp: android.content.SharedPreferences) {
        if (store != null) return
        store = sp
        discovered = (sp.getString("fastModels", "") ?: "").split(",").filter { it.isNotBlank() }
        lastWinner = sp.getString("lastWinner", null)
        (sp.getString("modes", "") ?: "").split(",").forEach {
            val kv = it.split("=")
            if (kv.size == 2) kv[1].toIntOrNull()?.let { m -> modeCache[kv[0]] = m }
        }
    }

    private fun persist() {
        val sp = store ?: return
        sp.edit()
            .putString("fastModels", discovered.joinToString(","))
            .putString("lastWinner", lastWinner)
            .putString("modes", modeCache.entries.joinToString(",") { "${it.key}=${it.value}" })
            .apply()
    }

    fun candidates(preferred: String): List<String> =
        (listOfNotNull(lastWinner) + discovered.take(2) + listOf(preferred) + STATIC_FAST)
            .filter { it.isNotBlank() && dead[it] != true }.distinct().take(4)

    /** Opens the connection in advance and learns which light models this key can use. */
    fun prewarm(apiKey: String) {
        val now = System.currentTimeMillis()
        if (apiKey.isBlank() || now - lastWarm < 45000) return
        lastWarm = now
        Thread {
            try {
                val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 6000
                    setRequestProperty("x-goog-api-key", apiKey)
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    val arr = JSONObject(text).optJSONArray("models") ?: JSONArray()
                    val names = ArrayList<String>()
                    val bad = listOf("tts", "image", "live", "audio", "native", "embedding", "robotics", "computer")
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        val name = m.optString("name").removePrefix("models/")
                        val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                        if (!name.contains("flash-lite")) continue
                        if (!methods.contains("generateContent")) continue
                        if (bad.any { name.contains(it) }) continue
                        names.add(name)
                    }
                    val sorted = names.sortedWith(
                        compareByDescending<String> { it == "gemini-flash-lite-latest" }
                            .thenBy { it.contains("preview") || it.contains("exp") }
                            .thenByDescending { it })
                    if (sorted.isNotEmpty()) {
                        discovered = sorted.take(3)
                        persist()
                    }
                }
            } catch (_: Throwable) {}
        }.start()
    }

    /**
     * Text-only generation tuned for speed: starts the best model, adds another every 1.2s,
     * returns the first success. Gives up at deadlineMs.
     */
    fun generateFast(
        apiKey: String, preferred: String, contextText: String, extraRule: String, system: String,
        deadlineMs: Long = 10000, onWin: (String) -> Unit = {},
    ): String {
        val models = candidates(preferred)
        val q = java.util.concurrent.LinkedBlockingQueue<Pair<String, Result<String>>>()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val start = System.currentTimeMillis()
        models.forEachIndexed { i, m ->
            Thread {
                try {
                    if (i > 0) Thread.sleep(1200L * i)
                    if (done.get()) return@Thread
                    q.put(m to Result.success(callAnyMode(apiKey, m, null, "", null, contextText, extraRule, system, 9000)))
                } catch (e: Throwable) {
                    q.put(m to Result.failure(e))
                }
            }.start()
        }
        var failures = 0
        var last: Throwable? = null
        while (true) {
            val left = deadlineMs - (System.currentTimeMillis() - start)
            if (left <= 0) break
            val r = q.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
            val (m, res) = r
            if (res.isSuccess) {
                done.set(true)
                lastWinner = m
                persist()
                onWin(m)
                return res.getOrThrow()
            }
            val e = res.exceptionOrNull()
            last = e
            if (e is GeminiException) {
                if (e.code == 404) dead[m] = true
                // key problems: no point waiting for the others
                if (e.code == 401 || e.code == 403 || (e.code == 400 && e.message.orEmpty().contains("API key", true))) {
                    done.set(true); throw e
                }
            }
            failures++
            if (failures >= models.size) break
        }
        done.set(true)
        if (failures >= models.size && last != null) throw last
        throw GeminiException("${deadlineMs / 1000}秒以内に応答がありませんでした（Geminiが混雑中）", -3)
    }

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
        system: String = SYSTEM_PROMPT,
    ): String {
        val models = (listOf(model) + Prefs.FALLBACK_MODELS).distinct()
        var last: GeminiException? = null
        for (m in models) {
            for (attempt in 0..1) {
                onTry(m)
                try {
                    return callAnyMode(apiKey, m, audio, audioMime, image, contextText, extraRule, system)
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

    /** thinking settings to try, fastest first; the last one (no config) always works. */
    private val modeCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private const val MODES = 3

    private fun callAnyMode(
        apiKey: String, model: String, audio: ByteArray?, audioMime: String,
        image: ByteArray?, contextText: String, extraRule: String, system: String,
        readMs: Int = 30000,
    ): String {
        var mode = modeCache[model] ?: 0
        while (true) {
            try {
                val r = call(apiKey, model, audio, audioMime, image, contextText, extraRule, mode, system, readMs)
                if (modeCache[model] != mode) { modeCache[model] = mode; persist() }
                return r
            } catch (e: GeminiException) {
                val keyProblem = e.message.orEmpty().contains("API key", true)
                if (e.code == 400 && !keyProblem && mode < MODES - 1) { mode++; continue }
                throw e
            }
        }
    }

    /** Text-only connectivity test. Returns "model: reply". */
    fun test(apiKey: String, model: String): String {
        var used = model
        val out = generateFast(apiKey, model, "【接続テスト】「OK」とだけ出力してください。", "", SYSTEM_PROMPT,
            onWin = { used = it })
        return "$used（思考設定${modeCache[used] ?: "-"}／候補: ${candidates(model).joinToString(" → ")}）→ $out"
    }

    private fun call(
        apiKey: String,
        model: String,
        audio: ByteArray?,
        audioMime: String,
        image: ByteArray?,
        contextText: String,
        extraRule: String,
        thinkingMode: Int,
        system: String,
        readMs: Int = 30000,
    ): String {
        val sys = if (extraRule.isBlank()) system
        else system + "\n\nユーザー固有の追加ルール:\n" + extraRule.trim()

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
            when (thinkingMode) {
                0 -> gc.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
                1 -> gc.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                else -> {}
            }
            put("generationConfig", gc)
        }

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = minOf(15000, readMs / 2)
            readTimeout = readMs
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
