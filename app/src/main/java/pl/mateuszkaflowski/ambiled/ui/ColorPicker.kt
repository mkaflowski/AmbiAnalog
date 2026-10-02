package pl.mateuszkaflowski.ambiled.ui

import pl.mateuszkaflowski.ambiled.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

private val Swatches = listOf(
    0xFFFF0000, 0xFFFF4000, 0xFFFF8000, 0xFFFFD000, 0xFF80FF00, 0xFF00FF00,
    0xFF00FF80, 0xFF00FFFF, 0xFF0080FF, 0xFF0000FF, 0xFF8000FF, 0xFFFF00FF,
    0xFFFF008C, 0xFFFFFFFF,
).map { it.toInt() }

/**
 * Hue + saturation picker. Value is fixed at 100 % because LED brightness is a separate,
 * global setting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LedColorPicker(color: Int, onColorChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
    // Hue is undefined for white; remember the last one so the saturation bar keeps its tint.
    var lastHue by remember { mutableFloatStateOf(hsv[0]) }
    val saturation = hsv[1]
    val hue = if (saturation > 0.01f) hsv[0] else lastHue
    SideEffect { lastHue = hue }

    fun emit(h: Float, s: Float) = onColorChange(Color.hsv(h, s, 1f).toArgb())

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.hue), style = MaterialTheme.typography.labelLarge)
        GradientBar(
            brush = Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) }),
            position = hue / 360f,
            thumbColor = Color.hsv(hue, 1f, 1f),
            // Picking a hue on white would otherwise do nothing visible.
            onPositionChange = {
                lastHue = it * 360f
                emit(lastHue, if (saturation > 0.01f) saturation else 1f)
            },
        )
        Text(stringResource(R.string.saturation), style = MaterialTheme.typography.labelLarge)
        GradientBar(
            brush = Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))),
            position = saturation,
            thumbColor = Color(color),
            onPositionChange = { emit(hue, it) },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatches.forEach { swatch ->
                val selected = swatch == color
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(swatch))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.2f),
                            shape = CircleShape,
                        )
                        .clickable { onColorChange(swatch) }
                )
            }
        }
    }
}

@Composable
private fun GradientBar(
    brush: Brush,
    position: Float,
    thumbColor: Color,
    onPositionChange: (Float) -> Unit,
) {
    val currentOnChange by rememberUpdatedState(onPositionChange)
    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(Unit) {
                fun report(x: Float) = currentOnChange((x / size.width).coerceIn(0f, 1f))
                awaitEachGesture {
                    val down = awaitFirstDown()
                    report(down.position.x)
                    drag(down.id) {
                        report(it.position.x)
                        it.consume()
                    }
                }
            }
            .drawBehind {
                val barHeight = size.height * 0.6f
                val top = (size.height - barHeight) / 2
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(0f, top),
                    size = size.copy(height = barHeight),
                    cornerRadius = CornerRadius(barHeight / 2),
                )
                val radius = size.height / 2 - 2.dp.toPx()
                val x = (position * size.width).coerceIn(radius, size.width - radius)
                val center = Offset(x, size.height / 2)
                drawCircle(thumbColor, radius, center)
                drawCircle(Color.White, radius, center, style = Stroke(3.dp.toPx()))
            }
    )
}
