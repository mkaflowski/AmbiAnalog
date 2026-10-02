package pl.mateuszkaflowski.ambiled.service

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import pl.mateuszkaflowski.ambiled.MainActivity

/**
 * Target of long-pressing the Quick Settings tile. SystemUI starts it on the screen the shade
 * is on (the Thor's top screen); this reopens the app on the bottom screen instead, so it
 * doesn't cover the game. Has no UI of its own.
 */
class TilePreferencesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = bottomDisplayId()?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        runCatching { startActivity(app, options) }.onFailure { startActivity(app) }
        finish()
    }

    /** The Thor's bottom screen is registered as a presentation display. */
    private fun bottomDisplayId(): Int? =
        getSystemService(DisplayManager::class.java)
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.state == Display.STATE_ON }
            ?.displayId
}
