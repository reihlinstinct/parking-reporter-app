package il.sidewalks.reporter

import android.content.Intent
import android.service.quicksettings.TileService

/**
 * Quick Settings tile (issue #149 phase 1): one tap starts a new report,
 * independent of the device or assistant language. Label and icon come from the
 * manifest declaration; the tile carries no state of its own.
 */
class ReportTileService : TileService() {
    override fun onClick() {
        super.onClick()
        @Suppress("DEPRECATION")
        startActivityAndCollapse(
            Intent(this, MainActivity::class.java)
                .setAction(EntryIntents.ACTION_TILE_NEW_REPORT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
