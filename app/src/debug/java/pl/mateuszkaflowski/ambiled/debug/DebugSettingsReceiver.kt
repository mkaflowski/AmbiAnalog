package pl.mateuszkaflowski.ambiled.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import pl.mateuszkaflowski.ambiled.adb.AdbShell
import pl.mateuszkaflowski.ambiled.adb.ExtendedMode
import pl.mateuszkaflowski.ambiled.led.LedRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug builds only: lets measurements change settings over adb while another app covers ours.
 * `adb shell am broadcast -n pl.mateuszkaflowski.ambiled/.debug.DebugSettingsReceiver --ei ambient_fps 5`
 * `... --ei adb_wifi 1` toggles wireless debugging (needs WRITE_SECURE_SETTINGS).
 * `... --es adb_pair 123456` pairs [AdbShell]; `... --es adb_cmd "id"` runs a command through it.
 */
private const val TAG = "ThorLedDebug"

class DebugSettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("ambient_fps")) {
            val fps = intent.getIntExtra("ambient_fps", 20)
            LedRepository.update { it.copy(ambientFps = fps) }
        }
        if (intent.hasExtra("adb_wifi")) {
            val enabled = intent.getIntExtra("adb_wifi", 0)
            runCatching { Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", enabled) }
                .onSuccess { Log.i(TAG, "adb_wifi_enabled <- $enabled: $it") }
                .onFailure { Log.e(TAG, "adb_wifi_enabled <- $enabled failed", it) }
        }
        intent.getStringExtra("adb_pair")?.let { code ->
            runAsync("adb_pair") { ExtendedMode.pair(context, code); "paired" }
        }
        intent.getStringExtra("adb_cmd")?.let { command ->
            runAsync("adb_cmd '$command'") { AdbShell.run(context, command).toString() }
        }
    }

    private fun runAsync(name: String, block: suspend () -> String) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val start = SystemClock.elapsedRealtime()
            runCatching { block() }
                .onSuccess { Log.i(TAG, "$name OK in ${SystemClock.elapsedRealtime() - start} ms: $it") }
                .onFailure { Log.e(TAG, "$name failed after ${SystemClock.elapsedRealtime() - start} ms", it) }
            pending.finish()
        }
    }
}
