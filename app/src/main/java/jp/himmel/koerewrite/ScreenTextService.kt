package jp.himmel.koerewrite

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Reads visible text of the app behind the keyboard, only when asked. */
class ScreenTextService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ScreenTextService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

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
