package dev.waadri.anylisten.ui.browse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.waadri.anylisten.AnyListenApp
import dev.waadri.anylisten.data.remote.Library
import dev.waadri.anylisten.data.remote.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the browse screen renders. */
data class BrowseUiState(
    val lists: List<Library.ListSummary> = emptyList(),
    val loadingLists: Boolean = false,
    val openListId: String? = null,
    val openListName: String = "",
    val tracks: List<Wire.PlayMusicInfo> = emptyList(),
    val loadingTracks: Boolean = false,
    val errorMessage: String? = null,
    /** Item id of the track currently playing, so the list can highlight it. */
    val playingItemId: String? = null,
)

/**
 * Browse state. Deliberately short-lived: it holds only what is on screen, while the play queue
 * itself stays owned by [dev.waadri.anylisten.playback.PlaybackRepository] so that starting a
 * song here cannot fork playback state.
 */
class BrowseViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AnyListenApp).container

    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.playback.state.collect { playback ->
                _state.value = _state.value.copy(playingItemId = playback.track?.itemId)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingLists = true, errorMessage = null)
            container.playback.loadLists()
                .onSuccess { lists ->
                    _state.value = _state.value.copy(lists = lists, loadingLists = false)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loadingLists = false,
                        errorMessage = error.message ?: "加载歌单失败",
                    )
                }
        }
    }

    fun openList(list: Library.ListSummary) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                openListId = list.id,
                openListName = list.name,
                tracks = emptyList(),
                loadingTracks = true,
                errorMessage = null,
            )
            container.playback.loadListMusics(list.id)
                .onSuccess { tracks ->
                    // Guard against a slower response overwriting a list the user already left.
                    if (_state.value.openListId != list.id) return@onSuccess
                    _state.value = _state.value.copy(tracks = tracks, loadingTracks = false)
                }
                .onFailure { error ->
                    if (_state.value.openListId != list.id) return@onFailure
                    _state.value = _state.value.copy(
                        loadingTracks = false,
                        errorMessage = error.message ?: "加载歌曲失败",
                    )
                }
        }
    }

    fun closeList() {
        _state.value = _state.value.copy(
            openListId = null,
            openListName = "",
            tracks = emptyList(),
            loadingTracks = false,
            errorMessage = null,
        )
    }

    /** Starts a track from the currently open list. */
    fun play(index: Int) {
        val listId = _state.value.openListId ?: return
        val tracks = _state.value.tracks
        if (index !in tracks.indices) return
        viewModelScope.launch {
            container.playback.playFromList(
                listId = listId,
                listName = _state.value.openListName,
                list = tracks,
                index = index,
            )
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }
}
