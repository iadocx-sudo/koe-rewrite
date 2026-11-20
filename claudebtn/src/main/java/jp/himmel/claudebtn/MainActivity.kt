package jp.himmel.claudebtn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(40))
            setBackgroundColor(Color.parseColor("#FAF9F5"))
        }
        col.addView(TextView(this).apply {
            text = "Claudeボタン"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#D97757"))
        })
        col.addView(note("Claudeアプリを開いている間だけ、画面下のナビバーの左に「☰」（サイドバーを開く／もう一度で閉じる）、右に「送信 ↑」を表示します。"))
        status = TextView(this).apply { textSize = 16f; setPadding(0, dp(16), 0, dp(8)) }
        col.addView(status)

        col.addView(head("① ユーザー補助をON"))
        col.addView(note("設定 → ダウンロードしたアプリ（インストール済みのサービス）→「Claudeボタン」→ ON。\n※「制限付き設定」と出たら：アプリ情報（下のボタン）→ 右上︙ →「制限付き設定を許可」してから再度ON。"))
        col.addView(btn("ユーザー補助の設定を開く") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        col.addView(btn("アプリ情報を開く") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        col.addView(head("② Xiaomi（MIUI/HyperOS）で止まらないように"))
        col.addView(note("アプリ情報 → 自動起動をON、バッテリーセーバー →「制限なし」。アプリ履歴（■）でこのアプリを長押し→🔒ロックも有効です。"))

        col.addView(head("③ 表示"))
        col.addView(CheckBox(this).apply {
            text = "ボタンを表示する"
            isChecked = getSharedPreferences("cb", MODE_PRIVATE).getBoolean("on", true)
            setOnCheckedChangeListener { _, b ->
                getSharedPreferences("cb", MODE_PRIVATE).edit().putBoolean("on", b).apply()
                NavService.instance?.setOn(b)
            }
        })
        col.addView(note("こえリライト側にも同じ機能があります。このアプリを有効にしている間は、こえリライト側は自動で表示を止めます（二重表示なし）。"))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (NavService.instance != null) "✅ 稼働中" else "⬜ 停止中（①でONにしてください）"
    }

    private fun head(t: String) = TextView(this).apply {
        text = t; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#3A2A28")); setPadding(0, dp(22), 0, dp(4))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.parseColor("#6A5A58")); setPadding(0, dp(4), 0, dp(4))
    }

    private fun btn(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#D97757")) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }
}
