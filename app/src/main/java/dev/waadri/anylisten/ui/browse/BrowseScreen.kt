package dev.waadri.anylisten.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import coil.size.Size
import dev.waadri.anylisten.data.remote.Library
import dev.waadri.anylisten.data.remote.Wire

/**
 * Playlist browser: the entry point that lets the user choose what to play rather than only
 * following whatever the session was already doing.
 *
 * Two levels only — playlists, then tracks. Deeper navigation (search, albums, artists) exists on
 * the server but is not what a phone user needs to get music playing.
 */
@Composable
fun BrowseScreen(
    state: BrowseUiState,
    onBack: () -> Unit,
    onOpenList: (Library.ListSummary) -> Unit,
    onCloseList: () -> Unit,
    onPlay: (Int) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Load once when the screen first appears. Refresh is manual: lists change rarely, and a
    // background poll would fight the server for no benefit.
    LaunchedEffect(Unit) {
        if (state.lists.isEmpty()) onRefresh()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(
                onClick = { if (state.openListId != null) onCloseList() else onBack() },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = if (state.openListId != null) state.openListName else "音乐库",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }

        HorizontalDivider()

        state.errorMessage?.let { message ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefresh) { Text("重试") }
            }
        }

        val loading = if (state.openListId != null) state.loadingTracks else state.loadingLists
        when {
            loading -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.openListId != null -> TrackList(
                tracks = state.tracks,
                trackCovers = state.trackCovers,
                playingItemId = state.playingItemId,
                onPlay = onPlay,
            )

            else -> ListList(lists = state.lists, onOpenList = onOpenList)
        }
    }
}

@Composable
private fun ListList(
    lists: List<Library.ListSummary>,
    onOpenList: (Library.ListSummary) -> Unit,
) {
    if (lists.isEmpty()) {
        EmptyHint("服务端上没有歌单。先到 any-listen 网页端添加音乐。")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(lists, key = { it.id }) { list ->
            ListItem(
                headlineContent = {
                    Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = { Text("${list.songCount} 首") },
                leadingContent = {
                    CoverThumb(
                        url = list.coverUrl,
                        fallbackIcon = Icons.Filled.QueueMusic,
                        size = 48.dp,
                    )
                },
                modifier = Modifier.clickable { onOpenList(list) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<Wire.PlayMusicInfo>,
    trackCovers: Map<String, String>,
    playingItemId: String?,
    onPlay: (Int) -> Unit,
) {
    if (tracks.isEmpty()) {
        EmptyHint("这个歌单是空的。")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(tracks, key = { _, item -> item.itemId }) { index, track ->
            val playing = track.itemId == playingItemId
            ListItem(
                headlineContent = {
                    Text(
                        text = track.musicInfo.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (playing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                },
                supportingContent = {
                    Text(
                        text = track.musicInfo.singer.ifBlank { "未知歌手" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = {
                    CoverThumb(
                        // Resolved by the view model; only rows on screen are composed, so only
                        // these are ever requested.
                        url = trackCovers[track.itemId],
                        fallbackIcon = Icons.Filled.MusicNote,
                        size = 48.dp,
                    )
                },
                trailingContent = {
                    if (playing) {
                        Icon(
                            imageVector = Icons.Filled.Equalizer,
                            contentDescription = "正在播放",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        track.musicInfo.interval?.let { interval ->
                            Text(interval, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                modifier = Modifier.clickable { onPlay(index) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun CoverThumb(
    url: String?,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    size: Dp,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val context = LocalContext.current
            val pixels = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
            // Ask for exactly the thumbnail, not the full-size artwork: a list row needs a 48dp
            // square, and decoding a 1000px cover per row is the kind of waste that makes a long
            // list feel heavy. The request is only made when the row is composed, so off-screen
            // covers are never fetched at all.
            val request = remember(url, pixels, context) {
                ImageRequest.Builder(context)
                    .data(url)
                    .size(Size(pixels, pixels))
                    .scale(Scale.FILL)
                    .precision(Precision.INEXACT)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
