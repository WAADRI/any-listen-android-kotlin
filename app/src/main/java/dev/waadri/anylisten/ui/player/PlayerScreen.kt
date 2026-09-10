package dev.waadri.anylisten.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import dev.waadri.anylisten.data.remote.PlayMethod
import dev.waadri.anylisten.playback.PlaybackRepository
import dev.waadri.anylisten.playback.PlaybackUiState

/**
 * The now-playing screen.
 *
 * Layout is a percentage-free column with weights so it adapts to any phone aspect ratio; the
 * web client's fixed pixel layout is the reason the original felt wrong on a phone.
 */
@Composable
fun PlayerScreen(
    state: PlaybackUiState,
    connected: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Double) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenLyrics: () -> Unit,
    onCyclePlayMethod: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!connected) {
                OfflineNotice()
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenLyrics) {
                Icon(
                    imageVector = Icons.Filled.QueueMusic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("歌词")
            }
            TextButton(onClick = onOpenBrowse) {
                Icon(
                    imageVector = Icons.Filled.QueueMusic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("音乐库")
            }
            TextButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("设置")
            }
        }

        Spacer(Modifier.weight(0.5f))

        AlbumArt(picUrl = state.track?.musicInfo?.meta?.picUrl)

        Spacer(Modifier.height(24.dp))

        Text(
            text = state.track?.musicInfo?.name ?: "未在播放",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.track?.musicInfo?.singer ?: "服务端当前没有播放中的曲目",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(0.5f))

        SeekBar(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            onSeek = onSeek,
        )

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(24.dp))
            FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(72.dp)) {
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.width(24.dp))
            IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一首", modifier = Modifier.size(36.dp))
            }
        }

        Spacer(Modifier.height(4.dp))

        // Play mode and queue position. The mode button is a tap-to-cycle control, matching the
        // web client: it is the one control changed most often and a picker would cost two taps.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onCyclePlayMethod) {
                Icon(
                    imageVector = playMethodIcon(state.playMethod),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(playMethodLabel(state.playMethod))
            }
            if (state.queueSize > 0) {
                Text(
                    text = "${state.queueIndex + 1}/${state.queueSize}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.sourceListName.isNotBlank()) {
            Text(
                text = "来自「${state.sourceListName}」",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(4.dp))

        state.errorMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Seek bar with local drag ownership.
 *
 * Position arrives roughly twice a second from the audio engine. Without the [dragging] guard a
 * tick landing mid-drag would snap the thumb back under the user's finger, which is exactly the
 * kind of unresponsive control that made the web client frustrating on a phone.
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Double) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val displayFraction = if (dragging) {
        dragFraction
    } else if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = displayFraction,
            onValueChange = { value ->
                dragging = true
                dragFraction = value
            },
            onValueChangeFinished = {
                dragging = false
                if (durationMs > 0) {
                    onSeek(durationMs / 1000.0 * dragFraction)
                }
            },
            enabled = durationMs > 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = PlaybackRepository.formatTime(displayFraction * durationMs / 1000.0),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (durationMs > 0) PlaybackRepository.formatTime(durationMs / 1000.0) else "--:--",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlbumArt(picUrl: String?) {
    val shape: Shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth(0.78f)
            .aspectRatio(1f)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (picUrl.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AsyncImage(
                model = picUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun OfflineNotice() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "服务端已断开",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun playMethodIcon(method: PlayMethod) = when (method) {
    PlayMethod.LIST_LOOP -> Icons.Filled.Repeat
    PlayMethod.RANDOM -> Icons.Filled.Shuffle
    PlayMethod.LIST -> Icons.Filled.PlaylistPlay
    PlayMethod.SINGLE_LOOP -> Icons.Filled.RepeatOne
    PlayMethod.STOP_AT_END -> Icons.Filled.PauseCircleOutline
}

private fun playMethodLabel(method: PlayMethod): String = when (method) {
    PlayMethod.LIST_LOOP -> "列表循环"
    PlayMethod.RANDOM -> "随机播放"
    PlayMethod.LIST -> "顺序播放"
    PlayMethod.SINGLE_LOOP -> "单曲循环"
    PlayMethod.STOP_AT_END -> "播完停止"
}
