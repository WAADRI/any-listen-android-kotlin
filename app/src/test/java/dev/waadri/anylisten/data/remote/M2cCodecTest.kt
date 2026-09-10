package dev.waadri.anylisten.data.remote

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-frame tests for the message2call wire format. These pin the exact bytes the
 * any-listen web server expects, so a refactor cannot silently break the protocol.
 */
class M2cCodecTest {

    @Test
    fun `decodes a request frame`() {
        val raw = """[0,"c1",["player","playerAction"],[{"action":"next"}],[]]"""

        val incoming = M2cCodec.decode(raw)

        assertTrue("expected a Request, got $incoming", incoming is M2cCodec.Incoming.Request)
        val request = (incoming as M2cCodec.Incoming.Request).request
        assertEquals("c1", request.callId)
        assertEquals(listOf("player", "playerAction"), request.path)
        assertEquals("player.playerAction", request.methodName)
        assertEquals(1, request.args.size)
        assertTrue(request.callbackIndexes.isEmpty())
    }

    @Test
    fun `decodes a successful response frame`() {
        val raw = """[1,"c1",null,{"info":{"index":3}}]"""

        val incoming = M2cCodec.decode(raw)

        assertTrue(incoming is M2cCodec.Incoming.Response)
        val response = incoming as M2cCodec.Incoming.Response
        assertEquals("c1", response.callId)
        assertNull(response.errorMessage)
        assertTrue(response.result!!.value.toString().contains("\"index\":3"))
    }

    @Test
    fun `decodes an error response frame`() {
        val raw = """[1,"c1",{"message":"boom","stack":"at foo"}]"""

        val response = M2cCodec.decode(raw) as M2cCodec.Incoming.Response

        assertEquals("c1", response.callId)
        assertEquals("boom", response.errorMessage)
        assertNull(response.result)
    }

    @Test
    fun `decodes a callback request from the server`() {
        val raw = """[2,"player.playerEvent_CALLBACK",[{"action":"progress","data":{"progress":0.5}}]]"""

        val incoming = M2cCodec.decode(raw)

        assertTrue(incoming is M2cCodec.Incoming.Callback)
        val callback = (incoming as M2cCodec.Incoming.Callback).callback
        assertEquals("player.playerEvent_CALLBACK", callback.callbackName)
        assertEquals(1, callback.args.size)
    }

    @Test
    fun `decodes a callback response`() {
        val raw = """[3,"cb1",null,42]"""

        val incoming = M2cCodec.decode(raw)

        assertTrue(incoming is M2cCodec.Incoming.CallbackResponse)
        val response = incoming as M2cCodec.Incoming.CallbackResponse
        assertEquals("cb1", response.callbackName)
        assertEquals("42", response.result!!.value.toString())
    }

    @Test
    fun `malformed frames never throw`() {
        val cases = listOf(
            "not json at all",
            "{}",
            """[99,"c1"]""",
            """[1]""",
            """[0,"c1"]""",
        )

        for (raw in cases) {
            val incoming = M2cCodec.decode(raw)
            assertTrue("$raw should be Malformed, got $incoming", incoming is M2cCodec.Incoming.Malformed)
        }
    }

    @Test
    fun `encodes a request in the exact shape the server parses`() {
        val encoded = M2cCodec.encodeRequest(
            callId = "c1",
            path = listOf("player", "getPlayInfo"),
            args = emptyList(),
        )

        assertEquals("""[0,"c1",["player","getPlayInfo"],[],[]]""", encoded)
    }

    @Test
    fun `encodes request args as raw JSON`() {
        val encoded = M2cCodec.encodeRequest(
            callId = "c2",
            path = listOf("player", "playerAction"),
            args = listOf(M2cCodec.json.parseToJsonElement("""{"action":"seek","data":12.5}""")),
        )

        assertEquals("""[0,"c2",["player","playerAction"],[{"action":"seek","data":12.5}],[]]""", encoded)
    }

    @Test
    fun `encodes success and error responses`() {
        assertEquals(
            """[1,"c1",null,null]""",
            M2cCodec.encodeResponse("c1", null, null),
        )
        assertEquals(
            """[1,"c1","boom",null]""",
            M2cCodec.encodeResponse("c1", "boom", null),
        )
        assertEquals(
            """[1,"c1",null,{"status":"playing"}]""",
            M2cCodec.encodeResponse("c1", null, M2cCodec.json.parseToJsonElement("""{"status":"playing"}""")),
        )
    }

    @Test
    fun `encode then decode round-trips`() {
        val encoded = M2cCodec.encodeRequest(
            callId = "round",
            path = listOf("music", "getMusicUrl"),
            args = listOf(M2cCodec.json.parseToJsonElement("""{"quality":"320k"}""")),
        )

        val request = (M2cCodec.decode(encoded) as M2cCodec.Incoming.Request).request
        assertEquals("round", request.callId)
        assertEquals(listOf("music", "getMusicUrl"), request.path)
        assertEquals("""{"quality":"320k"}""", request.args[0].value.toString())
    }

    @Test
    fun `action payload omits data when absent to match the web client`() {
        assertEquals("""{"action":"next"}""", M2cCodec.actionPayload("next").toString())
        assertEquals(
            """{"action":"seek","data":30}""",
            M2cCodec.actionPayload("seek", JsonPrimitive(30)).toString(),
        )
    }
}
