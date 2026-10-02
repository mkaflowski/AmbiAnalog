package pl.mateuszkaflowski.ambiled

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import pl.mateuszkaflowski.ambiled.adb.ExtendedMode
import pl.mateuszkaflowski.ambiled.ambient.AmbientStatus
import pl.mateuszkaflowski.ambiled.game.GameDetector
import pl.mateuszkaflowski.ambiled.game.GameProfiles
import pl.mateuszkaflowski.ambiled.game.GameStatus
import pl.mateuszkaflowski.ambiled.led.LedRepository
import pl.mateuszkaflowski.ambiled.service.LedControl
import pl.mateuszkaflowski.ambiled.service.LedService
import pl.mateuszkaflowski.ambiled.ui.ExtendedModeScreen
import pl.mateuszkaflowski.ambiled.ui.LedMode
import pl.mateuszkaflowski.ambiled.ui.LedScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by LedRepository.state.collectAsState()
            val userPresets by LedRepository.userPresets.collectAsState()
            val ambientRunning by AmbientStatus.running.collectAsState()
            val ambientColors = AmbientStatus.colors.collectAsState()
            // The service notification is optional; the LEDs work either way.
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {}
            val screenCaptureConsent = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val data = result.data
                if (result.resultCode == Activity.RESULT_OK && data != null) {
                    LedService.startAmbient(this, result.resultCode, data)
                }
            }

            val extendedModePaired by ExtendedMode.paired.collectAsState()
            val currentGame by GameStatus.current.collectAsState()
            // Granted outside the app (Settings or the shell), so re-check on every return.
            var usageAccess by remember { mutableStateOf(GameDetector.hasUsageAccess(this)) }
            LifecycleResumeEffect(Unit) {
                usageAccess = GameDetector.hasUsageAccess(this@MainActivity)
                onPauseOrDispose { }
            }
            val scope = rememberCoroutineScope()
            fun requestUsageAccess() {
                if (extendedModePaired) {
                    scope.launch {
                        runCatching { ExtendedMode.grantUsageAccess(this@MainActivity) }
                        usageAccess = GameDetector.hasUsageAccess(this@MainActivity)
                    }
                } else {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
            var showExtendedMode by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = showExtendedMode) { showExtendedMode = false }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    if (showExtendedMode) {
                        ExtendedModeScreen(onBack = { showExtendedMode = false })
                        return@Surface
                    }
                    LedScreen(
                        state = state,
                        userPresets = userPresets,
                        ambientRunning = ambientRunning,
                        ambientColors = ambientColors,
                        onEnabledChange = { enabled ->
                            if (enabled) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            setEnabled(enabled)
                        },
                        onModeChange = { mode ->
                            if (mode != LedMode.STATIC && !state.enabled) setEnabled(true)
                            if (ambientRunning) LedService.stopAmbient(this)
                            LedRepository.update { it.copy(gameMode = mode == LedMode.GAME) }
                            when (mode) {
                                LedMode.AMBIENT -> {
                                    val manager = getSystemService(MediaProjectionManager::class.java)
                                    screenCaptureConsent.launch(manager.createScreenCaptureIntent())
                                }
                                LedMode.GAME -> if (!usageAccess && extendedModePaired) requestUsageAccess()
                                LedMode.STATIC -> Unit
                            }
                        },
                        currentGame = currentGame,
                        usageAccess = usageAccess,
                        onGameColorsChange = GameProfiles::setCustom,
                        onRequestUsageAccess = ::requestUsageAccess,
                        onStateChange = LedRepository::update,
                        onSavePreset = LedRepository::savePreset,
                        onDeletePreset = LedRepository::deletePreset,
                        extendedModePaired = extendedModePaired,
                        onOpenExtendedMode = { showExtendedMode = true },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Recover after a force-stop or crash, when the service is gone but control is on.
        if (LedRepository.state.value.enabled) LedService.start(this)
    }

    private fun setEnabled(enabled: Boolean) = LedControl.setEnabled(this, enabled)
}
