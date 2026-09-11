package dev.waadri.anylisten.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Both forms here came off a real device against a real server and each one cost a 404, so they are
 * pinned exactly rather than approximately.
 */
class ServerUrlTest {

    private val origin = "https://music.waadri.top"

    @Test
    fun `the virtual protocol is replaced by the origin, not appended to it`() {
        // Observed on device: the player asked for
        // https://music.waadri.top/al-ps-host:/public/medias/x.mp3 and got a 404, because the
        // marker was treated as a path segment.
        assertEquals(
            "https://music.waadri.top/public/medias/x.mp3",
            ServerUrl.resolve("al-ps-host:/public/medias/x.mp3", origin),
        )
    }

    @Test
    fun `the virtual protocol works without a leading slash`() {
        assertEquals(
            "https://music.waadri.top/public/medias/x.mp3",
            ServerUrl.resolve("al-ps-host:public/medias/x.mp3", origin),
        )
    }

    @Test
    fun `a relative proxy path is resolved against the origin`() {
        // The server runs its proxy with base '.' and path '/api/p_static', so covers arrive as a
        // document-relative path.
        assertEquals(
            "https://music.waadri.top/api/p_static/abc.jpeg",
            ServerUrl.resolve("./api/p_static/abc.jpeg", origin),
        )
    }

    @Test
    fun `an absolute url passes through untouched`() {
        // A track served from a CDN or an extension's own host must not be rewritten.
        assertEquals("https://cdn.example.com/a.mp3", ServerUrl.resolve("https://cdn.example.com/a.mp3", origin))
        assertEquals("http://192.168.1.5:9500/a.mp3", ServerUrl.resolve("http://192.168.1.5:9500/a.mp3", origin))
    }

    @Test
    fun `the origin may carry a trailing slash`() {
        assertEquals(
            "https://music.waadri.top/public/medias/x.mp3",
            ServerUrl.resolve("al-ps-host:/public/medias/x.mp3", "https://music.waadri.top/"),
        )
    }

    @Test
    fun `blank input yields null rather than an empty url`() {
        assertNull(ServerUrl.resolve(null, origin))
        assertNull(ServerUrl.resolve("", origin))
        assertNull(ServerUrl.resolve("   ", origin))
    }

    @Test
    fun `a path with no configured origin is left as-is rather than mangled`() {
        assertEquals("/public/medias/x.mp3", ServerUrl.resolve("al-ps-host:/public/medias/x.mp3", ""))
        assertEquals("./api/p_static/abc.jpeg", ServerUrl.resolve("./api/p_static/abc.jpeg", ""))
    }
}
