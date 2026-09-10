package dev.waadri.anylisten.playback

import android.content.Context
import dev.waadri.anylisten.data.remote.AppSettings
import dev.waadri.anylisten.data.remote.M2cCodec
import dev.waadri.anylisten.data.remote.PlayMethod
import dev.waadri.anylisten.data.remote.RpcSocket
import dev.waadri.anylisten.data.remote.Wire
import dev.waadri.anylisten.domain.PlayOrderDecision
import dev.waadri.anylisten.domain.PlayOrderResolver
import dev.waadri.anylisten.domain.PlayQueueSnapshot
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Everything the player UI renders. */
data class PlaybackUiState(
    val track: Wire.PlayMusicInfo? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playMethod: PlayMethod = PlayMethod.LIST_LOOP,
    val queueSize: Int = 0,
    val queueIndex: Int = 0,
    val errorMessage: String? = null,
) {
    val positionSeconds: Double get() = positionMs / 1000.0
    val durationSeconds: Double get() = durationMs / 1000.0
    val progressFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Owns playback: the play queue mirror, the ExoPlayer-backed [AudioEngine], and the RPC
 * conversation with the server.
 *
 * Division of responsibility, learned from the web client:
 *  - The SERVER holds the session's play list, current index and settings. It is the only
 *    source for "what was playing last", which is what makes progress survive restarts and
 *    stay consistent across devices.
 *  - THIS CLIENT decides what plays next, because skip order (listLoop/random/singleLoop/...)
 *    is evaluated client-side by any-listen and only the *mode* is stored on the server.
 *  - Audio always plays here; the server never streams to us.
 *
 * Server command flow: an action is dispatched locally for immediate response and reported
 * upstream with `player.playerAction`. The server echoes that action back to every ready
 * client (including this one), where it is applied again. Actions are idempotent, so the echo
 * is harmless and guarantees convergence if another device drives playback.
 */
class PlaybackRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val engine = AudioEngine(context, scope)
    private val random = Random()

    private var socket: RpcSocket? = null
    private var baseUrl: String = ""
    private var wireJobs = mutableListOf<Job>()

    private var queue: List<Wire.PlayMusicInfo> = emptyList()
    private var currentIndex = 0
    private var historyList: List<Wire.HistoryItem> = emptyList()
    private var historyIndex = 0
    private var settings = AppSettings()

    /** Set while we are waiting for a resolved URL so a late arrival does not clobber state. */
    private var resolvingItemId: String? = null

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    // Mirrors owned here (not read from the engine) so they survive independently of the
    // player's readiness, matching the web client's `playerState.playing` semantics.
    private var userWantsPlaying = false
    private var progressReportJob: Job? = null

    fun attachSocket(socket: RpcSocket?, baseUrl: String = "") {
        detachSocket()
        this.socket = socket ?: return
        this.baseUrl = baseUrl.trimEnd('/')

        socket.on(RpcSocket.INCOMING_PLAYER_EVENT) { args ->
            val element = args.firstOrNull()?.value
            if (element != null && element !is JsonNull) {
                scope.launch { handlePlayerEvent(element) }
            }
            null
        }
        socket.on(RpcSocket.INCOMING_PLAYER_ACTION) { args ->
            val element = args.firstOrNull()?.value
            if (element != null && element !is JsonNull) {
                val action = playerActionOf(element)
                if (action != null) {
                    scope.launch {
                        // Server-originated actions are already known to the server, so nothing
                        // is reported back. Echoing them would double every remote command.
                        dispatch(action.action, action.data)
                    }
                }
            }
            null
        }

        wireJobs += scope.launch { observeEngine() }
        wireJobs += scope.launch { syncFromServer() }
        startProgressReporting()
    }

    private fun detachSocket() {
        wireJobs.forEach { it.cancel() }
        wireJobs.clear()
        progressReportJob?.cancel()
        progressReportJob = null
        socket = null
    }

    // ------------------------------------------------------------------ server sync

    /** Pulls the session state the server already holds: queue, current track, settings. */
    private suspend fun syncFromServer() {
        val socket = socket ?: return
        runCatching {
            val playInfo = socket.getPlayInfo()
            queue = playInfo.list
            currentIndex = playInfo.info.index.coerceIn(0, (playInfo.list.size - 1).coerceAtLeast(0))
            historyList = playInfo.historyList
            historyIndex = playInfo.info.historyIndex
            publish()

            // Settings carry the play method, which decides skip behaviour from here on.
            val rawSettings = socket.call(listOf("app", "getSetting"))
            settings = AppSettings.fromJson(rawSettings)
            publish()

            val current = queue.getOrNull(currentIndex)
            if (current != null) {
                loadAndPlay(current, startPositionMs = (playInfo.info.time * 1000).toLong(), autoPlay = false)
            }
        }.onFailure { error ->
            _state.value = _state.value.copy(errorMessage = error.message ?: "同步播放状态失败")
        }
    }

    private suspend fun resolveUrl(musicInfo: Wire.MusicInfo): String? {
        val socket = socket ?: return null
        return runCatching { socket.getMusicUrl(musicInfo, settings.playQuality).url }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Resolves and starts a track.
     *
     * `player.playerAction` is deliberately not sent here: starting a track is a consequence of
     * server state, not a new command, so echoing it back would be noise.
     */
    private suspend fun loadAndPlay(music: Wire.PlayMusicInfo, startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        resolvingItemId = music.itemId
        val url = resolveUrl(music.musicInfo)
        if (resolvingItemId != music.itemId) return // superseded by a newer request
        resolvingItemId = null

        if (url == null) {
            _state.value = _state.value.copy(
                errorMessage = "无法获取《${music.musicInfo.name}》的播放地址",
            )
            return
        }

        engine.setSource(absoluteUrl(url), startPositionMs)
        if (autoPlay) {
            userWantsPlaying = true
            engine.play()
        }
        publish()
    }

    /**
     * The server hands out source URLs; a same-server path (e.g. the `/api/p_static/...` proxy
     * used for cached or locally stored files) must be made absolute before ExoPlayer sees it.
     */
    private fun absoluteUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (baseUrl.isEmpty()) return url
        return if (url.startsWith("/")) "$baseUrl$url" else "$baseUrl/$url"
    }

    // ------------------------------------------------------------------ server -> client

    private suspend fun handlePlayerEvent(element: JsonElement) {
        val event = runCatching {
            M2cCodec.json.decodeFromJsonElement(Wire.PlayerEvent.serializer(), element)
        }.getOrNull() ?: return

        when (event.action) {
            "progress" -> applyRemoteProgress(event.data)
            "status" -> applyRemoteStatus(event.data)
            "musicChanged" -> applyMusicChanged(event.data)
            "playInfoUpdated" -> applyPlayInfoUpdated(event.data)
            else -> Unit // picUpdated/lyricUpdated/statusText arrive in later phases
        }
    }

    /**
     * The server's progress is the session's truth, and only the client that OWNS playback
     * reports it. Applying our own echo would fight the player, so it is ignored on this side:
     * our positions are published outward, never read back.
     */
    private fun applyRemoteProgress(data: JsonElement?) {
        // No-op by design. Kept explicit so the asymmetry is documented rather than accidental.
        if (data == null) return
    }

    private fun applyRemoteStatus(data: JsonElement?) {
        val array = M2cCodec.asArray(data) ?: return
        val playing = M2cCodec.booleanAt(array, Wire.StatusEventData.INDEX_PLAYING) ?: return
        userWantsPlaying = playing
        if (playing) engine.play() else engine.pause()
        publish()
    }

    private suspend fun applyMusicChanged(data: JsonElement?) {
        val changed = runCatching {
            M2cCodec.json.decodeFromJsonElement(Wire.MusicChangedData.serializer(), data ?: return)
        }.getOrNull() ?: return

        currentIndex = changed.index.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        historyIndex = changed.historyIndex
        val music = queue.getOrNull(currentIndex) ?: return
        loadAndPlay(music, autoPlay = userWantsPlaying)
    }

    private suspend fun applyPlayInfoUpdated(data: JsonElement?) {
        // The server sends a partial PlayInfo; a full resync is cheaper than diffing it and is
        // what makes "list changed on another device" converge here.
        if (data == null) return
        syncFromServer()
    }

    /**
     * An action echoed by the server (originally from this client or another device). It runs
     * through the same dispatch table as local input, which is what keeps multi-device playback
     * identical.
     */
    private fun playerActionOf(element: JsonElement): Wire.PlayerAction? = runCatching {
        M2cCodec.json.decodeFromJsonElement(Wire.PlayerAction.serializer(), element)
    }.getOrNull()

    // ------------------------------------------------------------------ client -> server

    /** Local UI entry point: apply immediately, then report upstream. */
    fun perform(action: String, data: JsonElement? = null) {
        scope.launch {
            val reported = dispatch(action, data)
            if (reported != null) reportAction(reported.first, reported.second)
        }
    }

    private suspend fun reportAction(action: String, data: JsonElement?) {
        runCatching { socket?.playerAction(action, data) }
    }

    /**
     * Applies an action and returns the action that should be reported upstream, or null when
     * nothing should be reported.
     *
     * The return value exists because "toggle" must NOT be forwarded verbatim. The server
     * resolves `toggle` against its OWN `playing` flag, and its flag is only updated by the
     * status we report — so forwarding `toggle` before reporting the resulting state makes the
     * server advance the queue a second time. Reporting the concrete `play`/`pause` we actually
     * performed is what keeps local and server state identical, and it is what the web client
     * does too.
     */
    private suspend fun dispatch(action: String, data: JsonElement?): Pair<String, JsonElement?>? {
        var reported = action to data
        when (action) {
            "play" -> {
                userWantsPlaying = true
                if (!engine.hasSource()) currentTrack()?.let { loadAndPlay(it, autoPlay = true) } else engine.play()
            }

            "pause" -> {
                userWantsPlaying = false
                engine.pause()
            }

            "toggle" -> {
                if (userWantsPlaying) {
                    userWantsPlaying = false
                    engine.pause()
                    reported = "pause" to null
                } else {
                    userWantsPlaying = true
                    if (!engine.hasSource()) {
                        currentTrack()?.let { loadAndPlay(it, autoPlay = true) }
                    } else {
                        engine.play()
                    }
                    reported = "play" to null
                }
            }

            "stop" -> {
                userWantsPlaying = false
                engine.stop()
            }

            "seek" -> {
                val seconds = (data as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
                engine.seekTo((seconds * 1000).toLong())
            }

            "volume" -> {
                val v = (data as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
                engine.setVolume(v.toFloat())
            }

            "volumeMute" -> {
                val muted = (data as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: return null
                engine.setMuted(muted)
            }

            "playbackRate" -> {
                val rate = (data as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
                engine.setPlaybackRate(rate.toFloat())
            }

            "next" -> advance(PlayOrderResolver.next(snapshot(), random), forward = true)
            "prev" -> advance(PlayOrderResolver.prev(snapshot()), forward = false)

            "skip" -> {
                val itemId = (data as? JsonPrimitive)?.content ?: return null
                val index = queue.indexOfFirst { it.itemId == itemId }
                if (index >= 0) {
                    currentIndex = index
                    loadAndPlay(queue[index], autoPlay = userWantsPlaying)
                }
            }

            // Handled by other modules (dislike refreshes the list); accepted so the dispatch
            // table stays total against the server's action vocabulary.
            "collectStatus", "lyricOffset", "dislike" -> Unit

            else -> return null
        }
        publish()
        return reported
    }

    /**
     * Applies a decision produced by [PlayOrderResolver]. [forward] only affects how the history
     * cursor moves, which random mode reads back on the next skip.
     */
    private suspend fun advance(decision: PlayOrderDecision, forward: Boolean) {
        when (decision) {
            is PlayOrderDecision.Play -> {
                currentIndex = queue.indexOfFirst { it.itemId == decision.music.itemId }
                    .takeIf { it >= 0 } ?: currentIndex
                if (forward) historyIndex++ else historyIndex = (historyIndex - 1).coerceAtLeast(0)
                loadAndPlay(decision.music, autoPlay = true)
            }

            PlayOrderDecision.Stop -> {
                userWantsPlaying = false
                engine.stop()
                // Tell the server playback finished so other devices stop showing "playing".
                reportAction("stop", null)
                publish()
            }
        }
    }

    private fun snapshot() = PlayQueueSnapshot(
        list = queue,
        currentIndex = currentIndex,
        method = settings.playMethod,
        historyList = historyList,
        historyIndex = historyIndex,
    )

    private fun currentTrack(): Wire.PlayMusicInfo? = queue.getOrNull(currentIndex)
    // ------------------------------------------------------------------ auto advance

    private suspend fun observeEngine() {
        var handledEndMarker = 0L
        while (currentCoroutineContext().isActive) {
            val marker = engine.ended.value
            if (marker != 0L && marker != handledEndMarker) {
                handledEndMarker = marker
                onTrackEnded()
            }
            val error = engine.errors.value
            if (error != null) {
                _state.value = _state.value.copy(errorMessage = error.message)
            }
            publish()
            delay(300)
        }
    }

    /**
     * The behaviour the phone browser could not deliver: when a track finishes, advance.
     * `singleLoop` is handled inside the resolver, so no special case is needed here.
     */
    private suspend fun onTrackEnded() {
        val decision = PlayOrderResolver.next(snapshot(), random)
        when (decision) {
            is PlayOrderDecision.Play -> {
                currentIndex = queue.indexOfFirst { it.itemId == decision.music.itemId }
                    .takeIf { it >= 0 } ?: currentIndex
                historyIndex++
                historyList = historyList + Wire.HistoryItem(decision.music.itemId, System.currentTimeMillis())
                // Report the advance so other devices follow, then play locally.
                reportAction("skip", JsonPrimitive(decision.music.itemId))
                loadAndPlay(decision.music, autoPlay = true)
            }

            PlayOrderDecision.Stop -> {
                // End of the queue under the current mode. Inlined rather than delegated to
                // `advance`, which is for user-initiated skips and would read oddly here.
                userWantsPlaying = false
                engine.stop()
                reportAction("stop", null)
                publish()
            }
        }
    }

    // ------------------------------------------------------------------ progress reporting

    /**
     * Reports playback position upward on a fixed cadence, matching the web client's 1 s
     * interval. This is what makes the phone's progress visible to the server (and therefore to
     * any other device), and it is driven by a coroutine rather than `timeupdate` so background
     * playback keeps reporting.
     */
    private fun startProgressReporting() {
        progressReportJob?.cancel()
        progressReportJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (!userWantsPlaying || !engine.hasSource()) continue
                val position = engine.currentPositionMs() / 1000.0
                val duration = engine.durationMs.value / 1000.0
                val event = progressEvent(position, duration)
                runCatching { socket?.call(listOf("player", "playerEvent"), listOf(event)) }
            }
        }
    }

    // ------------------------------------------------------------------ state plumbing

    private fun publish() {
        val engineState = _state.value
        val track = currentTrack()
        _state.value = engineState.copy(
            track = track,
            isPlaying = engine.isPlaying.value,
            isBuffering = engine.buffering.value,
            positionMs = engine.positionMs.value,
            durationMs = engine.durationMs.value.takeIf { it > 0 } ?: engineState.durationMs,
            playMethod = settings.playMethod,
            queueSize = queue.size,
            queueIndex = currentIndex,
        )
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    fun release() {
        detachSocket()
        engine.release()
    }

    companion object {
        const val PROGRESS_INTERVAL_MS = 1_000L

        /** `mm:ss`, matching the format the server stores and re-broadcasts. */
        fun formatTime(totalSeconds: Double): String {
            val seconds = totalSeconds.toLong().coerceAtLeast(0L)
            val m = seconds / 60
            val s = seconds % 60
            return "%02d:%02d".format(m, s)
        }

        /**
         * Builds the `progress` event this client reports upward.
         *
         * The shape must match `AnyListen.IPCPlayer.Progress` exactly: the server stores these
         * fields verbatim and re-broadcasts them to other devices, so a renamed key would
         * silently break cross-device progress rather than fail loudly.
         */
        fun progressEvent(positionSeconds: Double, durationSeconds: Double): JsonObject {
            val safeDuration = durationSeconds.coerceAtLeast(0.0)
            val safePosition = positionSeconds.coerceAtLeast(0.0)
            return JsonObject(
                mapOf(
                    "action" to JsonPrimitive("progress"),
                    "data" to JsonObject(
                        mapOf(
                            "nowPlayTime" to JsonPrimitive(safePosition),
                            "maxPlayTime" to JsonPrimitive(safeDuration),
                            "progress" to JsonPrimitive(
                                if (safeDuration > 0) safePosition / safeDuration else 0.0,
                            ),
                            "nowPlayTimeStr" to JsonPrimitive(formatTime(safePosition)),
                            "maxPlayTimeStr" to JsonPrimitive(formatTime(safeDuration)),
                        ),
                    ),
                ),
            )
        }
    }
}
