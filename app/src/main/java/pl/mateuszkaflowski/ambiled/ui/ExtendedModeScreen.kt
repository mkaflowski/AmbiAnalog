package pl.mateuszkaflowski.ambiled.ui

import pl.mateuszkaflowski.ambiled.R
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import pl.mateuszkaflowski.ambiled.adb.AdbShell
import pl.mateuszkaflowski.ambiled.adb.ExtendedMode
import pl.mateuszkaflowski.ambiled.led.LedRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

private val OkColor = Color(0xFF7BD88F)
private val MissingColor = Color(0xFFFFB86C)

@Composable
fun ExtendedModeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val paired by ExtendedMode.paired.collectAsState()
    val lastSync by ExtendedMode.lastSync.collectAsState()
    val controlEnabled by LedRepository.state.collectAsState()

    // Grants are made over adb outside the app, so re-check whenever we come back to it.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val canToggle = remember(refresh) { ExtendedMode.canToggleWirelessDebugging(context) }
    val projectionPreapproved = remember(refresh) { ExtendedMode.projectionPreapproved(context) }

    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.extended_mode), style = MaterialTheme.typography.titleLarge)
            }
            Text(
                stringResource(R.string.extended_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StepCard(
                title = stringResource(R.string.step_toggle_title),
                done = canToggle,
                doneText = stringResource(R.string.step_toggle_done),
            ) {
                Text(
                    stringResource(R.string.step_toggle_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CommandBox("adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
            }

            PairingCard(paired = paired, canToggle = canToggle)

            if (paired) {
                SyncStatus(lastSync, controlEnabled.enabled)
            }

            StepCard(
                title = stringResource(R.string.projection_title),
                done = projectionPreapproved,
                doneText = stringResource(R.string.projection_done),
            ) {
                Text(
                    stringResource(R.string.projection_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CommandBox("adb shell appops set ${context.packageName} PROJECT_MEDIA allow")
            }
        }
    }
}

@Composable
private fun PairingCard(paired: Boolean, canToggle: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var wirelessOn by remember { mutableStateOf(AdbShell.isWirelessDebuggingEnabled(context)) }
    // Only turn it back off after pairing if we were the ones who turned it on.
    var enabledForPairing by remember { mutableStateOf(false) }

    StepCard(
        title = stringResource(R.string.step_pair_title),
        done = paired,
        doneText = stringResource(R.string.step_pair_done),
        showContentWhenDone = true,
    ) {
        if (!paired) {
            Text(stringResource(R.string.step_pair_a), style = MaterialTheme.typography.bodyMedium)
            if (canToggle) {
                Button(
                    enabled = !wirelessOn,
                    onClick = {
                        AdbShell.setWirelessDebugging(context, true)
                        wirelessOn = true
                        enabledForPairing = true
                    },
                ) { Text(if (wirelessOn) stringResource(R.string.wireless_on) else stringResource(R.string.turn_on)) }
                Text(
                    stringResource(R.string.step_pair_network),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.step_pair_b),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { openDeveloperSettingsOnOtherDisplay(context) }) {
                Text(stringResource(R.string.open_dev_options))
            }
            Text(stringResource(R.string.step_pair_c), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.width(160.dp),
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    enabled = code.length == 6 && !busy,
                    onClick = {
                        busy = true
                        message = context.getString(R.string.pairing_progress)
                        scope.launch {
                            message = runCatching {
                                ExtendedMode.pair(context, code)
                                if (enabledForPairing) AdbShell.setWirelessDebugging(context, false)
                                val state = LedRepository.state.value
                                if (state.enabled) ExtendedMode.syncColors(context, state, force = true)
                                context.getString(R.string.paired)
                            }.getOrElse { pairingError(context, it) }
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.pair)) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = context.getString(R.string.connecting)
                        scope.launch {
                            message = runCatching { context.getString(R.string.connection_ok, ExtendedMode.testConnection(context)) }
                                .getOrElse { context.getString(R.string.connection_error, it.message ?: it.javaClass.simpleName) }
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.test_connection)) }
                TextButton(onClick = { ExtendedMode.forget(); message = null }) { Text(stringResource(R.string.forget_pairing)) }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SyncStatus(lastSync: ExtendedMode.SyncResult?, controlEnabled: Boolean) {
    val text = when {
        !controlEnabled -> stringResource(R.string.sync_needs_control)
        lastSync is ExtendedMode.SyncResult.Failed -> stringResource(R.string.sync_failed, lastSync.message)
        lastSync is ExtendedMode.SyncResult.Ok -> stringResource(R.string.sync_ok)
        else -> stringResource(R.string.sync_current)
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StepCard(
    title: String,
    done: Boolean,
    doneText: String,
    showContentWhenDone: Boolean = false,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (done) "✓" else "•",
                    color = if (done) OkColor else MissingColor,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            if (done) {
                Text(doneText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!done || showContentWhenDone) content()
        }
    }
}

@Composable
private fun CommandBox(command: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(command) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(command)); copied = true }) {
                Text(if (copied) stringResource(R.string.copied) else stringResource(R.string.copy))
            }
        }
    }
}

private fun pairingError(context: Context, error: Throwable) = when (error) {
    is TimeoutCancellationException -> context.getString(R.string.pairing_not_found)
    else -> context.getString(R.string.pairing_failed, error.message ?: error.javaClass.simpleName)
}

/**
 * The Thor has two screens: showing Settings on the other one keeps the pairing code visible
 * while the user types it here. Falls back to the current screen if that isn't allowed.
 */
private fun openDeveloperSettingsOnOtherDisplay(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        // Highlights the wireless debugging entry, as Shizuku does.
        .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val activity = context as? Activity
    val current = activity?.display?.displayId
    val other = context.getSystemService(DisplayManager::class.java).displays.firstOrNull {
        it.displayId != current && it.state == Display.STATE_ON && it.flags and Display.FLAG_PRIVATE == 0
    }
    val options = other?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
    runCatching { context.startActivity(intent, options) }
        .onFailure { context.startActivity(intent) }
}
