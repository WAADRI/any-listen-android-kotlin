package dev.waadri.anylisten.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthApiTest {

    @Test
    fun `sha256 matches the digest the server compares against`() {
        // Pinned against the server's `toSha256` (node crypto sha256 hex).
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AuthApi.sha256Hex(""),
        )
        assertEquals(
            "2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b",
            AuthApi.sha256Hex("secret"),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AuthApi.sha256Hex("abc"),
        )
    }

    @Test
    fun `password is combined with the salt before hashing`() {
        // The server recomputes sha256(storedPassword + salt) and compares to header `m`.
        val salt = "123456"
        assertEquals(
            AuthApi.sha256Hex("mypassword" + salt),
            AuthApi.sha256Hex("mypassword$salt"),
        )
        assertEquals(64, AuthApi.sha256Hex("anything").length)
    }

    @Test
    fun `random salt is digits only and has the requested length`() {
        repeat(20) {
            val salt = AuthApi.randomSalt()
            assertEquals(16, salt.length)
            assertEquals(true, salt.all { it.isDigit() })
        }
        assertEquals(24, AuthApi.randomSalt(24).length)
    }

    @Test
    fun `base url normalization adds a scheme and strips trailing slashes`() {
        assertEquals("http://192.168.1.5:9500", AuthApi.normalizeBaseUrl("192.168.1.5:9500"))
        assertEquals("http://music.lan", AuthApi.normalizeBaseUrl("http://music.lan/"))
        assertEquals("https://music.example.com:8443/sub", AuthApi.normalizeBaseUrl("https://music.example.com:8443/sub/"))
        assertEquals("http://192.168.1.5:9500", AuthApi.normalizeBaseUrl("  192.168.1.5:9500  "))
    }

    @Test
    fun `base url normalization rejects unusable input`() {
        assertNull(AuthApi.normalizeBaseUrl(""))
        assertNull(AuthApi.normalizeBaseUrl("   "))
        assertNull(AuthApi.normalizeBaseUrl("http:///nohost"))
    }

    @Test
    fun `api paths match the server router`() {
        // packages/web-server/src/modules/ipc/index.ts registers exactly these.
        assertEquals("/api", AuthApi.API_PREFIX)
        assertEquals("/ipc", AuthApi.IPC_PATH)
        assertEquals("OjppZDo6-", AuthApi.ID_PREFIX)
        assertEquals("Hello~::^-^::~v1~", AuthApi.HELLO_MSG)
    }
}
