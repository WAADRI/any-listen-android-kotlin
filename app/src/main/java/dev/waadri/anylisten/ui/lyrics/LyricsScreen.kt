package dev.waadri.anylisten.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.waadri.anylisten.domain.LyricLine
import kotlinx.coroutines.delay

@Composable
fun LyricsScreen(
    state: LyricsScreenState,
    showTranslation: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleTranslation: (Boolean) -> Unit,
    onSeekToLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Header(
            title = state.trackTitle,
            singer = state.trackSinger,
            onBack = onBack,
            onRefresh = onRefresh,
        )
        HorizontalDivider()

        // The translation switch is only offered when there is a translation to show, so it never
        // appears as a control that does nothing.
        if (state.lyrics.lyrics.lines.any { !it.translation.isNullOrBlank() }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = if (showTranslation) "显示翻译" else "仅原文",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onToggleTranslation(!showTranslation) }) {
                    Text(if (showTranslation) "隐藏翻译" else "显示翻译")
                }
            }
            HorizontalDivider()
        }

        when {
            state.lyrics.loading -> CenteredMessage {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text("正在获取歌词…", style = MaterialTheme.typography.bodyMedium)
            }

            state.lyrics.errorMessage != null -> CenteredMessage {
                Text(
                    text = state.lyrics.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRefresh) { Text("重试") }
            }

            !state.hasLyrics -> CenteredMessage {
                Text(
                    text = when {
                        state.trackTitle.isBlank() -> "还没有正在播放的歌曲"
                        state.lyrics.loaded -> "这首歌没有歌词"
                        else -> "歌词将在开始播放后载入"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> LyricsBody(
                lines = state.lyrics.lyrics.lines,
                activeIndex = state.activeIndex,
                showTranslation = showTranslation,
                onSeekToLine = onSeekToLine,
            )
        }
    }
}

@Composable
private fun Header(
    title: String,
    singer: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "歌词" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (singer.isNotBlank()) {
                Text(
                    text = singer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "重新获取歌词")
        }
    }
}

/**
 * The scrolling lyric list.
 *
 * Two behaviours worth stating, because they are what makes lyrics usable on a phone:
 *
 *  - Auto-scroll **disengages while the user is scrolling**. Otherwise a tick lands mid-flick and
 *    yanks the list back, and the user can never read ahead. The shift is deliberately animated to
 *    the position a few lines above the active one rather than centring on it, so the next line is
 *    visible before it is sung.
 *  - Every line is tappable and seeks. On a phone, "jump to the chorus" is the main use of lyrics.
 */
@Composable
private fun LyricsBody(
    lines: List<LyricLine>,
    activeIndex: Int,
    showTranslation: Boolean,
    onSeekToLine: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    val autoScroll = rememberAutoScrollEnabled(listState)

    LaunchedEffect(activeIndex, autoScroll) {
        if (activeIndex >= 0 && autoScroll) {
            // Keep two lines of context above the active one.
            listState.animateScrollToItem((activeIndex - LINES_ABOVE).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(lines, key = { index, line -> "${line.timeMs}-$index" }) { index, line ->
            LyricRow(
                line = line,
                active = index == activeIndex,
                showTranslation = showTranslation,
                onClick = { onSeekToLine(line.timeMs) },
            )
        }
    }
}

@Composable
private fun LyricRow(
    line: LyricLine,
    active: Boolean,
    showTranslation: Boolean,
    onClick: () -> Unit,
) {
    val translation = line.translation?.takeIf { showTranslation && it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                color = if (active) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = line.text.ifBlank { "♪" },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (translation != null) {
            Text(
                text = translation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Tracks whether auto-scroll should follow the music.
 *
 * It turns off as soon as the user drags the list and back on once they stop, which is what every
 * music app that gets this right does: reading ahead must be possible, and the list must not fight
 * the finger.
 */
@Composable
private fun rememberAutoScrollEnabled(listState: LazyListState): Boolean {
    var autoScroll by remember { mutableStateOf(true) }
    val scrolling by remember { derivedStateOf { listState.isScrollInProgress } }

    LaunchedEffect(scrolling) {
        if (scrolling) {
            autoScroll = false
        } else {
            // A short settle delay before re-engaging, so the list does not snap back the instant
            // the finger lifts while the user is still reading.
            delay(AUTO_SCROLL_RESUME_DELAY_MS)
            autoScroll = true
        }
    }

    return autoScroll
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/** How many lines to keep visible above the highlighted one when auto-scrolling. */
private const val LINES_ABOVE = 2

/** How long to wait after the user stops scrolling before auto-scroll resumes. */
private const val AUTO_SCROLL_RESUME_DELAY_MS = 2_500L
