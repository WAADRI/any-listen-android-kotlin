package dev.waadri.anylisten.ui.lyrics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.waadri.anylisten.AnyListenApp
import dev.waadri.anylisten.domain.LyricsUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Lyrics state, reduced for the UI.
 *
 * The active line index is computed here rather than in the composable: it is a pure function of
 * the playhead and the parsed document, and deriving it inside the composable would recompute it
 * on every unrelated recomposition.
 */
data class LyricsScreenState(
    val trackTitle: String = "",
    val trackSinger: String = "",
    val lyrics: LyricsUiState = LyricsUiState(),
    /** Index into `lyrics.lyrics.lines`, or -1 before the first line starts. */
    val activeIndex: Int = -1,
) {
    val hasLyrics: Boolean get() = lyrics.hasLyrics
}

class LyricsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AnyListenApp).container

    val showTranslation: StateFlow<Boolean> = container.lyrics.showTranslation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val state: StateFlow<LyricsScreenState> = combine(
        container.playback.state,
        container.lyrics.state,
    ) { play, lyrics ->
        LyricsScreenState(
            trackTitle = play.track?.musicInfo?.name.orEmpty(),
            trackSinger = play.track?.musicInfo?.singer.orEmpty(),
            lyrics = lyrics,
            activeIndex = lyrics.lyrics.indexAt(play.positionMs),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsScreenState())

    /** Re-fetches the current track's lyrics, for when the server's copy has just been updated. */
    fun refresh() = container.lyrics.refresh(container.playback.state.value.track)

    fun setTranslationEnabled(enabled: Boolean) = container.lyrics.setTranslationEnabled(enabled)

    /**
     * Jumps playback to a lyric line.
     *
     * The whole reason to render lyrics as distinct rows rather than one text block: on a phone the
     * usual way to use lyrics is to tap the line you want to hear.
     */
    fun seekToLine(timeMs: Long) = container.playback.seekTo(timeMs)
}
