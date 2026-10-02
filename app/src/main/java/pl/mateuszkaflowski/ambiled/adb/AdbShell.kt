package pl.mateuszkaflowski.ambiled.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import com.flyfishxu.kadb.shell.AdbShellResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import java.io.File

/**
 * Runs shell commands with adb's `shell` uid by connecting to this device's own wireless
 * debugging, like Shizuku does. Needs a one-time pairing ([pair]); with WRITE_SECURE_SETTINGS
 * (granted once over adb) wireless debugging is switched on only while it's needed.
 *
 * Every new connection makes the system post a "Wireless debugging connected" notification on
 * a channel users can't silence (sound and vibration). So while the LED service runs the
 * connection is kept open ([setKeepAlive]); otherwise it's closed [IDLE_CLOSE_MS] after the last
 * command, so e.g. recognising a game and syncing its colors still share a single one.
 */
object AdbShell {

    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val PREFS = "adb_shell"
    private const val KEY_ENABLED_BY_US = "wireless_debugging_enabled_by_us"
    private const val DISCOVERY_TIMEOUT_MS = 10_000L
    private const val PORT_POLL_INTERVAL_MS = 200L
    private const val IDLE_CLOSE_MS = 15_000L

    // adbd listens on all interfaces; loopback avoids a round trip through the Wi-Fi stack.
    private const val LOCALHOST = "127.0.0.1"

    @Volatile
    private var configured = false

    // Guards the shared connection; one command at a time.
    private val lock = Mutex()
    private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: Kadb? = null
    private var enabledWirelessDebugging = false
    private var idleClose: Job? = null

    @Volatile
    private var keepAlive = false

    /**
     * Keeps the connection (and wireless debugging, if we switched it on) open between commands
     * until turned off again, which closes it.
     */
    fun setKeepAlive(context: Context, keep: Boolean) {
        keepAlive = keep
        if (!keep) idleScope.launch { lock.withLock { closeConnection(context) } }
    }

    /** Pairs with the code from Settings > Developer options > Wireless debugging > Pair device. */
    suspend fun pair(context: Context, pairingCode: String) {
        configure(context)
        val port = AdbServiceDiscovery.findOwnPort(context, AdbService.PAIRING, DISCOVERY_TIMEOUT_MS)
        Kadb.pair(LOCALHOST, port, pairingCode)
    }

    suspend fun run(context: Context, command: String): AdbShellResponse = withShell(context) { it.shell(command) }

    /** Runs [block] over the shared connection, opening it (and wireless debugging) if needed. */
    suspend fun <T> withShell(context: Context, block: (Kadb) -> T): T = withContext(Dispatchers.IO) {
        configure(context)
        lock.withLock {
            idleClose?.cancel()
            try {
                val reused = connection
                if (reused != null) {
                    try {
                        return@withLock block(reused)
                    } catch (e: Exception) {
                        // Dropped meanwhile (adbd restarted, debugging switched off): start over once.
                        closeConnection(context)
                    }
                }
                block(openConnection(context))
            } catch (e: Exception) {
                closeConnection(context)
                throw e
            } finally {
                if (!keepAlive) {
                    idleClose = idleScope.launch {
                        delay(IDLE_CLOSE_MS)
                        lock.withLock { closeConnection(context) }
                    }
                }
            }
        }
    }

    private suspend fun openConnection(context: Context): Kadb {
        if (!isWirelessDebuggingEnabled(context) && canToggleWirelessDebugging(context)) {
            // Written synchronously so a kill right after enabling still leaves the marker.
            prefs(context).edit().putBoolean(KEY_ENABLED_BY_US, true).commit()
            setWirelessDebugging(context, true)
            enabledWirelessDebugging = true
        }
        return Kadb.create(LOCALHOST, waitForTlsPort()).also { connection = it }
    }

    /** Must be called holding [lock]. */
    private fun closeConnection(context: Context) {
        connection?.let { runCatching { it.close() } }
        connection = null
        // Don't leave the adb port open longer than needed.
        if (enabledWirelessDebugging) {
            setWirelessDebugging(context, false)
            prefs(context).edit().remove(KEY_ENABLED_BY_US).apply()
            enabledWirelessDebugging = false
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Switches wireless debugging back off if we turned it on and then got killed before we
     * could (e.g. by the low memory killer during a heavy game). Call on app start.
     */
    fun cleanUpAfterKill(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_ENABLED_BY_US, false)) return
        if (canToggleWirelessDebugging(context)) setWirelessDebugging(context, false)
        prefs.edit().remove(KEY_ENABLED_BY_US).apply()
    }

    /** Quotes [value] for `sh`, e.g. ROM names like "Luigi's Mansion". */
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    fun isWirelessDebuggingEnabled(context: Context) =
        Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    /** Needs WRITE_SECURE_SETTINGS; see [canToggleWirelessDebugging]. */
    fun setWirelessDebugging(context: Context, enabled: Boolean) {
        Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, if (enabled) 1 else 0)
    }

    fun canToggleWirelessDebugging(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * The Thor's adbd doesn't announce `_adb-tls-connect` over mDNS (only pairing), so the
     * port comes from the property adbd sets once its TLS server is up.
     */
    private suspend fun waitForTlsPort(): Int = withTimeout(DISCOVERY_TIMEOUT_MS) {
        while (true) {
            readTlsPort()?.let { return@withTimeout it }
            delay(PORT_POLL_INTERVAL_MS)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun readTlsPort(): Int? = runCatching {
        ProcessBuilder("getprop", "service.adb.tls.port").start()
            .inputStream.bufferedReader().use { it.readText() }
            .trim().toIntOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    private fun configure(context: Context) {
        if (configured) return
        synchronized(this) {
            if (configured) return
            val keyFile = File(context.noBackupFilesDir, "adb_private_key.pem")
            KadbCert.configure(OkioFilePrivateKeyStore(keyFile.absolutePath.toPath()))
            configured = true
        }
    }
}
