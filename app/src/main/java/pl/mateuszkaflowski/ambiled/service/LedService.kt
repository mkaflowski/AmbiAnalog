package pl.mateuszkaflowski.ambiled.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import pl.mateuszkaflowski.ambiled.MainActivity
import pl.mateuszkaflowski.ambiled.R
import pl.mateuszkaflowski.ambiled.adb.AdbShell
import pl.mateuszkaflowski.ambiled.adb.ExtendedMode
import pl.mateuszkaflowski.ambiled.ambient.AmbientCapture
import pl.mateuszkaflowski.ambiled.game.GameDetector
import pl.mateuszkaflowski.ambiled.game.GameStatus
import pl.mateuszkaflowski.ambiled.led.LedFader
import pl.mateuszkaflowski.ambiled.led.LedOutput
import pl.mateuszkaflowski.ambiled.led.LedRepository
import pl.mateuszkaflowski.ambiled.led.ThorLeds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps our colors on the joysticks: the static colors from [LedRepository], the current
 * game's colors in game mode ([GameDetector]), or, while an [AmbientCapture] runs, colors
 * taken from the top screen.
 *
 * OdinSettings overwrites the LEDs with its own state on screen on (after ~500 ms) and whenever
 * its Settings.System keys change, so we re-apply shortly after each of those.
 */
class LedService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val reapply = Runnable { applyCurrent() }
    private var ambient: AmbientCapture? = null
    private val fader by lazy { LedFader(scope) }
    private val gameDetector by lazy { GameDetector(this, scope) }
    private var destroyed = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val screenOn = intent.action == Intent.ACTION_SCREEN_ON
            ambient?.screenOn = screenOn
            if (screenOn) scheduleReapply(SCREEN_ON_DELAY_MS)
        }
    }

    private val systemSettingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            scheduleReapply(SETTINGS_CHANGE_DELAY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground(ambient = false)
        isRunning = true
        LedTileService.refresh(this)
        // One adb connection (and one "Wireless debugging connected" notification) per run.
        AdbShell.setKeepAlive(this, true)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        ThorLeds.SYSTEM_KEYS.forEach {
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(it), false, systemSettingsObserver
            )
        }
        scope.launch {
            LedRepository.state.collect { state ->
                if (!state.enabled) {
                    stopSelf()
                    return@collect
                }
                updateGameDetector()
                applyCurrent()
            }
        }
        // A new game fades in rather than switching at once.
        scope.launch { GameStatus.current.collect { applyCurrent(fade = true) } }
        scope.launch {
            LedRepository.state.map { it.lightsOff }.distinctUntilChanged().collect { LedTileService.refresh(this@LedService) }
        }
        syncToSystemSettings()
    }

    /**
     * In extended mode, mirrors what the sticks show into OdinSettings so it outlives us: the
     * static colors, or the current game's in game mode. Heavy games (BOTW in Eden takes ~6 of
     * the Thor's 7.4 GB) get us killed by the low memory killer; the game's colors then stay.
     */
    @OptIn(FlowPreview::class)
    private fun syncToSystemSettings() {
        scope.launch {
            combine(LedRepository.state, GameStatus.current, ExtendedMode.paired) { state, game, paired ->
                if (!paired) return@combine null
                val colors = game?.colors?.takeIf { state.gameMode }
                SystemLeds(colors?.left ?: state.left, colors?.right ?: state.right, state.brightness, !state.lightsOff)
            }
                .filterNotNull()
                .distinctUntilChanged()
                // Dragging the color or brightness slider would otherwise mean an adb round trip per step.
                .debounce(SYNC_DEBOUNCE_MS)
                .collect { ExtendedMode.syncColors(applicationContext, it.left, it.right, it.brightness, it.on) }
        }
    }

    private data class SystemLeds(val left: Int, val right: Int, val brightness: Float, val on: Boolean)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AMBIENT -> startAmbient(intent)
            ACTION_STOP_AMBIENT -> ambient?.stop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        isRunning = false
        LedTileService.refresh(this)
        AdbShell.setKeepAlive(this, false)
        ambient?.stop()
        gameDetector.stop()
        scope.cancel()
        handler.removeCallbacks(reapply)
        unregisterReceiver(screenReceiver)
        contentResolver.unregisterContentObserver(systemSettingsObserver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAmbient(intent: Intent) {
        if (ambient != null) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java) ?: return
        // The mediaProjection service type must be active before the projection is created.
        startInForeground(ambient = true)
        val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        if (projection == null) {
            startInForeground(ambient = false)
            return
        }
        ambient = AmbientCapture(projection, onStopped = ::onAmbientStopped).also {
            it.screenOn = getSystemService(PowerManager::class.java).isInteractive
            it.start()
        }
        updateGameDetector()
    }

    private fun onAmbientStopped() {
        ambient = null
        if (destroyed) return
        startInForeground(ambient = false)
        updateGameDetector()
        applyCurrent()
    }

    private fun scheduleReapply(delayMs: Long) {
        handler.removeCallbacks(reapply)
        handler.postDelayed(reapply, delayMs)
    }

    /** Game detection only runs in game mode, and ambient light takes precedence. */
    private fun updateGameDetector() {
        if (LedRepository.state.value.gameMode && ambient == null) gameDetector.start() else gameDetector.stop()
    }

    private fun applyCurrent(fade: Boolean = false) {
        val state = LedRepository.state.value
        if (!state.enabled) return
        if (state.lightsOff) {
            fader.release()
            // Also re-applied after OdinSettings lights them on screen on.
            runCatching { ThorLeds.turnOff() }.onFailure { Log.e(TAG, "Failed to write LEDs", it) }
            return
        }
        ambient?.let {
            fader.release()
            it.refresh()
            return
        }
        // OdinSettings turns the LEDs off with the screen; don't light them back up.
        if (!getSystemService(PowerManager::class.java).isInteractive) return
        // Game mode falls back to the static colors when no game is recognised.
        val game = GameStatus.current.value?.colors?.takeIf { state.gameMode }
        val target = LedOutput(game?.left ?: state.left, game?.right ?: state.right, state.brightness)
        if (fade) fader.fadeTo(target) else fader.set(target)
    }

    private fun startInForeground(ambient: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_led)
            .setContentIntent(openApp)
            .setOngoing(true)
        if (ambient) {
            val stop = PendingIntent.getService(
                this, 0, Intent(this, LedService::class.java).setAction(ACTION_STOP_AMBIENT),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder
                .setContentTitle(getString(R.string.service_notification_ambient))
                .addAction(Notification.Action.Builder(null, getString(R.string.stop_ambient), stop).build())
        } else {
            builder.setContentTitle(getString(R.string.service_notification_title))
        }

        var type = if (ambient) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.build(), type)
    }

    companion object {
        private const val TAG = "LedService"
        private const val CHANNEL_ID = "led_service"
        private const val NOTIFICATION_ID = 1
        private const val SCREEN_ON_DELAY_MS = 1000L
        private const val SETTINGS_CHANGE_DELAY_MS = 300L
        private const val SYNC_DEBOUNCE_MS = 800L

        private const val ACTION_START_AMBIENT = "start_ambient"
        private const val ACTION_STOP_AMBIENT = "stop_ambient"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        /** Whether the service is alive in this process; false after the process was killed. */
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LedService::class.java))
        }

        /** [resultCode] and [data] come from the MediaProjection consent screen. */
        fun startAmbient(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, LedService::class.java)
                    .setAction(ACTION_START_AMBIENT)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data)
            )
        }

        fun stopAmbient(context: Context) {
            context.startService(Intent(context, LedService::class.java).setAction(ACTION_STOP_AMBIENT))
        }
    }
}
