package dev.waadri.anylisten.domain

import dev.waadri.anylisten.Diag
import dev.waadri.anylisten.data.remote.AuthApi
import dev.waadri.anylisten.data.remote.AuthResult
import dev.waadri.anylisten.data.remote.RpcSocket
import dev.waadri.anylisten.data.remote.RpcState
import dev.waadri.anylisten.data.remote.ServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What the connect screen renders. */
sealed interface ConnectionPhase {
    /** Nothing attempted yet, or the user logged out. */
    data object Disconnected : ConnectionPhase

    /** Credentials are being exchanged for a token. */
    data object Authenticating : ConnectionPhase

    /** Token obtained; the socket is coming up. */
    data object Connecting : ConnectionPhase

    data class Connected(val serverName: String) : ConnectionPhase

    /** Keep retrying; the server went away or the network dropped. */
    data class Reconnecting(val attempt: Int, val reason: String) : ConnectionPhase

    /** Needs user action. [recoverable] drives whether we offer a retry button. */
    data class Failed(val message: String, val recoverable: Boolean) : ConnectionPhase
}

/**
 * Owns the lifetime of one server session: authenticate, open the RPC socket, mark the client
 * ready, and keep the whole thing honest across reconnects.
 */
class ClientSession(
    private val scope: CoroutineScope,
    private val authApi: AuthApi = AuthApi(),
    /**
     * Fired whenever the socket behind this session is replaced or torn down, with the base URL
     * it belongs to. Playback (and later the media service) hangs off this, which keeps the
     * transport knowledge inside this class instead of leaking connection details to callers.
     */
    private val onSocketChanged: (RpcSocket?, String) -> Unit = { _, _ -> },
) {
    private val _phase = MutableStateFlow<ConnectionPhase>(ConnectionPhase.Disconnected)
    val phase: StateFlow<ConnectionPhase> = _phase.asStateFlow()

    private val _rpc = MutableStateFlow<RpcSocket?>(null)

    /** Exposed so later phases can register handlers and issue calls. Null until connected. */
    val rpc: StateFlow<RpcSocket?> = _rpc.asStateFlow()

    private var observeJob: kotlinx.coroutines.Job? = null

    /**
     * Authenticates with the password and brings up the socket.
     *
     * The password path is always used, even when a token is already on file: the server's
     * JWT has no expiry and re-issuing it is a single cheap request, whereas a stale-token
     * path adds a second credential flow that can only ever fail in confusing ways.
     */
    suspend fun connect(serverUrl: String, password: String) {
        disconnectInternal()
        _phase.value = ConnectionPhase.Authenticating
        Diag.event("session.connect", "url" to Diag.url(serverUrl))

        // Every branch below is logged with the same string the UI shows, so a screenshot of the
        // failure and a logcat line are always the same sentence.
        when (val result = authApi.connect(serverUrl, password)) {
            is AuthResult.Success -> startSocket(serverUrl, result.session)
            AuthResult.BadPassword -> fail("访问密码不正确")
            AuthResult.BlockedIp -> fail(
                "该 IP 已被服务端暂时封禁（同一 IP 连续失败超过 10 次），请稍后再试",
            )
            is AuthResult.NotAnyListen -> fail("该地址不是 any-listen 服务端：${result.detail}")
            is AuthResult.Unreachable -> fail(result.detail)
            is AuthResult.Unexpected -> fail("服务端返回异常：${result.detail}")
        }
    }

    /** Surfaces a failure the user must act on, identically on screen and in the log. */
    private fun fail(message: String) {
        Diag.problem("session.failed", message)
        _phase.value = ConnectionPhase.Failed(message, recoverable = true)
    }

    fun disconnect() {
        disconnectInternal()
        _phase.value = ConnectionPhase.Disconnected
    }

    private fun startSocket(serverUrl: String, session: ServerSession) {
        val socket = RpcSocket(
            baseUrl = serverUrl,
            session = session,
            scope = scope,
        )
        _rpc.value = socket
        _phase.value = ConnectionPhase.Connecting
        onSocketChanged(socket, serverUrl)

        observeJob?.cancel()
        observeJob = scope.launch {
            socket.state.collectLatest { state ->
                _phase.value = when (state) {
                    is RpcState.Idle -> ConnectionPhase.Disconnected
                    is RpcState.Connecting -> ConnectionPhase.Connecting
                    is RpcState.Connected -> ConnectionPhase.Connected(state.serverName)
                    is RpcState.Reconnecting -> ConnectionPhase.Reconnecting(state.attempt, state.reason)
                    is RpcState.Failed -> ConnectionPhase.Failed(state.reason, recoverable = true)
                }
                Diag.event(
                    "session.state",
                    "state" to state::class.simpleName,
                    "detail" to when (state) {
                        is RpcState.Connected -> state.serverName
                        is RpcState.Reconnecting -> "attempt=${state.attempt} ${state.reason}"
                        is RpcState.Failed -> state.reason
                        else -> null
                    },
                )
                if (state is RpcState.Connected) {
                    // The server resets `isInited` per socket, so every (re)connect needs this
                    // handshake or all server->client broadcasts are skipped silently.
                    runCatching { socket.markInited() }
                        .onFailure { Diag.problem("session.inited.failed", it.message) }
                }
            }
        }
        socket.start()
    }

    private fun disconnectInternal() {
        observeJob?.cancel()
        observeJob = null
        _rpc.value?.stop()
        _rpc.value = null
        onSocketChanged(null, "")
    }
}
