package pl.mateuszkaflowski.ambiled

import android.app.Application
import pl.mateuszkaflowski.ambiled.adb.AdbShell
import pl.mateuszkaflowski.ambiled.adb.ExtendedMode
import pl.mateuszkaflowski.ambiled.game.BundledGameColors
import pl.mateuszkaflowski.ambiled.game.GameProfiles
import pl.mateuszkaflowski.ambiled.led.LedRepository

class ThorLedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AdbShell.cleanUpAfterKill(this)
        LedRepository.init(this)
        ExtendedMode.init(this)
        GameProfiles.init(this)
        BundledGameColors.init(this)
    }
}
