package jp.himmel.koerewrite

import android.service.quicksettings.TileService
import android.widget.Toast

/** Quick Settings tile: tapping it starts the app process, which revives the ✨ button service. */
class WakeTile : TileService() {
    override fun onClick() {
        super.onClick()
        val svc = ScreenTextService.instance
        svc?.refreshBubble()
        Toast.makeText(
            this,
            if (svc != null) "こえリライトは稼働中です" else "こえリライトを起動しました（数秒で✨ボタンが出ます）",
            Toast.LENGTH_SHORT
        ).show()
    }
}
