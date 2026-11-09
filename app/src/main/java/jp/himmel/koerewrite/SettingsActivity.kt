package jp.himmel.koerewrite

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var keyEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var ruleEdit: EditText
    private lateinit var testView: TextView

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(40))
            setBackgroundColor(Color.parseColor("#FFF8F6"))
        }

        col.addView(TextView(this).apply {
            text = "こえリライト"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#D9534F"))
        })
        col.addView(note("Gboardの地球儀キーで切り替え → 話す → AIが整えた文章を入力 → 自動でGboardに戻ります。"))

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#FBE3DE")) }
        }
        col.addView(statusView, lpTop(12))

        // 1 API key
        col.addView(head("① Gemini APIキー（無料）"))
        keyEdit = EditText(this).apply {
            hint = "AIza..."
            setText(prefs.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        col.addView(keyEdit)
        col.addView(btn("APIキーを取得（Google AI Studio）") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        })
        col.addView(btn("保存") { save(); toast("保存しました") })
        col.addView(btn("接続テスト（APIキーとモデルの確認）") { runTest() })
        testView = note("")
        col.addView(testView)

        // 2 mic
        col.addView(head("② マイクを許可"))
        col.addView(btn("マイクを許可する") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })

        // 3 keyboard
        col.addView(head("③ キーボードを有効にする"))
        col.addView(note("「こえリライト（AI音声）」をONにしてください。"))
        col.addView(btn("キーボード設定を開く") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        col.addView(note("Gboard側：Gboard設定 → 言語 → 「他の入力方法に切り替える」をON。これで地球儀キーが出ます。英語を別言語で追加していると2回押しになるので、英語は「あa」キーで切り替えるのがおすすめ。"))
        col.addView(btn("Gboardの設定を開く") {
            try {
                startActivity(Intent().setComponent(ComponentName("com.google.android.inputmethod.latin",
                    "com.google.android.apps.inputmethod.libs.framework.core.LauncherActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })

        // 4 accessibility
        col.addView(head("④ 画面読み取り（LINEの会話を踏まえる・任意）"))
        col.addView(note("ユーザー補助 →「こえリライト 画面読み取り」をON。グレーで押せない場合は、下の「アプリ情報」→ 右上︙ →「制限付き設定を許可」を押してから再度ON。"))
        col.addView(btn("ユーザー補助設定を開く") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(btn("アプリ情報を開く（制限付き設定の許可）") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(note("Xiaomi/Redmi等：アプリ情報で「自動起動」をON、バッテリーを「制限なし」にすると読み取りが止まりにくくなります。"))

        // 5 options
        col.addView(head("⑤ 動作"))
        col.addView(check("キーボードを開いたら自動で録音開始", prefs.autoStart) { prefs.autoStart = it })
        col.addView(check("入力後、自動でGboardに戻る", prefs.autoReturn) { prefs.autoReturn = it })
        col.addView(check("入力欄の文字（貼り付けた相手の文など）を結果で置き換える", prefs.replaceField) { prefs.replaceField = it })
        col.addView(check("画面の会話を文脈に使う", prefs.useScreen) { prefs.useScreen = it })
        col.addView(check("Gboard表示中に浮遊マイクボタンを出す（④が必要）", prefs.floatButton) {
            prefs.floatButton = it
            ScreenTextService.instance?.refreshBubble()
        })
        col.addView(note("浮遊ボタンはドラッグで好きな位置に動かせます（位置は記憶）。"))
        col.addView(check("スクリーンショットも送る（通常はOFF推奨。遅くなります）", prefs.alwaysShot) { prefs.alwaysShot = it })

        col.addView(head("追加ルール（任意）"))
        ruleEdit = EditText(this).apply {
            hint = "例：絵文字は控えめ。語尾は「〜です」より「〜だよ」寄り。"
            setText(prefs.extraRule)
            minLines = 2
        }
        col.addView(ruleEdit)

        col.addView(head("モデル（通常は変更不要）"))
        modelEdit = EditText(this).apply {
            setText(prefs.model)
            isSingleLine = true
        }
        col.addView(modelEdit)
        col.addView(btn("保存") { save(); toast("保存しました") })

        // test
        col.addView(head("テスト"))
        col.addView(note("ここをタップ → 地球儀で切り替えて試せます。"))
        col.addView(EditText(this).apply { hint = "ここで試す"; minLines = 3 })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun save() {
        prefs.apiKey = keyEdit.text.toString()
        prefs.model = modelEdit.text.toString()
        prefs.extraRule = ruleEdit.text.toString()
        refresh()
    }

    private fun refresh() {
        if (!::statusView.isInitialized) return
        val imm = getSystemService(InputMethodManager::class.java)
        val imeOn = imm.enabledInputMethodList.any { it.packageName == packageName }
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val a11y = ScreenTextService.instance != null
        fun mark(b: Boolean) = if (b) "✅" else "⬜"
        statusView.text = "${mark(prefs.apiKey.isNotBlank())} APIキー\n" +
            "${mark(mic)} マイク許可\n" +
            "${mark(imeOn)} キーボード有効\n" +
            "${mark(a11y)} 画面読み取り（任意）" +
            (if (prefs.lastError.isNotBlank()) "\n\n直近のエラー: ${prefs.lastError}" else "")
    }

    private fun runTest() {
        save()
        val key = prefs.apiKey
        if (key.isBlank()) { testView.text = "APIキーが空です"; return }
        testView.text = "テスト中…"
        val model = prefs.model
        val t0 = System.currentTimeMillis()
        Thread {
            val msg = try {
                "✅ 成功（${System.currentTimeMillis() - t0}ms）: " + Gemini.test(key, model)
            } catch (e: Throwable) {
                "❌ 失敗（${System.currentTimeMillis() - t0}ms）: ${e.message}"
            }
            runOnUiThread { testView.text = msg }
        }.start()
    }

    private fun lpTop(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun head(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#3A2A28"))
        setPadding(0, dp(22), 0, dp(4))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.parseColor("#6A5A58"))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun btn(t: String, onClick: () -> Unit): View = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#FA8072")) }
        setOnClickListener { onClick() }
        layoutParams = lpTop(8)
    }

    private fun check(t: String, v: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = t
        isChecked = v
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
