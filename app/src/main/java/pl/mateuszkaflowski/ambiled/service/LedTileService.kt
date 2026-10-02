package pl.mateuszkaflowski.ambiled.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import pl.mateuszkaflowski.ambiled.MainActivity
import pl.mateuszkaflowski.ambiled.R
import pl.mateuszkaflowski.ambiled.led.LedRepository

/**
 * Quick Settings toggle for the LEDs: off dims them to zero, on lights them in the current mode.
 * Also brings the service back after it was killed (the launcher force-stops apps swiped from
 * recents, heavy games get it killed for memory): the tile then shows as off, and a tap starts
 * it again. Long-pressing the tile opens the app.
 */
class LedTileService : TileService() {

    override fun onStartListening() {
        updateTile(isLit())
    }

    override fun onClick() {
        val turnOn = !isLit()
        if (turnOn) {
            try {
                LedControl.setLightsOn(this, true)
            } catch (e: IllegalStateException) {
                // Android may refuse a foreground service start from here; the app can start it.
                Log.w(TAG, "Service start from the tile refused, opening the app", e)
                openApp()
            }
        } else {
            LedControl.setLightsOn(this, false)
        }
        updateTile(turnOn)
    }

    private fun isLit(): Boolean {
        val state = LedRepository.state.value
        return LedService.isRunning && state.enabled && !state.lightsOff
    }

    private fun updateTile(lit: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (lit) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(if (lit) R.string.tile_on else R.string.tile_off)
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") // The PendingIntent overload is API 34+.
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val TAG = "LedTileService"

        /** Asks the system to refresh the tile, e.g. when the service starts or stops. */
        fun refresh(context: Context) {
            requestListeningState(context, ComponentName(context, LedTileService::class.java))
        }
    }
}
