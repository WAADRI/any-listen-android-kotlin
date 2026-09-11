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
    fun `outgoing method paths are flat too`() {
        // This used to assert dotted strings like "app.inited", on the theory that outgoing paths
        // mirror exposeObj nesting. They do not: the server spreads flat factories into one
        // object, so there is no exposeObj.app or exposeObj.list to descend into, and a
        // two-segment path made the server answer every call with "app is not defined".
        assertEquals(listOf("getPlayInfo"), RpcSocket.OUT_PLAYER_GET_PLAY_INFO)
        assertEquals(listOf("playerAction"), RpcSocket.OUT_PLAYER_ACTION)
        assertEquals(listOf("getMusicUrl"), RpcSocket.OUT_MUSIC_GET_URL)
        assertEquals(listOf("getAllUserLists"), RpcSocket.OUT_LIST_GET_ALL_USER_LISTS)
        assertEquals(listOf("inited"), RpcSocket.OUT_APP_INITED)
    }

    @Test
    fun `every outgoing path is a single element`() {
        // The server's dispatch object is flat, so more than one segment can only ever be a bug.
        val paths = mapOf(
            "OUT_PLAYER_GET_PLAY_INFO" to RpcSocket.OUT_PLAYER_GET_PLAY_INFO,
            "OUT_PLAYER_ACTION" to RpcSocket.OUT_PLAYER_ACTION,
            "OUT_PLAYER_PLAY_LIST_ACTION" to RpcSocket.OUT_PLAYER_PLAY_LIST_ACTION,
            "OUT_LIST_GET_ALL_USER_LISTS" to RpcSocket.OUT_LIST_GET_ALL_USER_LISTS,
            "OUT_LIST_GET_MUSICS" to RpcSocket.OUT_LIST_GET_MUSICS,
            "OUT_MUSIC_GET_URL" to RpcSocket.OUT_MUSIC_GET_URL,
            "OUT_MUSIC_GET_PIC" to RpcSocket.OUT_MUSIC_GET_PIC,
            "OUT_MUSIC_GET_LYRIC" to RpcSocket.OUT_MUSIC_GET_LYRIC,
            "OUT_APP_INITED" to RpcSocket.OUT_APP_INITED,
            "OUT_APP_SET_SETTING" to RpcSocket.OUT_APP_SET_SETTING,
        )
        for ((name, path) in paths) {
            assertEquals("$name must have exactly one segment, got $path", 1, path.size)
            assertTrue("$name must not be blank, got $path", path.first().isNotBlank())
        }
    }

    @Test
    fun `the encoded request carries the flat path on the wire`() {
        // Pins the bytes rather than the constants: the frame is built by M2cCodec and this is
        // what the server actually parses.
        val frame = M2cCodec.encodeRequest("1", RpcSocket.OUT_LIST_GET_ALL_USER_LISTS, emptyList())

        assertEquals("""[0,"1",["getAllUserLists"],[],[]]""", frame)
    }

    @Test
    fun `incoming and outgoing player action names are both bare`() {
        // Guards the single most damaging protocol mistake: assuming a group prefix where the
        // server has none. Both directions send the bare method name.
        assertEquals("playerAction", RpcSocket.INCOMING_PLAYER_ACTION)
        assertEquals(listOf("playerAction"), RpcSocket.OUT_PLAYER_ACTION)
    }

    @Test
    fun `backoff matches the web client curve`() {
        // web client: waitTime = min(2000 + floor(failedNum / 2) * 3000, 60000)
        assertEquals(2_000L, RpcSocket.backoffMsFor(1))
        assertEquals(5_000L, RpcSocket.backoffMsFor(2))
        assertEquals(5_000L, RpcSocket.backoffMsFor(3))
        assertEquals(8_000L, RpcSocket.backoffMsFor(4))
        assertEquals(8_000L, RpcSocket.backoffMsFor(5))
        assertEquals(11_000L, RpcSocket.backoffMsFor(6))
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
