package pl.mateuszkaflowski.ambiled.ui

import pl.mateuszkaflowski.ambiled.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import pl.mateuszkaflowski.ambiled.ambient.StickColors
import pl.mateuszkaflowski.ambiled.game.CurrentGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GamePanel(
    game: CurrentGame?,
    usageAccess: Boolean,
    extendedModePaired: Boolean,
    target: EditTarget,
    onRequestUsageAccess: () -> Unit,
    onCustomColorsChange: (id: String, colors: StickColors?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.game_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.game_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!usageAccess) {
            InfoCard(stringResource(R.string.game_usage_access)) {
                Button(onClick = onRequestUsageAccess) { Text(stringResource(R.string.grant_access)) }
            }
        }
        if (!extendedModePaired) {
            InfoCard(stringResource(R.string.game_needs_extended))
        }
        if (game == null) {
            InfoCard(stringResource(R.string.game_none))
        } else {
            CurrentGameCard(game, target, onCustomColorsChange)
        }
    }
}

@Composable
private fun CurrentGameCard(
    game: CurrentGame,
    target: EditTarget,
    onCustomColorsChange: (id: String, colors: StickColors?) -> Unit,
) {
    val custom = game.customColors
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(game)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(game.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        game.platform ?: stringResource(R.string.platform_android),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        when {
                            custom != null -> stringResource(R.string.source_custom)
                            game.presetColors != null -> stringResource(R.string.source_preset)
                            game.autoColors == null -> stringResource(R.string.source_none)
                            game.platform != null -> stringResource(R.string.source_cover)
                            else -> stringResource(R.string.source_icon)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                game.colors?.let {
                    Dot(Color(it.left))
                    Spacer(Modifier.width(6.dp))
                    Dot(Color(it.right))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.custom_colors_for_game), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = custom != null,
                    onCheckedChange = { on ->
                        val start = game.colors ?: StickColors(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
                        onCustomColorsChange(game.id, if (on) start else null)
                    },
                )
            }
            if (custom != null) {
                val edited = if (target == EditTarget.RIGHT) custom.right else custom.left
                LedColorPicker(edited, onColorChange = { color ->
                    onCustomColorsChange(
                        game.id,
                        when (target) {
                            EditTarget.LEFT -> custom.copy(left = color)
                            EditTarget.RIGHT -> custom.copy(right = color)
                            EditTarget.BOTH -> StickColors(color, color)
                        },
                    )
                })
            }
        }
    }
}

@Composable
private fun Cover(game: CurrentGame) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, game.id) {
        value = withContext(Dispatchers.IO) { loadCover(context, game) }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(88.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        image?.let { Image(it, contentDescription = game.title, contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp)) }
    }
}

private fun loadCover(context: Context, game: CurrentGame): ImageBitmap? = runCatching {
    val file = game.artwork
    if (file != null) {
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
    } else {
        context.packageManager.getApplicationIcon(game.packageName).toBitmap(192, 192).asImageBitmap()
    }
}.getOrNull()

@Composable
private fun InfoCard(text: String, action: (@Composable () -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(18.dp).clip(CircleShape).background(color))
}
