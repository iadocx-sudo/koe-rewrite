package jp.himmel.koerewrite

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
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
import java.io.ByteArrayOutputStream
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
    private var bubble: View? = null
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

    private val updaterLate1 = Runnable { updateBubble() }
    private val updaterLate2 = Runnable { updateBubble() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        main.removeCallbacks(updater)
        main.postDelayed(updater, 120)
        main.removeCallbacks(updaterLate1)
        main.postDelayed(updaterLate1, 600)
        main.removeCallbacks(updaterLate2)
        main.postDelayed(updaterLate2, 1500)
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
        // keep checking while shown so it reliably follows / hides with the keyboard
        main.removeCallbacks(updater)
        main.postDelayed(updater, 1500)
    }

    private fun bubbleW() = dp(58)
    private fun bubbleH() = dp(86)

    private fun positionLp(lp: WindowManager.LayoutParams) {
        val screenW = resources.displayMetrics.widthPixels
        val right = if (prefs.bubbleRight >= 0) prefs.bubbleRight else dp(10)
        val above = if (prefs.bubbleAbove >= 0) prefs.bubbleAbove else dp(70)
        lp.x = (screenW - right - bubbleW()).coerceIn(0, screenW - bubbleW())
        lp.y = (kbTop - above - bubbleH()).coerceAtLeast(0)
    }

    /** One button: big ✨ (tap = rewrite), small "長押し🎤" strip (long press = voice). */
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
        val lp = WindowManager.LayoutParams(
            bubbleW(), bubbleH(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionLp(lp)

        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#FA8072"))
                setStroke(dp(2), Color.WHITE)
            }
        }
        val rw = TextView(this).apply {
            text = "✨"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        val div = View(this).apply { setBackgroundColor(Color.parseColor("#CCFFFFFF")) }
        val hint = TextView(this).apply {
            text = "長押し🎤"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        box.addView(rw, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        box.addView(div, android.widget.LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(8); rightMargin = dp(8) })
        box.addView(hint, android.widget.LinearLayout.LayoutParams(-1, dp(28)))

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longFired = false
        val longPress = Runnable {
            if (!moved) {
                longFired = true
                box.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                box.alpha = 1f
                switchToVoice()
            }
        }
        box.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    moved = false; longFired = false
                    box.alpha = 0.7f
                    main.postDelayed(longPress, 450)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > dp(10) || abs(dy) > dp(10))) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    if (moved && !longFired) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt().coerceAtLeast(0)
                        try { w.updateViewLayout(box, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    box.alpha = 1f
                    if (longFired) {
                        // already handled
                    } else if (moved) {
                        val screenW = resources.displayMetrics.widthPixels
                        prefs.bubbleRight = (screenW - lp.x - bubbleW()).coerceAtLeast(0)
                        prefs.bubbleAbove = (kbTop - lp.y - bubbleH()).coerceAtLeast(0)
                    } else onBubbleTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(longPress); box.alpha = 1f; true }
                else -> false
            }
        }
        try {
            w.addView(box, lp)
            bubble = box
            bubbleLp = lp
            rewriteBtn = rw
        } catch (e: Exception) {
            bubble = null
            bubbleLp = null
        }
    }

    private fun hideBubble() {
        val v = bubble ?: return
        bubble = null
        bubbleLp = null
        rewriteBtn = null
        try { wm?.removeView(v) } catch (_: Exception) {}
    }

    // ---- tap: rewrite the whole input field in place ----

    private var busy = false
    private var undoOriginal: String? = null
    private var undoRewritten: String? = null
    private var undoUntil = 0L

    private var rewriteBtn: TextView? = null

    private fun setBubbleLook(label: String, color: String) {
        val v = rewriteBtn ?: return
        v.text = label
        (bubble?.background as? GradientDrawable)?.setColor(Color.parseColor(color))
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val n = try { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (e: Exception) { null }
        return if (n != null && n.isEditable) n else null
    }

    private fun fieldText(n: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return ""
        return n.text?.toString() ?: ""
    }

    private fun setFieldText(n: AccessibilityNodeInfo, text: String): Boolean {
        val b = android.os.Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
        if (ok) {
            val sel = android.os.Bundle()
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun onBubbleTap() {
        if (busy) return
        val node = focusedEditable()
        val text = node?.let { fieldText(it) }.orEmpty()
        if (node == null || text.isBlank()) {
            toast("入力欄に文章を書いてから✨をタップ（長押しで音声入力）")
            return
        }
        // undo within 15s
        val now = System.currentTimeMillis()
        if (undoOriginal != null && now < undoUntil && text == undoRewritten) {
            setFieldText(node, undoOriginal!!)
            undoOriginal = null
            toast("元に戻しました")
            return
        }
        if (prefs.apiKey.isBlank()) { toast("こえリライトの設定でAPIキーを登録してください"); return }

        busy = true
        setBubbleLook("…", "#BDBDBD")
        val screen = if (prefs.useScreen) try { captureText(2500) } catch (e: Exception) { null } else null
        val apiKey = prefs.apiKey
        val model = prefs.model
        val extra = prefs.extraRule
        val t0 = System.currentTimeMillis()
        Thread {
            val result = try {
                Result.success(Gemini.rewriteText(apiKey, model, text, screen, extra))
            } catch (e: Throwable) { Result.failure(e) }
            main.post {
                busy = false
                setBubbleLook("✨", "#FA8072")
                result.onSuccess { out ->
                    val target = focusedEditable() ?: node
                    if (setFieldText(target, out)) {
                        undoOriginal = text
                        undoRewritten = out
                        undoUntil = System.currentTimeMillis() + 15000
                        prefs.lastError = ""
                        toast("リライトしました（${(System.currentTimeMillis() - t0) / 1000}秒）。15秒以内にもう一度タップで元に戻す")
                    } else {
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("rewrite", out))
                        toast("この欄には直接入れられないため、コピーしました。貼り付けてください")
                    }
                }
                result.onFailure {
                    prefs.lastError = it.message ?: it.javaClass.simpleName
                    toast("失敗: ${it.message}")
                }
            }
        }.start()
    }

    /** long press: switch to the voice keyboard */
    private fun switchToVoice() {
        val id = ourImeId()
        if (id == null) {
            toast("こえリライトのキーボードを有効にしてください")
            return
        }
        var ok = false
        if (Build.VERSION.SDK_INT >= 30) {
            ok = try { softKeyboardController.switchToInputMethod(id) } catch (e: Exception) { false }
        }
        if (ok) hideBubble()
        else try { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() } catch (_: Exception) {}
    }

    // ---------------- screenshot ----------------

    /** Takes a screenshot (Android 11+), returns JPEG bytes (width <= 720) or null. Callback on main thread. */
    fun takeShot(cb: (ByteArray?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    Thread {
                        val bytes = try {
                            val hb = result.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close()
                            if (sw == null) null else {
                                val scaled = if (sw.width > 720)
                                    Bitmap.createScaledBitmap(sw, 720, (sw.height * 720f / sw.width).toInt(), true)
                                else sw
                                val out = ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                                out.toByteArray()
                            }
                        } catch (e: Throwable) { null }
                        main.post { cb(bytes) }
                    }.start()
                }

                override fun onFailure(errorCode: Int) {
                    main.post { cb(null) }
                }
            })
        } catch (e: Throwable) {
            cb(null)
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
