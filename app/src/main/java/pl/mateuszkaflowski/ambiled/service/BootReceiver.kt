package pl.mateuszkaflowski.ambiled.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import pl.mateuszkaflowski.ambiled.led.LedRepository

/** Restarts [LedService] after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (LedRepository.state.value.enabled) LedService.start(context)
    }
}
