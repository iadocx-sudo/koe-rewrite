package jp.himmel.claudebtn

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/** Claudeアプリ表示中だけナビバー左右にボタンを出す。定期処理なし（画面切替イベントのみで動く）。 */
class NavService : AccessibilityService() {

    companion object {
        @Volatile var instance: NavService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var nav: ClaudeNavButtons? = null
    private val upd = Runnable { nav?.update() }
    private val updLate = Runnable { nav?.update() }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_OFF) nav?.hide() else kick()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        nav = ClaudeNavButtons(this).also { it.enabled = getSharedPreferences("cb", MODE_PRIVATE).getBoolean("on", true) }
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        })
        kick()
    }

    fun setOn(on: Boolean) {
        getSharedPreferences("cb", MODE_PRIVATE).edit().putBoolean("on", on).apply()
        main.post { nav?.enabled = on }
    }

    private fun kick() {
        main.removeCallbacks(upd); main.postDelayed(upd, 120)
        main.removeCallbacks(updLate); main.postDelayed(updLate, 700)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { if (event != null) kick() }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { unregisterReceiver(screen) } catch (_: Exception) {}
        nav?.hide()
        instance = null
        super.onDestroy()
    }
}
