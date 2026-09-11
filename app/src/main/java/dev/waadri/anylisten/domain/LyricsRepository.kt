package dev.waadri.anylisten.domain

import dev.waadri.anylisten.data.config.ConfigStore
import dev.waadri.anylisten.Diag
import dev.waadri.anylisten.data.remote.RpcSocket
import dev.waadri.anylisten.data.remote.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Lyrics for the track currently playing, plus how the fetch went. */
data class LyricsUiState(
    /** The track these lyrics belong to, so the UI never shows one song's words over another. */
    val trackItemId: String? = null,
    val lyrics: Lyrics = Lyrics.EMPTY,
    val loading: Boolean = false,
    /**
     * True once a fetch has finished for [trackItemId], whether or not it produced lyrics. Without
     * it the UI cannot tell "still loading" from "this track has no lyrics".
     */
    val loaded: Boolean = false,
    val errorMessage: String? = null,
) {
    val hasLyrics: Boolean get() = !lyrics.isEmpty
}

/**
 * Fetches and caches lyrics for whatever track is playing.
 *
 * Kept out of [dev.waadri.anylisten.playback.PlaybackRepository] on purpose: playback must keep
 * working when lyrics fail, and lyrics must not be able to stall the playhead. This class observes
 * the player and never drives it.
 *
 * ## What the server does and does not give us
 *
 * The server returns a raw `LyricInfo` whose `lyric` field is one LRC string with the translation,
 * romanisation and word-by-word layers base64-encoded into tags. Decoding is
 * [LyricsParser]'s job. The server resolves `null` for a track it has no lyrics for, which is not
 * an error and must not be shown as one.
 *
 * ## Caching
 *
 * One entry, not a map: only the current track's lyrics are ever displayed, and the next track
 * usually differs, so an LRU would hold memory nobody reads. Revisiting a track re-fetches, which
 * is a single call to a server on the same LAN.
 */
class LyricsRepository(
    private val scope: CoroutineScope,
    private val configStore: ConfigStore,
) {
    private var socket: RpcSocket? = null
    private var observeJob: Job? = null
    private var currentJob: Job? = null

    private val _state = MutableStateFlow(LyricsUiState())
    val state: StateFlow<LyricsUiState> = _state.asStateFlow()

    /** Bound by the same session hook that binds the player, so both see the same connection. */
    fun attachSocket(socket: RpcSocket?) {
        observeJob?.cancel()
        currentJob?.cancel()
        this.socket = socket
        if (socket == null) {
            _state.value = LyricsUiState()
        }
    }

    /**
     * Watches the player and fetches lyrics whenever the track changes.
     *
     * Driven by [playbackState] rather than called from the player, because the player has many
     * paths that change the track (auto-advance, remote skip, resume on launch) and hooking them
     * all individually is how one gets missed.
     */
    fun observe(playbackState: StateFlow<dev.waadri.anylisten.playback.PlaybackUiState>) {
        observeJob?.cancel()
        observeJob = scope.launch {
            var lastItemId: String? = null
            playbackState.collect { playback ->
                val track = playback.track
                val itemId = track?.itemId
                if (itemId == lastItemId) return@collect
                lastItemId = itemId

                currentJob?.cancel()
                if (track == null) {
                    _state.value = LyricsUiState()
                    return@collect
                }
                currentJob = scope.launch { fetch(track) }
            }
        }
    }

    /** Re-fetches for the current track, bypassing the cache. */
    fun refresh(track: Wire.PlayMusicInfo?) {
        if (track == null) return
        currentJob?.cancel()
        currentJob = scope.launch { fetch(track, isRefresh = true) }
    }

    private suspend fun fetch(track: Wire.PlayMusicInfo, isRefresh: Boolean = false) {
        val socket = socket ?: run {
            // Offline: keep whatever is displayed rather than blanking the pane, but mark the
            // attempt finished so the UI stops claiming to load.
            _state.value = _state.value.copy(loading = false, loaded = true)
            return
        }

        _state.value = LyricsUiState(trackItemId = track.itemId, loading = true)

        val info = runCatching { socket.getMusicLyric(track.musicInfo, isRefresh) }
            .getOrElse { error ->
                Diag.problem("lyrics.fetch.failed", "${track.musicInfo.name} — ${error.message}")
                _state.value = LyricsUiState(
                    trackItemId = track.itemId,
                    loaded = true,
                    errorMessage = error.message ?: "获取歌词失败",
                )
                return
            }

        if (info == null) {
            // No lyrics for this track. Not an error: the pane says so plainly.
            Diag.d("lyrics.none", track.musicInfo.name)
            _state.value = LyricsUiState(trackItemId = track.itemId, loaded = true)
            return
        }

        val lyrics = LyricsParser.parse(info.lyric, info.tlyric)
        // The line count is the point. A blank lyric pane looks identical whether the server had
        // nothing, the tag key was wrong, or the body was stripped of timing — and the last two
        // are invisible by construction, because an empty document is a valid parse.
        Diag.event(
            "lyrics.parsed",
            "track" to track.musicInfo.name,
            "chars" to info.lyric.length,
            "lines" to lyrics.lines.size,
            "empty" to lyrics.isEmpty,
        )
        _state.value = LyricsUiState(
            trackItemId = track.itemId,
            lyrics = lyrics,
            loaded = true,
        )
    }

    /** Local preference flows, re-exposed so the UI has one place to collect lyrics state from. */
    val showTranslation: kotlinx.coroutines.flow.Flow<Boolean> = configStore.playbackShowTranslation

    fun setTranslationEnabled(enabled: Boolean) {
        scope.launch { configStore.saveShowTranslation(enabled) }
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }
}
