package dev.waadri.anylisten.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import dev.waadri.anylisten.Diag
import dev.waadri.anylisten.MainActivity
import dev.waadri.anylisten.data.config.ConfigStore
import dev.waadri.anylisten.data.config.PlaybackPreferences
import dev.waadri.anylisten.data.remote.Library
import dev.waadri.anylisten.data.remote.PlayMethod
import dev.waadri.anylisten.data.remote.RpcState
import dev.waadri.anylisten.data.remote.RpcSocket
import dev.waadri.anylisten.data.remote.ServerUrl
import dev.waadri.anylisten.data.remote.Wire
import dev.waadri.anylisten.domain.PlayOrderDecision
import dev.waadri.anylisten.domain.PlayOrderResolver
import dev.waadri.anylisten.domain.PlayQueueSnapshot
import dev.waadri.anylisten.domain.ResumePoint
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /**
     * Cover art, already resolved against the server. The raw value from the track is a
     * document-relative path that no image loader can fetch, so the resolved form is carried in
     * state rather than left for each screen to remember to do; see
     * [dev.waadri.anylisten.data.remote.ServerUrl].
     */
    val artworkUrl: String? = null,

    /** Name of the library list the queue came from, for the "playing from" line. */
    val sourceListName: String = "",
) {
    val positionSeconds: Double get() = positionMs / 1000.0
    val durationSeconds: Double get() = durationMs / 1000.0
    val progressFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * The player. Owns the queue, the playhead, the volume and the playback mode — all of it local.
 *
 * ## Where this differs from the web client, on purpose
 *
 * any-listen's web build treats the server as the session's source of truth: the queue, the
 * current index and the play mode live there, and clients report progress and status back. This
 * app does **not** do that. The server is used purely as a music provider:
 *
 * | server provides | this device owns |
 * |---|---|
 * | playlists (`list.getAllUserLists`) | the play queue |
 * | tracks (`list.getListMusics`) | which track is current |
 * | stream URLs (`music.getMusicUrl`) | the playhead |
 * | artwork and lyrics | play order (listLoop/random/singleLoop/list) |
 *
 * Output volume is owned by the platform (system media volume), not by this app.
 *
 * Nothing about playback is ever sent upstream: no `progress`, no `status`, no `playListAction`,
 * no settings writes. The play mode and the resume point persist locally in [ConfigStore].
 *
 * The tradeoff, stated so it is not a surprise later: there is no cross-device continuity. The
 * phone and the desktop app play independently. That is the point — it is also why the progress
 * bar and auto-advance work here after failing in the phone browser, where the playhead depended
 * on the server round trip plus a WebAudio graph the mobile browser had suspended.
 *
 * `playerEvent` / `playerAction` are still registered (as deliberate no-ops) because the server
 * broadcasts them to every ready client and would otherwise log an error per broadcast. Nothing
 * in this class reacts to them.
 */
class PlaybackRepository(
    appContext: Context,
    private val scope: CoroutineScope,
    private val configStore: ConfigStore,
) {
    private val engine = AudioEngine(appContext, scope)
    private val random = Random()

    /** Application context, used to raise the foreground service and build the media session. */
    private val context: Context = appContext.applicationContext

    /**
     * Published to the system through [PlaybackService]. Owned here rather than by the service
     * so the session and the player can never come from different places.
     */
    var mediaSession: MediaSession? = null
        private set

    private var socket: RpcSocket? = null
    private var baseUrl: String = ""
    private var wireJobs = mutableListOf<Job>()

    private var queue: List<Wire.PlayMusicInfo> = emptyList()
    private var currentIndex = 0
    private var historyIndex = 0
    private var sourceListId: String? = null
    private var sourceListName: String = ""

    /** Local playback preferences, mirrored from [ConfigStore]. */
    private var prefs = PlaybackPreferences()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var userWantsPlaying = false
    private var progressReportJob: Job? = null
    private var resumeJob: Job? = null

    /**
     * True once the resume point has either been restored or ruled out for this session.
     *
     * Deliberately not set on a failed attempt: that failure is almost always a dropped connection,
     * and the point is to retry when it comes back.
     */
    private var resumeSettled = false

    /** Set while a resolved URL is in flight so a late arrival cannot clobber a newer request. */
    private var resolvingItemId: String? = null

    init {
        // Preferences are the single source of truth for the play mode, so they are loaded once
        // and then only ever written through the setters below.
        scope.launch {
            prefs = configStore.currentPlayback()
            publish()
        }
    }

    // ------------------------------------------------------------------ transport

    /**
     * Binds (or unbinds) the RPC socket.
     *
     * Only the music-source calls are used now, but the socket is still needed for authentication
     * and for `app.inited`. It is kept deliberately free of playback state.
     */
    fun attachSocket(socket: RpcSocket?, baseUrl: String = "") {
        detachSocket()
        this.socket = socket ?: return
        this.baseUrl = baseUrl.trimEnd('/')

        // Inert handlers. The server broadcasts playback messages to every ready client; leaving
        // these unregistered would make it log an error for each one. This client ignores them
        // because playback is local.
        socket.on(RpcSocket.INCOMING_PLAYER_EVENT) { null }
        socket.on(RpcSocket.INCOMING_PLAYER_ACTION) { null }
        socket.on(RpcSocket.INCOMING_PLAY_LIST_ACTION) { null }
        socket.on(RpcSocket.INCOMING_SETTING_CHANGED) { null }

        wireJobs += scope.launch { observeEngine() }
        observeResume(socket)
        startPositionTracking()
    }

    private fun detachSocket() {
        wireJobs.forEach { it.cancel() }
        wireJobs.clear()
        progressReportJob?.cancel()
        progressReportJob = null
        resumeJob?.cancel()
        resumeJob = null
        socket = null
    }

    // ------------------------------------------------------------------ music source

    /**
     * The configured server origin, for collaborators that need to resolve the server's
     * not-quite-absolute URLs. Empty before the socket is attached.
     */
    fun serverBaseUrl(): String = baseUrl

    suspend fun loadLists(): Result<List<Library.ListSummary>> = runCatching {
        val socket = socket ?: throw IllegalStateException("尚未连接服务端")
        Library.summaries(socket.getAllUserLists(), baseUrl)
    }

    /**
     * Loads a list's tracks into a queue WITHOUT starting playback.
     *
     * `itemId` is `listId::trackId` because a song can legitimately appear in several lists and
     * would otherwise collide.
     */
    suspend fun loadListMusics(listId: String): Result<List<Wire.PlayMusicInfo>> = runCatching {
        val socket = socket ?: throw IllegalStateException("尚未连接服务端")
        socket.getListMusics(listId).map { entry ->
            Wire.PlayMusicInfo(
                itemId = "$listId::${entry.id}",
                musicInfo = entry.toMusicInfo(),
                listId = listId,
                source = SOURCE_SONG_LIST,
                playLater = false,
                played = false,
            )
        }
    }

    private suspend fun resolveUrl(musicInfo: Wire.MusicInfo): String? {
        val socket = socket ?: run {
            Diag.problem("url.no_socket", musicInfo.name)
            return null
        }
        val resolved = runCatching { socket.getMusicUrl(musicInfo, prefs.playQuality).url }
            .getOrElse { error ->
                // Silent before this: a failure here left the transport row doing nothing at all.
                Diag.problem("url.resolve.failed", "${musicInfo.name} — ${error.javaClass.simpleName}: ${error.message}")
                return null
            }
            ?.takeIf { it.isNotBlank() }
        if (resolved == null) {
            Diag.problem("url.resolve.blank", musicInfo.name)
            return null
        }
        val absolute = absoluteUrl(resolved)
        Diag.event(
            "url.resolved",
            "track" to musicInfo.name,
            "raw" to Diag.url(resolved),
            "absolute" to Diag.url(absolute),
            "madeAbsolute" to (absolute != resolved),
        )
        return absolute
    }

    /**
     * The server hands out two flavours of not-quite-absolute URL: the `al-ps-host:` virtual
     * protocol, and same-server relative paths for proxied or locally stored files. Both must be
     * resolved before ExoPlayer sees them; see [ServerUrl].
     */
    private fun absoluteUrl(url: String): String = ServerUrl.resolve(url, baseUrl) ?: url

    // ------------------------------------------------------------------ playback control

    /**
     * Replaces the queue with [list] and starts at [index]. The queue is local; the server is not
     * told about it.
     */
    suspend fun playFromList(
        listId: String,
        listName: String,
        list: List<Wire.PlayMusicInfo>,
        index: Int,
    ) {
        val track = list.getOrNull(index) ?: return
        queue = list
        currentIndex = index
        historyIndex = 0
        sourceListId = listId
        sourceListName = listName
        userWantsPlaying = true
        publish()

        configStore.saveResumePoint(listId, index, 0L)
        loadAndPlay(track, autoPlay = true)
    }

    fun play() {
        if (!engine.hasSource()) {
            queue.getOrNull(currentIndex)?.let { track ->
                scope.launch { loadAndPlay(track, autoPlay = true) }
            }
            return
        }
        userWantsPlaying = true
        engine.play()
        publish()
    }

    fun pause() {
        userWantsPlaying = false
        engine.pause()
        scope.launch { persistResumePoint() }
        publish()
    }

    fun togglePlay() {
        if (userWantsPlaying) pause() else play()
    }

    fun next() {
        advance(PlayOrderResolver.next(snapshot(), random), forward = true)
    }

    fun previous() {
        advance(PlayOrderResolver.prev(snapshot()), forward = false)
    }

    fun seekTo(positionMs: Long) {
        engine.seekTo(positionMs)
        scope.launch { persistResumePoint() }
        publish()
    }

    fun stop() {
        userWantsPlaying = false
        engine.stop()
        publish()
    }

    /** Jumps to a specific track in the current queue. */
    fun skipToIndex(index: Int) {
        val track = queue.getOrNull(index) ?: return
        currentIndex = index
        userWantsPlaying = true
        scope.launch { loadAndPlay(track, autoPlay = true) }
    }

    // ------------------------------------------------------------------ local settings

    /**
     * Advances the play mode. Purely local: nothing is written to the server.
     *
     * Volume is deliberately absent from this class. Output level is owned by the platform — the
     * hardware volume keys and the system volume panel drive the media stream directly, which is
     * what users expect from a music player. An in-app slider would multiply with that, so the
     * same gesture would appear to do nothing once either end reached zero.
     */
    fun cyclePlayMethod() {
        setPlayMethod(prefs.playMethod.next())
    }

    fun setPlayMethod(method: PlayMethod) {
        if (method == prefs.playMethod) return
        prefs = prefs.copy(playMethod = method)
        scope.launch { configStore.savePlayMethod(method) }
        publish()
    }

    fun setResumeOnLaunch(enabled: Boolean) {
        prefs = prefs.copy(resumeOnLaunch = enabled)
        scope.launch { configStore.saveResumeOnLaunch(enabled) }
        publish()
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    // ------------------------------------------------------------------ internals

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

        engine.setSource(
            absoluteUrl(url),
            startPositionMs,
            mediaMetadataFor(music, ServerUrl.resolve(music.musicInfo.meta.picUrl, baseUrl)),
        )
        ensureMediaSession()
        if (autoPlay) {
            userWantsPlaying = true
            startPlaybackService()
            engine.play()
        }
        publish()
    }

    private fun advance(decision: PlayOrderDecision, forward: Boolean) {
        when (decision) {
            is PlayOrderDecision.Play -> {
                val index = queue.indexOfFirst { it.itemId == decision.music.itemId }
                if (index >= 0) currentIndex = index
                if (forward) historyIndex++ else historyIndex = (historyIndex - 1).coerceAtLeast(0)
                userWantsPlaying = true
                scope.launch { loadAndPlay(decision.music, autoPlay = true) }
            }

            PlayOrderDecision.Stop -> {
                userWantsPlaying = false
                engine.stop()
                publish()
            }
        }
    }

    private fun snapshot() = PlayQueueSnapshot(
        list = queue,
        currentIndex = currentIndex,
        method = prefs.playMethod,
        historyIndex = historyIndex,
    )

    /**
     * Watches the engine for end-of-track and errors and mirrors them into UI state.
     *
     * Track end is where auto-advance happens — the behaviour the phone browser could not deliver,
     * because its `ended` event depended on a WebAudio graph the mobile browser had suspended.
     */
    private suspend fun observeEngine() {
        var handledEndMarker = 0L
        while (currentCoroutineContext().isActive) {
            val marker = engine.ended.value
            if (marker != 0L && marker != handledEndMarker) {
                handledEndMarker = marker
                onTrackEnded()
            }
            engine.errors.value?.let { error ->
                if (_state.value.errorMessage != error.message) {
                    _state.value = _state.value.copy(errorMessage = error.message)
                }
            }
            publish()
            delay(300)
        }
    }

    private suspend fun onTrackEnded() {
        when (val decision = PlayOrderResolver.next(snapshot(), random)) {
            is PlayOrderDecision.Play -> {
                val index = queue.indexOfFirst { it.itemId == decision.music.itemId }
                if (index >= 0) currentIndex = index
                historyIndex++
                loadAndPlay(decision.music, autoPlay = true)
            }

            PlayOrderDecision.Stop -> {
                userWantsPlaying = false
                engine.stop()
                publish()
            }
        }
    }

    /**
     * Persists the playhead periodically so a relaunch can resume.
     *
     * This is a LOCAL write, once every few seconds — not the once-per-second upload the web client
     * performs. Writing on every position tick would hammer the disk for no benefit.
     */
    private fun startPositionTracking() {
        progressReportJob?.cancel()
        progressReportJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(RESUME_SAVE_INTERVAL_MS)
                if (userWantsPlaying) persistResumePoint()
            }
        }
    }

    private suspend fun persistResumePoint() {
        configStore.saveResumePoint(sourceListId, currentIndex, engine.currentPositionMs())
    }

    /**
     * Restores the last queue once the socket can actually serve requests.
     *
     * ## Why this waits for a connection instead of loading immediately
     *
     * `ClientSession` hands the socket over *before* calling `start()` on it, so when [attachSocket]
     * runs the socket is still `Idle` and every call fails at once. Loading the saved list there
     * made resume-on-launch look implemented while never working a single time — there was no
     * error, the queue just stayed empty.
     *
     * Waiting for `Connected` also makes the feature self-healing: if the first connect attempt
     * fails, the saved point stays pending and is restored when a retry succeeds.
     */
    private fun observeResume(socket: RpcSocket) {
        resumeJob?.cancel()
        resumeJob = scope.launch {
            socket.state
                .filterIsInstance<RpcState.Connected>()
                .collect { applyResumePoint() }
        }
    }

    private suspend fun applyResumePoint() {
        if (queue.isNotEmpty()) {
            // The user has already started something. Overwriting the queue here would yank the
            // music out from under them.
            resumeSettled = true
            return
        }

        val saved = configStore.currentPlayback()
        // Mode and the switch are local preferences, not playback state, so they are adopted even
        // when nothing is restored.
        prefs = prefs.copy(playMethod = saved.playMethod, resumeOnLaunch = saved.resumeOnLaunch)
        Diag.event(
            "resume.check",
            "savedList" to saved.lastListId,
            "track" to saved.lastTrackIndex,
            "positionMs" to saved.lastPositionMs,
            "enabled" to saved.resumeOnLaunch,
        )

        if (!saved.resumeOnLaunch) {
            resumeSettled = true
            publish()
            return
        }
        val listId = saved.lastListId
        if (listId.isNullOrBlank()) {
            resumeSettled = true
            publish()
            return
        }

        // Left pending on failure: a dropped connection is the common cause, and the next Connected
        // emission should try again rather than the feature silently giving up.
        val restored = loadListMusics(listId).getOrNull()
        if (restored.isNullOrEmpty()) {
            Diag.problem("resume.load.empty", "list=$listId — keeping the point pending for the next connect")
            publish()
            return
        }

        resumeSettled = true
        val point = ResumePoint(
            listId = listId,
            trackIndex = saved.lastTrackIndex,
            positionMs = saved.lastPositionMs,
        )
        queue = restored
        currentIndex = point.indexFor(restored.size) ?: 0
        sourceListId = listId
        historyIndex = 0
        userWantsPlaying = false
        publish()

        // Prepared but not played: launching into sound from a pocket is unwelcome, and pressing
        // play continues from exactly where the last session stopped.
        loadAndPlay(
            restored[currentIndex],
            startPositionMs = point.positionFor(),
            autoPlay = false,
        )
    }

    // ------------------------------------------------------------------ background playback

    /**
     * Builds the [MediaSession] that the foreground service publishes to the system.
     *
     * It wraps the same ExoPlayer instance the UI drives, so lock-screen and notification controls
     * go through one audio pipeline rather than a parallel one.
     */
    private fun ensureMediaSession() {
        if (mediaSession != null) return
        val player = engine.playerOrNull() ?: return
        val activityIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(context, player)
            .setSessionActivity(activityIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(
                            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                                // The queue is ours and is replaced only by the browse screen, so
                                // a platform controller must not add or replace items behind our
                                // back — that would desynchronise `currentIndex` from the audio.
                                .remove(Player.COMMAND_SET_MEDIA_ITEM)
                                .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                                .build(),
                        )
                        .build()
            })
            .build()
    }

    private fun startPlaybackService() {
        runCatching { context.startForegroundService(Intent(context, PlaybackService::class.java)) }
            .onFailure { /* foreground-start restrictions; playback still works in-process */ }
    }

    /** Called when the user swipes the app away: stop rather than keep playing invisibly. */
    fun stopForTaskRemoval() {
        userWantsPlaying = false
        engine.stop()
        publish()
    }

    // ------------------------------------------------------------------ state plumbing

    private fun publish() {
        val previous = _state.value
        val track = queue.getOrNull(currentIndex)
        _state.value = previous.copy(
            track = track,
            isPlaying = engine.isPlaying.value,
            isBuffering = engine.buffering.value,
            positionMs = engine.positionMs.value,
            // The engine reports 0 before a track is prepared; keeping the last known duration
            // avoids the seek bar collapsing to 00:00 between tracks.
            durationMs = engine.durationMs.value.takeIf { it > 0 } ?: previous.durationMs,
            playMethod = prefs.playMethod,
            queueSize = queue.size,
            queueIndex = currentIndex,
            artworkUrl = ServerUrl.resolve(track?.musicInfo?.meta?.picUrl, baseUrl),
            sourceListName = sourceListName,
        )
    }

    fun release() {
        detachSocket()
        mediaSession?.release()
        mediaSession = null
        engine.release()
    }

    companion object {
        /** How often the resume point is written locally. Not a sync interval. */
        const val RESUME_SAVE_INTERVAL_MS = 5_000L

        /**
         * `Player.SourceType` for a track started from a user playlist. The server uses this to
         * decide how to resolve a stream URL, so it must match the value the web client sends.
         */
        const val SOURCE_SONG_LIST = "songlist"

        /** `mm:ss`, matching the format the server uses for track durations. */
        fun formatTime(totalSeconds: Double): String {
            val seconds = totalSeconds.toLong().coerceAtLeast(0L)
            val m = seconds / 60
            val s = seconds % 60
            return "%02d:%02d".format(m, s)
        }
    }
}
