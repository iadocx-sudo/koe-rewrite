package jp.himmel.koerewrite

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Quick Settings tile: toggles the floating button ON/OFF (and wakes the service). */
class WakeTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val prefs = Prefs(this)
        prefs.floatButton = !prefs.floatButton
        ScreenTextService.instance?.refreshBubble()
        update()
        val msg = when {
            ScreenTextService.instance == null -> "ユーザー補助の「こえリライト 画面読み取り」がOFFです"
            prefs.floatButton -> "こえリライト ON"
            else -> "こえリライト OFF"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun update() {
        val t = qsTile ?: return
        val on = Prefs(this).floatButton
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "こえリライト"
        try { t.subtitle = if (on) "ON" else "OFF" } catch (_: Throwable) {}
        t.updateTile()
    }
}
