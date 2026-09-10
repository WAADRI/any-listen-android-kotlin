package dev.waadri.anylisten.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.waadri.anylisten.AnyListenApp
import dev.waadri.anylisten.data.remote.PlayMethod
import dev.waadri.anylisten.domain.ConnectionPhase
import dev.waadri.anylisten.playback.PlaybackUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Thin command surface over the application-scoped player.
 *
 * It owns no playback state: playback continues while this ViewModel is cleared, so copying any of
 * it here would create a second source of truth for the same playhead. Every method is a direct
 * delegation to [dev.waadri.anylisten.playback.PlaybackRepository], which is entirely local.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AnyListenApp).container

    val state: StateFlow<PlaybackUiState> = container.playback.state

    /**
     * Whether the server is reachable. Only affects new track resolution — the current track keeps
     * playing through a disconnect, because the audio is already local.
     */
    val connected: StateFlow<Boolean> = container.clientSession.phase
        .map { it is ConnectionPhase.Connected || it is ConnectionPhase.Reconnecting }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = container.clientSession.phase.value is ConnectionPhase.Connected,
        )

    fun togglePlay() = container.playback.togglePlay()

    fun next() = container.playback.next()

    fun previous() = container.playback.previous()

    fun seekTo(seconds: Double) = container.playback.seekTo((seconds * 1000).toLong())

    fun cyclePlayMethod() = container.playback.cyclePlayMethod()

    fun setPlayMethod(method: PlayMethod) = container.playback.setPlayMethod(method)

    fun setResumeOnLaunch(enabled: Boolean) = container.playback.setResumeOnLaunch(enabled)

    fun clearError() = container.playback.clearError()
}
