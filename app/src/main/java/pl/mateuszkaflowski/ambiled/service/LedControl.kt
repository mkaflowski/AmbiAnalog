package pl.mateuszkaflowski.ambiled.service

import android.content.Context
import pl.mateuszkaflowski.ambiled.led.LedRepository
import pl.mateuszkaflowski.ambiled.led.ThorLeds

/** Turning app control and the LEDs on and off, shared by the app and the Quick Settings tile. */
object LedControl {

    /**
     * Dims the LEDs to zero, or lights them again in the current mode. Unlike disabling
     * control, the app keeps the LEDs (and keeps them dark when OdinSettings re-applies its
     * state). Turning on also brings the service back if it was killed.
     */
    fun setLightsOn(context: Context, on: Boolean) {
        LedRepository.update { it.copy(enabled = it.enabled || on, lightsOff = !on) }
        if (on) LedService.start(context)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        LedRepository.update { it.copy(enabled = enabled) }
        if (enabled) {
            LedService.start(context)
        } else {
            // The service stops itself on the state change; give the LEDs back to the system.
            runCatching { ThorLeds.restoreSystem(context) }
        }
    }
}
