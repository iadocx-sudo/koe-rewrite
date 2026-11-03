package jp.himmel.koerewrite

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 1) Reads visible text of the app behind the keyboard, only when the IME asks.
 * 2) Shows a floating mic bubble while another keyboard (Gboard) is open;
 *    tapping it switches directly to こえリライト.
 */
class ScreenTextService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ScreenTextService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private lateinit var prefs: Prefs
    private var kbTop = 0
    private val updater = Runnable { updateBubble() }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onDestroy() {
        hideBubble()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val t = event?.eventType ?: return
        if (t == AccessibilityEvent.TYPE_WINDOWS_CHANGED || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            main.removeCallbacks(updater)
            main.postDelayed(updater, 120)
        }
    }

    override fun onInterrupt() {}

    // ---------------- floating bubble ----------------

    fun refreshBubble() {
        main.removeCallbacks(updater)
        main.post(updater)
    }

    private fun ourImeId(): String? {
        val imm = getSystemService(InputMethodManager::class.java) ?: return null
        return imm.enabledInputMethodList.firstOrNull { it.packageName == packageName }?.id
    }

    private fun updateBubble() {
        if (!::prefs.isInitialized || !prefs.floatButton) { hideBubble(); return }
        val wins = try { windows } catch (e: Exception) { emptyList<AccessibilityWindowInfo>() }
        val ime = wins.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (ime == null) { hideBubble(); return }
        val cur = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        if (cur.startsWith(packageName)) { hideBubble(); return }
        val r = Rect()
        ime.getBoundsInScreen(r)
        if (r.height() < dp(80)) { hideBubble(); return }
        kbTop = r.top
        showBubble()
    }

    private fun positionLp(lp: WindowManager.LayoutParams) {
        val size = dp(52)
        val screenW = resources.displayMetrics.widthPixels
        val right = if (prefs.bubbleRight >= 0) prefs.bubbleRight else dp(10)
        val above = if (prefs.bubbleAbove >= 0) prefs.bubbleAbove else dp(76)
        lp.x = (screenW - right - size).coerceIn(0, screenW - size)
        lp.y = (kbTop - above - size).coerceAtLeast(0)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val w = wm ?: return
        val existing = bubble
        val lp0 = bubbleLp
        if (existing != null && lp0 != null) {
            positionLp(lp0)
            try { w.updateViewLayout(existing, lp0) } catch (_: Exception) {}
            return
        }
        val size = dp(52)
        val v = TextView(this).apply {
            text = "🎤"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FA8072"))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(6).toFloat()
            alpha = 0.95f
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionLp(lp)

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) moved = true
                    if (moved) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt().coerceAtLeast(0)
                        try { w.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val screenW = resources.displayMetrics.widthPixels
                        prefs.bubbleRight = (screenW - lp.x - size).coerceAtLeast(0)
                        prefs.bubbleAbove = (kbTop - lp.y - size).coerceAtLeast(0)
                    } else {
                        onBubbleTap()
                    }
                    true
                }
                else -> false
            }
        }
        try {
            w.addView(v, lp)
            bubble = v
            bubbleLp = lp
        } catch (e: Exception) {
            bubble = null
            bubbleLp = null
        }
    }

    private fun hideBubble() {
        val v = bubble ?: return
        bubble = null
        bubbleLp = null
        try { wm?.removeView(v) } catch (_: Exception) {}
    }

    private fun onBubbleTap() {
        val id = ourImeId()
        if (id == null) {
            Toast.makeText(this, "こえリライトのキーボードを有効にしてください", Toast.LENGTH_SHORT).show()
            return
        }
        var ok = false
        if (Build.VERSION.SDK_INT >= 30) {
            ok = try { softKeyboardController.switchToInputMethod(id) } catch (e: Exception) { false }
        }
        if (ok) {
            hideBubble()
        } else {
            try {
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            } catch (_: Exception) {}
        }
    }

    // ---------------- screen text ----------------

    fun captureText(maxChars: Int = 4000): String? {
        val root = findAppRoot() ?: return null
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val lines = ArrayList<String>()
        val rect = Rect()
        walk(root, 0) { node ->
            if (node.isEditable) return@walk
            if (!node.isVisibleToUser) return@walk
            val t = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (t.isEmpty() || t.length > 1500) return@walk
            node.getBoundsInScreen(rect)
            val cx = rect.centerX().toFloat() / width
            val side = when {
                rect.width() > width * 0.8f -> "[中]"
                cx < 0.45f -> "[左]"
                cx > 0.55f -> "[右]"
                else -> "[中]"
            }
            val line = "$side $t"
            if (lines.isEmpty() || lines.last() != line) lines.add(line)
        }
        if (lines.isEmpty()) return null
        val sb = StringBuilder()
        for (i in lines.indices.reversed()) {
            if (sb.length + lines[i].length + 1 > maxChars) break
            sb.insert(0, lines[i] + "\n")
        }
        return sb.toString().trim()
    }

    private fun findAppRoot(): AccessibilityNodeInfo? {
        val wins = try { windows } catch (e: Exception) { emptyList<AccessibilityWindowInfo>() }
        val apps = wins.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val target = apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused } ?: apps.firstOrNull()
        return target?.root ?: try { rootInActiveWindow } catch (e: Exception) { null }
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > 60) return
        visit(node)
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), depth + 1, visit)
        }
    }
}
