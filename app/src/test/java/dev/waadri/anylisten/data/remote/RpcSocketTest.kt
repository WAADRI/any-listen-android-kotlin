package dev.waadri.anylisten.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The socket URL and backoff curve must match what the any-listen web server expects and
 * what the web client does; both are easy to get subtly wrong, so they are pinned here.
 */
class RpcSocketTest {

    @Test
    fun `socket url uses the ipc path and required window type`() {
        val url = RpcSocket.buildSocketUrl("http://192.168.1.5:9500", "abc.def.ghi")

        assertEquals("ws://192.168.1.5:9500/api/ipc/socket?m=abc.def.ghi&t=main", url)
    }

    @Test
    fun `socket url upgrades to wss for https servers`() {
        val url = RpcSocket.buildSocketUrl("https://music.example.com", "tok")

        assertTrue(url.startsWith("wss://music.example.com/api/ipc/socket?"))
        assertTrue(url.endsWith("&t=main"))
    }

    @Test
    fun `socket url keeps the sub path of the server`() {
        val url = RpcSocket.buildSocketUrl("http://music.lan:9500/anylisten", "tok")

        assertEquals("ws://music.lan:9500/anylisten/api/ipc/socket?m=tok&t=main", url)
    }

    @Test
    fun `socket url url-encodes the jwt`() {
        // JWTs are base64url so they are path-safe, but the web client still encodes the
        // value; matching that keeps any future token format working.
        val url = RpcSocket.buildSocketUrl("http://host", "a+b/c=d")

        assertTrue("expected encoded token in $url", url.contains("m=a%2Bb%2Fc%3Dd"))
    }

    @Test
    fun `close codes match the server constants`() {
        assertEquals(1000, RpcSocket.CLOSE_NORMAL)
        assertEquals(4001, RpcSocket.CLOSE_LOGOUT)
        assertEquals(4100, RpcSocket.CLOSE_FAILED)
        assertEquals(1006, RpcSocket.CLOSE_ABNORMAL)
    }

    @Test
    fun `incoming method names carry no group prefix`() {
        // The server builds outbound proxies with createRemoteGroup('player', ...), but the
        // group name is local-only; the wire path is the bare method name. Registering
        // "player.playerEvent" instead of "playerEvent" silently receives nothing.
        assertEquals("playerEvent", RpcSocket.INCOMING_PLAYER_EVENT)
        assertEquals("playerAction", RpcSocket.INCOMING_PLAYER_ACTION)
        assertEquals("playListAction", RpcSocket.INCOMING_PLAY_LIST_ACTION)
        assertEquals("playHistoryListAction", RpcSocket.INCOMING_PLAY_HISTORY_LIST_ACTION)
    }

    @Test
    fun `outgoing method paths carry the group prefix`() {
        // Outgoing calls mirror the server's exposeObj nesting.
        assertEquals("player.getPlayInfo", RpcSocket.OUT_PLAYER_GET_PLAY_INFO)
        assertEquals("player.playerAction", RpcSocket.OUT_PLAYER_ACTION)
        assertEquals("music.getMusicUrl", RpcSocket.OUT_MUSIC_GET_URL)
        assertEquals("app.inited", RpcSocket.OUT_APP_INITED)
    }

    @Test
    fun `incoming and outgoing player action names differ as the server expects`() {
        // Guards the single most damaging protocol mistake: these two are NOT symmetric.
        assertEquals("playerAction", RpcSocket.INCOMING_PLAYER_ACTION)
        assertEquals("player.playerAction", RpcSocket.OUT_PLAYER_ACTION)
    }

    @Test
    fun `backoff starts at two seconds and grows every two attempts`() {
        assertEquals(2_000L, RpcSocket.backoffMsFor(1))
        assertEquals(2_000L, RpcSocket.backoffMsFor(2))
        assertEquals(5_000L, RpcSocket.backoffMsFor(3))
        assertEquals(5_000L, RpcSocket.backoffMsFor(4))
        assertEquals(8_000L, RpcSocket.backoffMsFor(5))
    }

    @Test
    fun `backoff is capped at one minute`() {
        assertEquals(60_000L, RpcSocket.backoffMsFor(50))
        assertEquals(60_000L, RpcSocket.backoffMsFor(1000))
        assertTrue(RpcSocket.backoffMsFor(39) <= 60_000L)
    }

    @Test
    fun `backoff tolerates nonsensical attempt numbers`() {
        assertEquals(2_000L, RpcSocket.backoffMsFor(0))
        assertEquals(2_000L, RpcSocket.backoffMsFor(-5))
    }
}
