package pl.mateuszkaflowski.ambiled.ui

import androidx.annotation.StringRes
import pl.mateuszkaflowski.ambiled.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.mateuszkaflowski.ambiled.ambient.StickColors
import pl.mateuszkaflowski.ambiled.game.CurrentGame
import pl.mateuszkaflowski.ambiled.led.AMBIENT_FPS_OPTIONS
import pl.mateuszkaflowski.ambiled.led.BuiltInPresets
import pl.mateuszkaflowski.ambiled.led.LedState
import pl.mateuszkaflowski.ambiled.led.Preset
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class EditTarget(@StringRes val label: Int) { LEFT(R.string.stick_left), BOTH(R.string.stick_both), RIGHT(R.string.stick_right) }

enum class LedMode(@StringRes val label: Int) { STATIC(R.string.mode_static), AMBIENT(R.string.mode_ambient), GAME(R.string.mode_game) }

@Composable
fun LedScreen(
    state: LedState,
    userPresets: List<Preset>,
    ambientRunning: Boolean,
    /** Read only while drawing, so live updates redraw the preview without recomposing. */
    ambientColors: State<StickColors?>,
    currentGame: CurrentGame?,
    usageAccess: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (LedMode) -> Unit,
    onStateChange: ((LedState) -> LedState) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (Preset) -> Unit,
    onGameColorsChange: (id: String, colors: StickColors?) -> Unit,
    onRequestUsageAccess: () -> Unit,
    extendedModePaired: Boolean,
    onOpenExtendedMode: () -> Unit,
) {
    var target by rememberSaveable { mutableStateOf(EditTarget.BOTH) }
    val mode = when {
        ambientRunning -> LedMode.AMBIENT
        state.gameMode -> LedMode.GAME
        else -> LedMode.STATIC
    }
    // In game mode the preview shows what the sticks show: the game's colors, if one is on screen.
    val gameColors = currentGame?.colors?.takeIf { mode == LedMode.GAME }
    val canPickStick = mode == LedMode.STATIC || (mode == LedMode.GAME && currentGame?.customColors != null)
    val editedColor = if (target == EditTarget.RIGHT) state.right else state.left

    fun setColor(color: Int) = onStateChange {
        when (target) {
            EditTarget.LEFT -> it.copy(left = color)
            EditTarget.RIGHT -> it.copy(right = color)
            EditTarget.BOTH -> it.copy(left = color, right = color)
        }
    }

    val preview: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EnableCard(
                enabled = state.enabled,
                lightsOff = state.lightsOff,
                onEnabledChange = onEnabledChange,
                onLightUp = { onStateChange { it.copy(lightsOff = false) } },
            )
            ModeSelector(mode, onModeChange)
            if (mode == LedMode.AMBIENT) {
                // Live colors; stick selection doesn't apply here.
                SticksPreview(
                    left = { Color(ambientColors.value?.left ?: 0xFF000000.toInt()) },
                    right = { Color(ambientColors.value?.right ?: 0xFF000000.toInt()) },
                    target = EditTarget.BOTH,
                    dimmed = false,
                    onSelect = {},
                )
            } else {
                SticksPreview(
                    left = { Color(gameColors?.left ?: state.left) },
                    right = { Color(gameColors?.right ?: state.right) },
                    target = if (canPickStick) target else EditTarget.BOTH,
                    dimmed = !state.enabled,
                    onSelect = { if (canPickStick) target = it },
                )
                if (canPickStick) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        EditTarget.entries.forEachIndexed { index, entry ->
                            SegmentedButton(
                                selected = target == entry,
                                onClick = { target = entry },
                                shape = SegmentedButtonDefaults.itemShape(index, EditTarget.entries.size),
                            ) { Text(stringResource(entry.label)) }
                        }
                    }
                }
            }
            BrightnessSlider(state.brightness) { b -> onStateChange { it.copy(brightness = b) } }
            ExtendedModeEntry(extendedModePaired, onOpenExtendedMode)
        }
    }
    val controls: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when (mode) {
                LedMode.AMBIENT -> AmbientSettings(state, onStateChange)
                LedMode.GAME -> GamePanel(
                    game = currentGame,
                    usageAccess = usageAccess,
                    extendedModePaired = extendedModePaired,
                    target = target,
                    onRequestUsageAccess = onRequestUsageAccess,
                    onCustomColorsChange = onGameColorsChange,
                )
                LedMode.STATIC -> {
                    LedColorPicker(editedColor, ::setColor)
                    PresetsSection(
                        presets = BuiltInPresets + userPresets,
                        current = state,
                        onApply = { p -> onStateChange { it.copy(left = p.left, right = p.right) } },
                        onSave = onSavePreset,
                        onDelete = onDeletePreset,
                    )
                }
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        if (maxWidth > 600.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                preview(Modifier.weight(0.42f).verticalScroll(rememberScrollState()))
                controls(Modifier.weight(0.58f).verticalScroll(rememberScrollState()))
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                preview(Modifier)
                controls(Modifier)
            }
        }
    }
}

@Composable
private fun EnableCard(
    enabled: Boolean,
    lightsOff: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onLightUp: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.control_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !enabled -> stringResource(R.string.control_off)
                            lightsOff -> stringResource(R.string.control_lights_off)
                            else -> stringResource(R.string.control_on)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled && lightsOff) {
                TextButton(onClick = onLightUp) { Text(stringResource(R.string.light_up)) }
            }
        }
    }
}

@Composable
private fun ExtendedModeEntry(paired: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.extended_mode), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (paired) stringResource(R.string.extended_on)
                    else stringResource(R.string.extended_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ModeSelector(mode: LedMode, onModeChange: (LedMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        LedMode.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = mode == entry,
                onClick = { if (mode != entry) onModeChange(entry) },
                shape = SegmentedButtonDefaults.itemShape(index, LedMode.entries.size),
                icon = {},
            ) { Text(stringResource(entry.label), maxLines = 1) }
        }
    }
}

@Composable
private fun SticksPreview(
    left: () -> Color,
    right: () -> Color,
    target: EditTarget,
    dimmed: Boolean,
    onSelect: (EditTarget) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        StickRing(
            color = left,
            selected = target != EditTarget.RIGHT,
            dimmed = dimmed,
            onClick = { onSelect(EditTarget.LEFT) },
        )
        StickRing(
            color = right,
            selected = target != EditTarget.LEFT,
            dimmed = dimmed,
            onClick = { onSelect(EditTarget.RIGHT) },
        )
    }
}

@Composable
private fun AmbientSettings(state: LedState, onStateChange: ((LedState) -> LedState) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.mode_ambient), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.ambient_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SamplingSelector(state.ambientFps) { fps -> onStateChange { it.copy(ambientFps = fps) } }
        SwitchRow(
            title = stringResource(R.string.ignore_bars_title),
            description = stringResource(R.string.ignore_bars_description),
            checked = state.ambientIgnoreBars,
            onCheckedChange = { v -> onStateChange { it.copy(ambientIgnoreBars = v) } },
        )
        LabeledSlider(
            label = stringResource(R.string.smoothing),
            valueText = "${(state.ambientSmoothing * 100).roundToInt()}%",
            value = state.ambientSmoothing,
            onChange = { v -> onStateChange { it.copy(ambientSmoothing = v) } },
        )
        LabeledSlider(
            label = stringResource(R.string.color_boost),
            valueText = "${(state.ambientSaturation * 100).roundToInt()}%",
            value = state.ambientSaturation,
            onChange = { v -> onStateChange { it.copy(ambientSaturation = v) } },
        )
    }
}

private val SamplingLabels = mapOf(5 to R.string.sampling_saver, 10 to R.string.sampling_balanced, 0 to R.string.sampling_smooth)

@Composable
private fun SamplingSelector(fps: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sampling_title), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AMBIENT_FPS_OPTIONS.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = fps == value,
                    onClick = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, AMBIENT_FPS_OPTIONS.size),
                    icon = {},
                ) { Text(stringResource(SamplingLabels.getValue(value)), textAlign = TextAlign.Center) }
            }
        }
        Text(
            stringResource(R.string.sampling_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabeledSlider(label: String, valueText: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label: $valueText", style = MaterialTheme.typography.labelLarge)
        Slider(value = value, onValueChange = onChange)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickRing(color: () -> Color, selected: Boolean, dimmed: Boolean, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.onSurface
    Canvas(
        Modifier
            .size(112.dp)
            .clip(CircleShape)
            .combinedClickable(onClick = onClick)
    ) {
        val ring = color().let { if (dimmed) it.copy(alpha = 0.35f) else it }
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(ring.copy(alpha = ring.alpha * 0.45f), Color.Transparent), center, r), r)
        drawCircle(ring, r * 0.62f, style = Stroke(r * 0.14f))
        drawCircle(Color(0xFF2A2A2E), r * 0.48f)
        drawCircle(Color(0xFF3A3A40), r * 0.30f)
        if (selected) drawCircle(outline, r - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun BrightnessSlider(brightness: Float, onChange: (Float) -> Unit) {
    Column {
        Text(stringResource(R.string.brightness, (brightness * 100).roundToInt()), style = MaterialTheme.typography.labelLarge)
        // Quadratic mapping gives finer control at the low end, where the LEDs are usually run.
        Slider(
            value = sqrt(brightness),
            onValueChange = { onChange((it * it).coerceAtLeast(MIN_BRIGHTNESS)) },
        )
    }
}

private const val MIN_BRIGHTNESS = 0.01f

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsSection(
    presets: List<Preset>,
    current: LedState,
    onApply: (Preset) -> Unit,
    onSave: (String) -> Unit,
    onDelete: (Preset) -> Unit,
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Preset?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.presets), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                PresetChip(
                    preset = preset,
                    active = preset.left == current.left && preset.right == current.right,
                    onClick = { onApply(preset) },
                    onLongClick = if (preset.builtIn) null else ({ toDelete = preset }),
                )
            }
            AddPresetChip { showSaveDialog = true }
        }
        if (presets.any { !it.builtIn }) {
            Text(
                stringResource(R.string.presets_delete_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(onDismiss = { showSaveDialog = false }, onSave = { onSave(it); showSaveDialog = false })
    }
    toDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.preset_delete_title, preset.name)) },
            confirmButton = { TextButton(onClick = { onDelete(preset); toDelete = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(preset: Preset, active: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(Color(preset.left))
        Spacer(Modifier.width(4.dp))
        Dot(Color(preset.right))
        Spacer(Modifier.width(8.dp))
        Text(preset.nameRes?.let { stringResource(it) } ?: preset.name, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddPresetChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(stringResource(R.string.save_current), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(14.dp).clip(CircleShape).background(color))
}

@Composable
private fun SavePresetDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_preset)) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
