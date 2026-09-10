package dev.waadri.anylisten.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.waadri.anylisten.AnyListenApp
import dev.waadri.anylisten.domain.ConnectionPhase
import dev.waadri.anylisten.playback.PlaybackUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonPrimitive

/**
 * Read-only view over the application-scoped player.
 *
 * It deliberately owns no playback state of its own: playback continues while this ViewModel is
 * cleared, so copying any of it here would create a second source of truth for the same playhead.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AnyListenApp).container

    val state: StateFlow<PlaybackUiState> = container.playback.state

    val connected: StateFlow<Boolean> = container.clientSession.phase
        .map { it is ConnectionPhase.Connected || it is ConnectionPhase.Reconnecting }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = container.clientSession.phase.value is ConnectionPhase.Connected,
        )

    fun togglePlay() {
        val action = if (container.playback.state.value.isPlaying) "pause" else "play"
        container.playback.perform(action)
    }

    fun next() {
        container.playback.perform("next")
    }

    fun previous() {
        container.playback.perform("prev")
    }

    fun seekTo(seconds: Double) {
        container.playback.perform("seek", JsonPrimitive(seconds))
    }
}
