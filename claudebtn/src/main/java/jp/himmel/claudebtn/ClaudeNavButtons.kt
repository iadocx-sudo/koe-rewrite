package jp.himmel.claudebtn

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast

/**
 * Claudeアプリが前面のときだけ、ナビバーの左右に2つのボタンを出す。
 *  左 ☰ : サイドバー（サブウィンドウ）を開く / 開いていれば閉じる
 *  右 ↑ : 送信ボタンを押す
 * 共通コード（こえリライト / Claudeボタン の両アプリで同じ内容）。
 */
class ClaudeNavButtons(private val svc: AccessibilityService) {

    companion object {
        const val CLAUDE_PKG = "com.anthropic.claude"
        private val DRAWER_WORDS = listOf(
            "新規セッション", "新規チャット", "スケジュール済みタスク", "最近の項目",
            "New chat", "New session", "Recents", "Scheduled tasks"
        )
        private val MENU_WORDS = listOf(
            "メニュー", "サイドバー", "ナビゲーション", "ドロワー", "menu", "sidebar", "navigation", "drawer"
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var left: View? = null
    private var right: View? = null
    var enabled = true
        set(v) { field = v; if (!v) hide() else update() }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), svc.resources.displayMetrics).toInt()

    private fun toast(t: String) = Toast.makeText(svc, t, Toast.LENGTH_SHORT).show()

    // ---------------- visibility ----------------

    private fun claudeWindow(): AccessibilityWindowInfo? {
        val wins = try { svc.windows } catch (e: Exception) { emptyList<AccessibilityWindowInfo>() }
        val apps = wins.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val top = apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused } ?: return null
        val pkg = try { top.root?.packageName?.toString() } catch (e: Exception) { null }
        return if (pkg == CLAUDE_PKG) top else null
    }

    fun update() {
        if (!enabled) { hide(); return }
        val pm = svc.getSystemService(android.os.PowerManager::class.java)
        if (pm != null && !pm.isInteractive) { hide(); return }
        if (claudeWindow() != null) show() else hide()
    }

    fun hide() {
        left?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        right?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        left = null; right = null
    }

    // ---------------- overlay ----------------

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun navBarHeight(): Int {
        val res = svc.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        val h = if (id > 0) res.getDimensionPixelSize(id) else 0
        return if (h > dp(20)) h else dp(48)
    }

    private fun makeLp(x: Int, y: Int, w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x; this.y = y
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        if (Build.VERSION.SDK_INT >= 28)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeButton(label: String, color: String, onTap: () -> Unit): View {
        val v = TextView(svc).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor(color))
            }
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.alpha = 0.6f; true }
                MotionEvent.ACTION_UP -> {
                    view.alpha = 1f
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { view.alpha = 1f; true }
                else -> true
            }
        }
        return v
    }

    private fun show() {
        val (sw, sh) = screenSize()
        val nh = navBarHeight()
        val w = (sw * 0.22f).toInt()
        val h = (nh - dp(8)).coerceAtLeast(dp(36))
        val y = sh - nh + (nh - h) / 2
        val m = dp(8)
        if (left == null) {
            val v = makeButton("☰", "#CC8A6A5A") { toggleDrawer() }
            try { wm.addView(v, makeLp(m, y, w, h)); left = v } catch (_: Exception) {}
        } else {
            try { wm.updateViewLayout(left, makeLp(m, y, w, h)) } catch (_: Exception) {}
        }
        if (right == null) {
            val v = makeButton("送信 ↑", "#E0D97757") { send() }
            try { wm.addView(v, makeLp(sw - w - m, y, w, h)); right = v } catch (_: Exception) {}
        } else {
            try { wm.updateViewLayout(right, makeLp(sw - w - m, y, w, h)) } catch (_: Exception) {}
        }
    }

    // ---------------- actions ----------------

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > 60) return
        visit(node)
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, visit)
    }

    private fun label(n: AccessibilityNodeInfo) =
        ((n.contentDescription ?: "").toString() + " " + (n.text ?: "").toString()).trim()

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        var d = 0
        while (c != null && !c.isClickable && d < 3) { c = c.parent; d++ }
        return if (c != null && c.isClickable && c.isEnabled) c else null
    }

    private fun isDrawerOpen(root: AccessibilityNodeInfo): Boolean {
        var open = false
        walk(root, 0) { n ->
            if (open || !n.isVisibleToUser) return@walk
            val l = label(n)
            if (l.isNotEmpty() && DRAWER_WORDS.any { l.contains(it, ignoreCase = true) }) open = true
        }
        return open
    }

    private fun toggleDrawer() {
        val win = claudeWindow() ?: return
        val root = win.root ?: return
        if (isDrawerOpen(root)) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }
        val (sw, sh) = screenSize()
        val r = Rect()
        // 1) ラベルで「メニュー」ボタンを探す
        var menu: AccessibilityNodeInfo? = null
        walk(root, 0) { n ->
            if (menu != null || !n.isVisibleToUser) return@walk
            val l = label(n)
            if (l.isEmpty() || l.length > 30) return@walk
            if (MENU_WORDS.any { l.contains(it, ignoreCase = true) }) {
                val c = clickableOf(n) ?: return@walk
                c.getBoundsInScreen(r)
                if (r.top < sh * 0.25f) menu = c
            }
        }
        if (menu?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        // 2) 画面左上で一番上にあるタップ可能な部品（≡ボタン）を押す
        var best: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE
        walk(root, 0) { n ->
            if (!n.isVisibleToUser || !n.isClickable || !n.isEnabled || n.isEditable) return@walk
            n.getBoundsInScreen(r)
            if (r.centerX() < sw * 0.25f && r.centerY() < sh * 0.2f && r.width() < sw * 0.3f && r.top < bestTop) {
                bestTop = r.top; best = n
            }
        }
        if (best?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        // 3) 最終手段：左端から右へスワイプ
        val p = Path().apply { moveTo(2f, sh * 0.5f); lineTo(sw * 0.7f, sh * 0.5f) }
        svc.dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 250)).build(),
            null, null
        )
    }

    private fun send() {
        val win = claudeWindow() ?: return
        val root = win.root ?: return
        val r = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestBottom = -1
        walk(root, 0) { n ->
            if (!n.isVisibleToUser || n.isEditable) return@walk
            val l = label(n).lowercase()
            if (l.isEmpty() || l.length > 25) return@walk
            val ng = listOf("取消", "取り消", "フィードバック", "feedback", "cancel", "共有", "share")
            val hit = (l.contains("送信") || l == "send" || l.startsWith("send ") || l.contains("send message") ||
                l.contains("submit")) && ng.none { l.contains(it) }
            if (!hit) return@walk
            val c = clickableOf(n) ?: return@walk
            c.getBoundsInScreen(r)
            if (r.bottom > bestBottom) { bestBottom = r.bottom; best = c }
        }
        if (best?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        // 予備：入力欄の右下にある最も右のボタン
        var edit: AccessibilityNodeInfo? = null
        walk(root, 0) { n -> if (edit == null && n.isEditable && n.isVisibleToUser) edit = n }
        val e = edit
        if (e == null) { toast("送信ボタンが見つかりません"); return }
        val empty = (Build.VERSION.SDK_INT >= 26 && e.isShowingHintText) || e.text.isNullOrBlank()
        if (empty) { toast("入力欄が空です"); return }
        val er = Rect(); e.getBoundsInScreen(er)
        var cand: AccessibilityNodeInfo? = null
        var candRight = -1
        walk(root, 0) { n ->
            if (!n.isVisibleToUser || !n.isClickable || !n.isEnabled || n.isEditable) return@walk
            n.getBoundsInScreen(r)
            if (r.top >= er.top - dp(10) && r.top < er.bottom + dp(90) && r.width() < dp(80) && r.right > candRight) {
                candRight = r.right; cand = n
            }
        }
        if (cand?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) toast("送信ボタンが見つかりません")
    }
}
