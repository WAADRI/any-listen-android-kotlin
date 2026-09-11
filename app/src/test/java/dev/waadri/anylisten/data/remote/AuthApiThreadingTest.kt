package dev.waadri.anylisten.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Where the blocking HTTP work actually runs.
 *
 * This exists because a shipped build failed every connect with `NetworkOnMainThreadException`.
 * `viewModelScope` launches on `Dispatchers.Main`, `AuthApi.connect` used OkHttp's blocking
 * `execute()`, and nothing moved the work off the main thread. Every unit test passed anyway: a JVM
 * test has no main-thread check, so the only signal was a phone that could not connect to anything.
 *
 * The test therefore does two things a normal unit test does not:
 *
 *  1. Installs a main dispatcher via [Dispatchers.setMain], so `Dispatchers.Main` resolves in a JVM
 *     test at all, and calls [AuthApi.connect] from it — the same dispatcher `viewModelScope` uses.
 *  2. Records the thread name from inside the OkHttp call factory, which runs synchronously on
 *     whatever thread invoked `execute()`. That is the thread the real network I/O would block.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthApiThreadingTest {

    /** Records the thread that performs the blocking call, and answers both handshake requests. */
    private class RecordingCallFactory : Call.Factory {
        var observedThreadName: String? = null

        override fun newCall(request: Request): Call {
            observedThreadName = Thread.currentThread().name
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/id") -> "${AuthApi.ID_PREFIX}test-server-id"
                else -> "${AuthApi.HELLO_MSG}\nAnyListenTest"
            }
            return FakeCall(
                request = request,
                response = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .apply { if (!path.endsWith("/id")) header("token", "test.jwt.token") }
                    .body(body.toResponseBody("text/plain".toMediaType()))
                    .build(),
            )
        }
    }

    private class FakeCall(private val request: Request, private val response: Response) : Call {
        override fun request(): Request = request
        override fun execute(): Response = response
        override fun enqueue(responseCallback: okhttp3.Callback) =
            throw UnsupportedOperationException("AuthApi uses execute()")

        override fun isExecuted(): Boolean = false
        override fun cancel() = Unit
        override fun isCanceled(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall(request, response)
    }

    private lateinit var factory: RecordingCallFactory
    private lateinit var api: AuthApi

    @Before
    fun setUp() {
        // Without this, Dispatchers.Main throws "Module with the Main dispatcher had failed to
        // initialize" in a plain JVM test, and the ViewModel path could not be reproduced at all.
        Dispatchers.setMain(Dispatchers.Unconfined)
        factory = RecordingCallFactory()
        api = AuthApi(
            OkHttpClient.Builder()
                .callFactory(factory)
                .connectTimeout(1, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `connect does its blocking work off the main dispatcher`() {
        val callerThread = Thread.currentThread().name

        val result = runBlocking {
            // Dispatchers.Main here is what viewModelScope would use.
            withContextMain { api.connect("http://server.local:9500", "pw") }
        }

        assertTrue("handshake should succeed, got $result", result is AuthResult.Success)
        assertNotEquals(
            "the blocking OkHttp call ran on the caller's main thread — this is exactly the " +
                "NetworkOnMainThreadException that shipped",
            callerThread,
            factory.observedThreadName,
        )
    }

    @Test
    fun `connect succeeds from the main dispatcher at all`() {
        // The narrower guarantee: the call is usable from the dispatcher a ViewModel gives you.
        // Before the fix this threw NetworkOnMainThreadException on device.
        val result = runBlocking {
            withContextMain { api.connect("http://server.local:9500", "pw") }
        }

        val success = result as? AuthResult.Success
        assertEquals("test-server-id", success?.session?.serverId)
        assertEquals("test.jwt.token", success?.session?.token)
    }

    private suspend fun <T> withContextMain(block: suspend () -> T): T =
        withContext(Dispatchers.Main) { block() }
}
