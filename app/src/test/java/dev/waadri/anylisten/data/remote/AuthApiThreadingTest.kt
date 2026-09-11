package dev.waadri.anylisten.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Which thread the blocking HTTP work runs on.
 *
 * This exists because a shipped build failed every connect with `NetworkOnMainThreadException`.
 * `viewModelScope` launches on `Dispatchers.Main`, `AuthApi.connect` used OkHttp's blocking
 * `execute()`, and nothing moved the work off the main thread. Every unit test passed anyway: a JVM
 * test has no main-thread check, so the only signal was a phone that could not connect to any
 * server while the same URL opened fine in a browser.
 *
 * So the test does two things an ordinary unit test does not:
 *
 *  1. Installs a main dispatcher with [Dispatchers.setMain], so `Dispatchers.Main` resolves at all
 *     in a JVM test, and calls [AuthApi.connect] from it — the same dispatcher `viewModelScope`
 *     uses.
 *  2. Records the thread name from an [Interceptor]. Interceptors run synchronously on the thread
 *     that invoked the call, so this is the thread the real network I/O would block.
 *
 * A real [MockWebServer] is used rather than a hand-written fake: OkHttp's `Builder.callFactory`
 * is internal and `okhttp3.Timeout` is not on this module's test classpath, so implementing `Call`
 * by hand does not compile from here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthApiThreadingTest {

    private lateinit var server: MockWebServer
    private var observedThreadName: String? = null
    private lateinit var api: AuthApi

    @Before
    fun setUp() {
        // Without this, Dispatchers.Main throws "Module with the Main dispatcher had failed to
        // initialize" in a plain JVM test, and the ViewModel path could not be reproduced at all.
        Dispatchers.setMain(Dispatchers.Unconfined)

        server = MockWebServer()
        server.enqueue(MockResponse().setBody("${AuthApi.ID_PREFIX}test-server-id"))
        server.enqueue(
            MockResponse()
                .addHeader("token", "test.jwt.token")
                .setBody("${AuthApi.HELLO_MSG}\nAnyListenTest"),
        )
        server.start()

        val recording = Interceptor { chain ->
            observedThreadName = Thread.currentThread().name
            chain.proceed(chain.request())
        }
        api = AuthApi(
            OkHttpClient.Builder()
                .addInterceptor(recording)
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    @Test
    fun `connect does its blocking work off the main dispatcher`() {
        val callerThread = Thread.currentThread().name

        val result = runBlocking {
            // Dispatchers.Main is what viewModelScope would use.
            withContext(Dispatchers.Main) { api.connect(baseUrl(), "pw") }
        }

        assertTrue("handshake should succeed, got $result", result is AuthResult.Success)
        assertNotNull("the interceptor should have run", observedThreadName)
        assertNotEquals(
            "the blocking OkHttp call ran on the caller's main thread — this is exactly the " +
                "NetworkOnMainThreadException that shipped",
            callerThread,
            observedThreadName,
        )
    }

    @Test
    fun `connect succeeds from the main dispatcher at all`() {
        // The narrower guarantee: usable from the dispatcher a ViewModel is given.
        val result = runBlocking {
            withContext(Dispatchers.Main) { api.connect(baseUrl(), "pw") }
        }

        val success = result as? AuthResult.Success
        assertEquals("test-server-id", success?.session?.serverId)
        assertEquals("test.jwt.token", success?.session?.token)
    }

    @Test
    fun `the handshake sends the salted password digest, not the password`() {
        // Guards the one credential rule of this protocol: `m` is sha256(password + salt), and the
        // password itself must never appear on the wire.
        runBlocking { withContext(Dispatchers.Main) { api.connect(baseUrl(), "super-secret") } }

        val idRequest = server.takeRequest()
        assertTrue(
            "expected ${AuthApi.API_PREFIX}${AuthApi.IPC_PATH}/id, got ${idRequest.path}",
            idRequest.path?.endsWith("${AuthApi.API_PREFIX}${AuthApi.IPC_PATH}/id") == true,
        )
        val authRequest = server.takeRequest()
        assertTrue(
            "expected ${AuthApi.API_PREFIX}${AuthApi.IPC_PATH}/ah, got ${authRequest.path}",
            authRequest.path?.endsWith("${AuthApi.API_PREFIX}${AuthApi.IPC_PATH}/ah") == true,
        )

        val salt = authRequest.getHeader("s")
        assertNotNull("the salt header is required for the server to recompute the digest", salt)
        assertEquals(AuthApi.sha256Hex("super-secret$salt"), authRequest.getHeader("m"))
        assertNotEquals("super-secret", authRequest.getHeader("m"))

        // Belt and braces: the password must not leak through any other header or the URL either.
        authRequest.headers.names().forEach { name ->
            assertNotEquals(
                "password leaked in header $name",
                "super-secret",
                authRequest.getHeader(name),
            )
        }
        assertTrue("password leaked in the URL", authRequest.path?.contains("super-secret") != true)
    }
}
