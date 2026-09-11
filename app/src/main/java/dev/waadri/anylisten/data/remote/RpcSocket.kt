package dev.waadri.anylisten.data.remote

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import dev.waadri.anylisten.Diag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Connection lifecycle as surfaced to the UI. */
sealed interface RpcState {
    data object Idle : RpcState
    data object Connecting : RpcState
    data class Connected(val serverName: String) : RpcState

    /** Recoverable: the session keeps retrying with backoff. */
    data class Reconnecting(val attempt: Int, val reason: String) : RpcState

    /** Terminal for this attempt: caller must re-authenticate. */
    data class Failed(val reason: String) : RpcState
}

/** A call the server made on us (server -> client methods such as `player.playerEvent`). */
fun interface RpcMethodHandler {
    suspend fun invoke(args: List<M2cCodec.JsonElementBox>): JsonElement?
}

/**
 * The message2call RPC session over `GET /api/ipc/socket?m=<token>&t=main`.
 *
 * Mirrors the semantics of the web client's `preload/ws.ts`:
 *  - heartbeats: the server sends the literal `ping` (ignored) and a WS ping frame every 30s,
 *    terminating the socket after 45s of silence. We only need to keep reading; OkHttp
 *    answers protocol-level pings automatically.
 *  - the server closes with code 4100 on a bad frame and 4001 on logout; those are terminal.
 *  - any other close is retried with capped exponential backoff.
 *
 * Threading: [scope] is expected to be a long-lived scope (application or service). Socket
 * callbacks arrive on OkHttp's dispatcher threads and only touch thread-safe state.
 */
class RpcSocket(
    private val baseUrl: String,
    private val session: ServerSession,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = AuthApi.defaultClient(),
    private val reconnect: Boolean = true,
) {
    private val _state = MutableStateFlow<RpcState>(RpcState.Idle)
    val state: StateFlow<RpcState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Wire.PlayerEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Player events pushed by the server (`progress`, `status`, `musicChanged`, ...). */
    val events: SharedFlow<Wire.PlayerEvent> = _events.asSharedFlow()

    private val handlers = ConcurrentHashMap<String, RpcMethodHandler>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
    private val callIdSeq = AtomicLong(0)
    private val attempt = AtomicLong(0)
    private val closedByUs = AtomicBoolean(false)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var connectJob: Job? = null

    /**
     * Registers a server -> client method.
     *
     * IMPORTANT — the key is the *bare* method name, not a dotted path. The server builds its
     * outbound proxies with `createRemoteGroup('player', ...)` for queuing only; the group
     * name is NOT part of the wire path. `socket.remoteQueuePlayer.playerAction(action)` is
     * sent as path `["playerAction"]`. Outgoing calls are the ones that carry a group prefix,
     * e.g. `["player","getPlayInfo"]`, because that matches the server's `exposeObj` nesting.
     */
    fun on(method: String, handler: RpcMethodHandler) {
        handlers[method] = handler
    }

    fun start() {
        if (connectJob?.isActive == true) return
        closedByUs.set(false)
        connectJob = scope.launch { connectLoop() }
    }

    fun stop() {
        closedByUs.set(true)
        connectJob?.cancel()
        connectJob = null
        socket?.close(CLOSE_NORMAL, "bye")
        socket = null
        failAllPending("session stopped")
        _state.value = RpcState.Idle
    }

    /**
     * Calls a server method. Suspends until the matching response arrives.
     *
     * @param path nested path into the server's `exposeObj`, e.g. `["player","getPlayInfo"]`.
     * @throws RpcException when the server reports an error, or the call times out.
     */
    suspend fun call(
        path: List<String>,
        args: List<JsonElement> = emptyList(),
        timeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    ): JsonElement? {
        val method = path.joinToString(".")
        val ws = socket ?: run {
            Diag.problem("rpc.rejected", "$method — socket is not open")
            throw RpcException("尚未连接到服务器")
        }
        val callId = "${method}_${callIdSeq.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonElement?>()
        pending[callId] = deferred
        Diag.d("rpc.send", method)

        return try {
            val sent = ws.send(M2cCodec.encodeRequest(callId, path, args))
            if (!sent) throw RpcException("消息发送失败，连接可能已断开")
            kotlinx.coroutines.withTimeout(timeoutMs) { deferred.await() }
                .also { Diag.d("rpc.ok", method) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Diag.problem("rpc.timeout", "$method after ${timeoutMs}ms")
            throw RpcException("调用 $method 超时")
        } catch (e: RpcException) {
            // The server answered with an error. This is the line that explains a call that
            // "succeeded" from the UI's point of view while returning nothing useful.
            Diag.problem("rpc.error", "$method — ${e.message}")
            throw e
        } finally {
            pending.remove(callId)
        }
    }

    /**
     * Marks this client ready on the server. Until this is called the socket's `isInited` flag
     * stays false and the server silently skips it when broadcasting player state, list
     * changes and theme updates — a connected but permanently silent client.
     *
     * `packages/web-server/src/app/renderer/winMain/rendererEvent/app.ts` sets it:
     * `async inited(event) { event.isInited = true; ... }`
     *
     * Must be repeated after every reconnect: `isInited` is per-socket server state and is
     * reset to false when the connection is established.
     */
    suspend fun markInited() {
        call(listOf("app", "inited"))
    }

    /** Convenience for the common `player.playerAction({action, data})` shape. */
    suspend fun playerAction(action: String, data: JsonElement? = null) =
        call(OUT_PLAYER_ACTION.split("."), listOf(M2cCodec.actionPayload(action, data)))

    suspend fun getPlayInfo(): Wire.PlayInfo {
        val result = call(OUT_PLAYER_GET_PLAY_INFO.split("."))
            ?: throw RpcException("getPlayInfo 返回空结果")
        return M2cCodec.json.decodeFromJsonElement(Wire.PlayInfo.serializer(), result)
    }

    suspend fun getMusicUrl(musicInfo: Wire.MusicInfo, quality: String? = null): Wire.MusicUrlInfo {
        val payload = M2cCodec.json.encodeToJsonElement(
            Wire.GetMusicUrlInfo.serializer(),
            Wire.GetMusicUrlInfo(musicInfo = musicInfo, quality = quality),
        )
        val result = call(OUT_MUSIC_GET_URL.split("."), listOf(payload))
            ?: throw RpcException("getMusicUrl 返回空结果")
        return M2cCodec.json.decodeFromJsonElement(Wire.MusicUrlInfo.serializer(), result)
    }

    /**
     * `music.getMusicLyric(info)`: the lyric for a track.
     *
     * Returns null rather than throwing when the server has no lyric, which is the normal case for
     * a track nobody has tagged or fetched lyrics for. The server also returns null for a local
     * track that belongs to a different device, since only the owning machine can read its file.
     *
     * The payload is a single LRC string with base64 tags; see
     * [dev.waadri.anylisten.domain.LyricsParser] for what is inside it.
     */
    suspend fun getMusicLyric(musicInfo: Wire.MusicInfo, isRefresh: Boolean = false): Wire.LyricInfo? {
        val payload = M2cCodec.json.encodeToJsonElement(
            Wire.GetMusicPicInfo.serializer(),
            Wire.GetMusicPicInfo(musicInfo = musicInfo, isRefresh = isRefresh),
        )
        val result = call(OUT_MUSIC_GET_LYRIC.split("."), listOf(payload)) ?: return null
        if (result is JsonNull) return null
        return runCatching {
            M2cCodec.json.decodeFromJsonElement(Wire.MusicLyricInfo.serializer(), result).info
        }.getOrNull()
    }

    /**
     * `app.setSetting(partial)` — writes server-side settings.
     *
     * Kept for read-only-ish server capabilities (lyric preferences and similar). Playback state
     * must NOT be written through here: this client owns its queue, playhead and play mode, and
     * writing them upstream would reintroduce the server round trip the local design removed.
     */
    suspend fun setSetting(setting: JsonObject) {
        call(OUT_APP_SET_SETTING.split("."), listOf(setting))
    }

    // ------------------------------------------------------------------ music library

    /** `list.getAllUserLists()`: the built-in lists plus the user's own. */
    suspend fun getAllUserLists(): Wire.MyAllList {
        val result = call(OUT_LIST_GET_ALL_USER_LISTS.split("."))
            ?: return Wire.MyAllList()
        return M2cCodec.json.decodeFromJsonElement(Wire.MyAllList.serializer(), result)
    }

    /** `list.getListMusics(listId)`: the tracks of one list. */
    suspend fun getListMusics(listId: String): List<ListMusicEntry> {
        val result = call(OUT_LIST_GET_MUSICS.split("."), listOf(JsonPrimitive(listId)))
            ?: return emptyList()
        return M2cCodec.json.decodeFromJsonElement(
            ListSerializer(ListMusicEntry.serializer()),
            result,
        )
    }

    /**
     * `player.playListAction({action: 'set', data})` — replaces the session's play queue.
     *
     * This is the only way a client can start a specific list: the queue's authoritative copy
     * lives on the server and this call broadcasts the replacement to every ready client. It
     * does NOT start playback; the caller plays locally, exactly as the web client does.
     */
    suspend fun setPlayList(listId: String?, list: List<Wire.PlayMusicInfo>, source: String) {
        val payload = M2cCodec.json.encodeToJsonElement(
            Wire.PlayListSetAction.serializer(),
            Wire.PlayListSetAction(listId = listId, list = list, source = source),
        )
        call(OUT_PLAYER_PLAY_LIST_ACTION.split("."), listOf(M2cCodec.actionPayload("set", payload)))
    }

    // ------------------------------------------------------------------ internals

    private suspend fun connectLoop() {
        val wsUrl = buildSocketUrl(baseUrl, session.token)
        // The URL carries the JWT in its query string, so only its shape is recorded — this is the
        // single most useful line when a socket handshake is refused, and the easiest to leak.
        Diag.event(
            "socket.url",
            "base" to Diag.url(baseUrl),
            "path" to "/api/ipc/socket",
            "token" to Diag.secret(session.token),
        )
        while (!closedByUs.get()) {
            val attemptNo = attempt.incrementAndGet().toInt()
            _state.value = if (attemptNo == 1) RpcState.Connecting else RpcState.Reconnecting(attemptNo - 1, "连接中断")
            Diag.event("socket.attempt", "n" to attemptNo)

            val settled = CompletableDeferred<CloseReason>()
            val request = Request.Builder().url(wsUrl).build()
            socket = client.newWebSocket(request, Listener(settled))

            val reason = settled.await()
            socket = null
            failAllPending("连接已断开")
            Diag.problem("socket.closed", "code=${reason.code} detail=${reason.detail}")

            if (closedByUs.get()) return
            if (reason.code == CLOSE_LOGOUT) {
                _state.value = RpcState.Failed("已被服务端登出，请重新登录")
                return
            }
            if (!reconnect) {
                _state.value = RpcState.Failed(reason.detail)
                return
            }
            if (reason.code == CLOSE_FAILED) {
                // Protocol-level rejection (bad auth or malformed frames): retrying blindly
                // would hammer the server's per-IP failure counter.
                _state.value = RpcState.Failed("连接被服务端拒绝：${reason.detail}")
                return
            }

            _state.value = RpcState.Reconnecting(attemptNo, reason.detail)
            val backoff = backoffMs(attemptNo)
            Diag.event("socket.retry", "in" to "${backoff}ms")
            delay(backoff)
        }
    }

    private fun backoffMs(attemptNo: Int): Long = backoffMsFor(attemptNo)

    private fun failAllPending(reason: String) {
        val snapshot = pending.entries.toList()
        pending.clear()
        snapshot.forEach { (_, deferred) -> deferred.completeExceptionally(RpcException(reason)) }
    }

    private fun handleText(text: String) {
        if (text == "ping") return // application-level keepalive from the server
        when (val incoming = M2cCodec.decode(text)) {
            is M2cCodec.Incoming.Response -> {
                val deferred = pending.remove(incoming.callId) ?: return
                val error = incoming.errorMessage
                if (error != null) {
                    deferred.completeExceptionally(RpcException(error))
                } else {
                    deferred.complete(incoming.result?.value ?: JsonNull)
                }
            }

            is M2cCodec.Incoming.Callback -> scope.launch { handleServerCall(incoming.callback) }

            is M2cCodec.Incoming.Request -> scope.launch { handleServerCall(incoming.request) }

            is M2cCodec.Incoming.CallbackResponse -> Unit // we never issue proxy callbacks

            is M2cCodec.Incoming.Malformed -> {
                // A frame we cannot parse means a protocol mismatch; surface it and let the
                // socket layer reconnect rather than silently ignoring server pushes.
                _events.tryEmit(Wire.PlayerEvent(action = EVENT_PROTOCOL_ERROR, data = null))
            }
        }
    }

    private suspend fun handleServerCall(call: M2cCodec.IncomingRequest) {
        val handler = handlers[call.methodName]
        if (handler == null) {
            // Answer with an error rather than `null`: a null result would look like a
            // legitimate value to the server's awaiting promise.
            socket?.send(
                M2cCodec.encodeResponse(call.callId, "client has no handler for ${call.methodName}", null),
            )
            return
        }

        val result = try {
            handler.invoke(call.args)
        } catch (e: Exception) {
            socket?.send(M2cCodec.encodeResponse(call.callId, e.message ?: "client handler failed", null))
            return
        }
        socket?.send(M2cCodec.encodeResponse(call.callId, null, result))

        if (call.methodName == INCOMING_PLAYER_EVENT) {
            decodePlayerEvent(call.args.firstOrNull()?.value)?.let { _events.tryEmit(it) }
        }
    }

    private suspend fun handleServerCall(callback: M2cCodec.IncomingCallback) {
        // Callback-style invocations are not used by this client; acknowledge so the server's
        // pending promise does not time out.
        socket?.send(M2cCodec.encodeCallbackResponse(callback.callbackName, null, null))
    }

    private fun decodePlayerEvent(element: JsonElement?): Wire.PlayerEvent? {
        if (element == null || element is JsonNull) return null
        return runCatching {
            M2cCodec.json.decodeFromJsonElement(Wire.PlayerEvent.serializer(), element)
        }.getOrNull()
    }

    private inner class Listener(private val settled: CompletableDeferred<CloseReason>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt.set(0)
            _state.value = RpcState.Connected(session.serverName)
            Diag.d("socket.open", session.serverName)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleText(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(CLOSE_NORMAL, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            settled.complete(CloseReason(code, reason.ifEmpty { "连接关闭" }))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val httpCode = response?.code
            val detail = when (httpCode) {
                401 -> "鉴权失败（401）"
                null -> t.message ?: t.javaClass.simpleName
                else -> "HTTP $httpCode"
            }
            // A socket that never opens leaves no other trace at all: no state change, no HTTP
            // log, nothing the user can see beyond a spinner.
            Diag.problem(
                "socket.failure",
                "http=$httpCode throwable=${t.javaClass.simpleName} message=${t.message}",
            )
            settled.complete(CloseReason(if (httpCode == 401) CLOSE_LOGOUT else CLOSE_ABNORMAL, detail))
        }
    }

    private data class CloseReason(val code: Int, val detail: String)

    class RpcException(message: String) : Exception(message)

    companion object {
        /**
         * Server -> client method names arrive WITHOUT a group prefix. The server's outbound
         * proxy is created via `createRemoteGroup('player', ...)`, which only configures local
         * queueing; `socket.remoteQueuePlayer.playerAction(action)` therefore travels as path
         * `["playerAction"]`, not `["player","playerAction"]`.
         */
        const val INCOMING_PLAYER_EVENT = "playerEvent"
        const val INCOMING_PLAYER_ACTION = "playerAction"
        const val INCOMING_PLAY_LIST_ACTION = "playListAction"
        const val INCOMING_PLAY_HISTORY_LIST_ACTION = "playHistoryListAction"
        const val INCOMING_SETTING_CHANGED = "settingChanged"

        /** Outgoing paths, which DO follow the server's `exposeObj` nesting. */
        const val OUT_PLAYER_GET_PLAY_INFO = "player.getPlayInfo"
        const val OUT_PLAYER_ACTION = "player.playerAction"
        const val OUT_PLAYER_PLAY_LIST_ACTION = "player.playListAction"
        const val OUT_LIST_GET_ALL_USER_LISTS = "list.getAllUserLists"
        const val OUT_LIST_GET_MUSICS = "list.getListMusics"
        const val OUT_MUSIC_GET_URL = "music.getMusicUrl"
        const val OUT_MUSIC_GET_PIC = "music.getMusicPic"
        const val OUT_MUSIC_GET_LYRIC = "music.getMusicLyric"
        const val OUT_APP_INITED = "app.inited"
        const val OUT_APP_SET_SETTING = "app.setSetting"

        const val EVENT_PROTOCOL_ERROR = "protocolError"

        /** `IPC_CLOSE_CODE` from `shared/common/constants.ts`. */
        const val CLOSE_NORMAL = 1000
        const val CLOSE_LOGOUT = 4001
        const val CLOSE_FAILED = 4100
        const val CLOSE_ABNORMAL = 1006

        const val DEFAULT_CALL_TIMEOUT_MS = 20_000L

        /**
         * `GET <ws|wss>://<host>/api/ipc/socket?m=<url-encoded JWT>&t=main`
         *
         * Extracted so the exact URL the server's upgrade handler expects is unit-testable.
         * `t=main` is mandatory: the server drops the connection unless `t` is a known window
         * type (`main`/`desktopLyric`).
         */
        fun buildSocketUrl(baseUrl: String, token: String): String {
            val scheme = if (baseUrl.startsWith("https://")) "wss" else "ws"
            val hostPath = baseUrl.substringAfter("://").trimEnd('/')
            val encodedToken = java.net.URLEncoder.encode(token, "UTF-8")
            return "$scheme://$hostPath${AuthApi.API_PREFIX}${AuthApi.IPC_PATH}/socket?m=$encodedToken&t=main"
        }

        /** Mirrors the web client: 2s, +3s every two attempts, capped at 60s. */
        fun backoffMsFor(attemptNo: Int): Long {
            val safeAttempt = attemptNo.coerceAtLeast(1)
            return (2_000L + (safeAttempt / 2) * 3_000L).coerceAtMost(60_000L)
        }
    }
}
