package dev.waadri.anylisten.data.remote

import dev.waadri.anylisten.Diag
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Outcome of a connect attempt, mapped from the server's documented responses. */
sealed interface AuthResult {
    data class Success(val session: ServerSession) : AuthResult

    /** Password rejected. Server replies 401 `Auth failed`. */
    data object BadPassword : AuthResult

    /** Server replies 403 `Blocked IP` after 10 failed attempts from one IP. */
    data object BlockedIp : AuthResult

    /** Reached the host but it is not an any-listen web server we understand. */
    data class NotAnyListen(val detail: String) : AuthResult

    /** DNS/TCP/TLS/timeout — the host is unreachable or refused the connection. */
    data class Unreachable(val detail: String) : AuthResult

    data class Unexpected(val detail: String) : AuthResult
}

/** A successfully authenticated server, as persisted between app launches. */
data class ServerSession(
    val serverId: String,
    val serverName: String,
    val token: String,
)

/**
 * Implemented from `packages/web-server/src/preload/auth.ts`.
 *
 * ```
 * GET  /api/ipc/id                      -> "OjppZDo6-<serverId>"
 * POST /api/ipc/ah  headers m, s        -> header `token: <JWT>`, body "Hello~::^-^::~v1~\n<name>"
 * ```
 *
 * where `m = sha256Hex(password + s)` and `s` is a random per-request salt. The password
 * itself never crosses the wire.
 *
 * ## Threading
 *
 * [connect] is `suspend` AND owns its dispatcher. OkHttp's synchronous `execute()` blocks, and this
 * used to run directly on `Dispatchers.Main` because `viewModelScope` launches there — so the
 * connect button failed with `NetworkOnMainThreadException` on every device and every server, while
 * every unit test stayed green, because a JVM test has no main-thread check.
 *
 * Dispatching in here rather than at the call site is deliberate. An earlier version shipped a
 * `connectOnIo()` wrapper that no caller ever used, so the safe path existed and nothing took it.
 * A suspend function that touches the network should be safe to call from any dispatcher.
 */
class AuthApi(
    private val callFactory: Call.Factory = defaultClient(),
) {

    suspend fun connect(rawUrl: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(rawUrl) ?: run {
            // The most common first failure, and one the user cannot diagnose from the UI: a URL
            // that normalises to nothing never produces a request, so there is no network error.
            Diag.problem("auth.url.rejected", "input=\"${Diag.url(rawUrl)}\"")
            return@withContext AuthResult.Unreachable("无法解析服务器地址：$rawUrl")
        }
        Diag.event(
            "auth.start",
            "base" to Diag.url(base),
            "password" to Diag.secret(password),
            "thread" to Thread.currentThread().name,
        )

        val serverId = try {
            fetchServerId(base)
        } catch (e: Exception) {
            Diag.problem("auth.serverId.failed", "${e.javaClass.simpleName}: ${e.message}")
            return@withContext classifyNetworkError(e)
        } ?: run {
            Diag.problem("auth.serverId.unexpected", "GET $base${API_PREFIX}${IPC_PATH}/id did not start with $ID_PREFIX")
            return@withContext AuthResult.NotAnyListen("该地址不是 any-listen 服务端（/api/ipc/id 响应格式不符）")
        }
        Diag.d("auth.serverId", serverId)

        try {
            val salt = randomSalt()
            val key = sha256Hex(password + salt)
            val request = Request.Builder()
                .url("$base${API_PREFIX}${IPC_PATH}/ah")
                .header("m", key)
                .header("s", salt)
                .post(EMPTY_BODY)
                .build()

            callFactory.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Diag.event("auth.response", "code" to response.code, "token" to Diag.secret(response.header("token")))
                when {
                    response.code == 403 -> AuthResult.BlockedIp
                    response.code == 401 -> AuthResult.BadPassword
                    response.code != 200 -> AuthResult.Unexpected("HTTP ${response.code}: ${body.take(200)}")
                    else -> {
                        val token = response.header("token")
                        if (token.isNullOrBlank()) {
                            AuthResult.NotAnyListen("鉴权响应缺少 token 头")
                        } else if (!body.startsWith(HELLO_MSG)) {
                            AuthResult.NotAnyListen("鉴权响应正文格式不符：${body.take(120)}")
                        } else {
                            val serverName = body.removePrefix(HELLO_MSG)
                                .trim()
                                .let { if (it.isEmpty()) "" else runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                            Diag.event("auth.success", "serverName" to serverName)
                            AuthResult.Success(ServerSession(serverId, serverName, token))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Diag.problem("auth.request.failed", "${e.javaClass.simpleName}: ${e.message}")
            classifyNetworkError(e)
        }
    }

    private fun fetchServerId(base: String): String? {
        val request = Request.Builder().url("$base${API_PREFIX}${IPC_PATH}/id").get().build()
        callFactory.newCall(request).execute().use { response ->
            if (response.code != 200) return null
            val body = response.body?.string().orEmpty().trim()
            if (!body.startsWith(ID_PREFIX)) return null
            val id = body.removePrefix(ID_PREFIX)
            return id.ifEmpty { null }
        }
    }

    private fun classifyNetworkError(e: Exception): AuthResult = when (e) {
        is java.net.UnknownHostException -> AuthResult.Unreachable("无法解析主机名，请检查 URL")
        is java.net.ConnectException -> AuthResult.Unreachable("连接被拒绝，请确认服务端已启动且端口正确")
        is java.net.SocketTimeoutException -> AuthResult.Unreachable("连接超时，请确认手机与服务端在同一网络")
        is javax.net.ssl.SSLException -> AuthResult.Unreachable("TLS 握手失败：${e.message ?: ""}")
        else -> AuthResult.Unreachable(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        const val API_PREFIX = "/api"
        const val IPC_PATH = "/ipc"
        const val ID_PREFIX = "OjppZDo6-"
        const val HELLO_MSG = "Hello~::^-^::~v1~"

        private val EMPTY_BODY = ByteArray(0).toRequestBody("application/octet-stream".toMediaType())

        /**
         * The web client strips a single trailing slash and treats the rest as
         * `<scheme>//<hostPath>`; the same normalization keeps user-typed URLs working.
         */
        fun normalizeBaseUrl(raw: String): String? {
            var url = raw.trim()
            if (url.isEmpty()) return null
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            url = url.trimEnd('/')
            return runCatching { java.net.URI(url) }
                .map { uri -> if (uri.host.isNullOrBlank()) null else uri.scheme + "://" + url.removePrefix("${uri.scheme}://") }
                .getOrNull()
                ?.trimEnd('/')
        }

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte) }
        }

        /**
         * The web client uses `Math.random().toString().substring(2)` — digits only.
         * Any unpredictable salt is fine as long as it is echoed back verbatim.
         */
        fun randomSalt(length: Int = 16): String {
            val digits = "0123456789"
            val random = java.security.SecureRandom()
            return (1..length).map { digits[random.nextInt(digits.length)] }.joinToString("")
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
