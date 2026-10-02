package pl.mateuszkaflowski.ambiled.adb

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import pl.mateuszkaflowski.ambiled.led.LedState
import pl.mateuszkaflowski.ambiled.led.ThorLeds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * "Extended mode": with [AdbShell] paired, static colors are also written to the Settings.System
 * keys OdinSettings owns. OdinSettings then re-applies them itself on screen on and after a
 * reboot, so they survive the launcher force-stopping our app.
 */
object ExtendedMode {

    private const val TAG = "ExtendedMode"
    private const val KEY_PAIRED = "paired"

    sealed interface SyncResult {
        val atElapsedMs: Long

        data class Ok(override val atElapsedMs: Long) : SyncResult
        data class Failed(override val atElapsedMs: Long, val message: String) : SyncResult
    }

    private lateinit var prefs: SharedPreferences

    private val _paired = MutableStateFlow(false)
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    private val _lastSync = MutableStateFlow<SyncResult?>(null)
    val lastSync: StateFlow<SyncResult?> = _lastSync.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("extended_mode", Context.MODE_PRIVATE)
        _paired.value = prefs.getBoolean(KEY_PAIRED, false)
    }

    suspend fun pair(context: Context, code: String) {
        AdbShell.pair(context, code)
        setPaired(true)
    }

    /** Stops syncing; the device keeps our key until it's removed under Paired devices. */
    fun forget() {
        setPaired(false)
        _lastSync.value = null
    }

    suspend fun testConnection(context: Context): String = AdbShell.run(context, "id").allOutput.trim()

    /**
     * Writes the static colors to OdinSettings' keys. No-op until paired, or when the system
     * already holds these values, so that e.g. every service start doesn't cost an adb connection.
     */
    suspend fun syncColors(context: Context, state: LedState, force: Boolean = false) =
        syncColors(context, state.left, state.right, state.brightness, !state.lightsOff, force)

    suspend fun syncColors(
        context: Context,
        left: Int,
        right: Int,
        brightness: Float,
        on: Boolean,
        force: Boolean = false,
    ) {
        if (!_paired.value) return
        val wanted = mapOf(
            ThorLeds.KEY_COLORS to "${hex(left)},${hex(right)}",
            // Off in the system too, so OdinSettings keeps them dark if we get killed.
            ThorLeds.KEY_ENABLED to if (on) "1,1" else "0,0",
            ThorLeds.KEY_BRIGHTNESS to String.format(Locale.US, "%.4f", brightness),
        )
        val resolver = context.contentResolver
        if (!force && wanted.all { (key, value) -> Settings.System.getString(resolver, key) == value }) return
        val command = wanted.entries.joinToString(" && ") { (key, value) -> "settings put system $key '$value'" }
        val now = SystemClock.elapsedRealtime()
        _lastSync.value = runCatching {
            val response = AdbShell.run(context, command)
            check(response.exitCode == 0) { response.allOutput.trim() }
        }.fold(
            onSuccess = { SyncResult.Ok(now) },
            onFailure = {
                Log.w(TAG, "Color sync failed", it)
                SyncResult.Failed(now, it.message ?: it.javaClass.simpleName)
            },
        )
    }

    fun canToggleWirelessDebugging(context: Context) = AdbShell.canToggleWirelessDebugging(context)

    /** Game mode needs usage access; the shell can grant it without a trip to Settings. */
    suspend fun grantUsageAccess(context: Context) {
        AdbShell.run(context, "appops set ${context.packageName} GET_USAGE_STATS allow")
    }

    /** Whether `appops set <pkg> PROJECT_MEDIA allow` was run, so ambient light needs no consent. */
    fun projectionPreapproved(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow("android:project_media", Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun setPaired(paired: Boolean) {
        _paired.value = paired
        prefs.edit().putBoolean(KEY_PAIRED, paired).apply()
    }

    private fun hex(color: Int) = String.format("#%08x", color)
}
