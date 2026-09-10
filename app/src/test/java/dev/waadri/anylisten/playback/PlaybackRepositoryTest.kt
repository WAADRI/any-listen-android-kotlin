package dev.waadri.anylisten.playback

import dev.waadri.anylisten.data.remote.M2cCodec
import dev.waadri.anylisten.data.remote.Wire
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the payload this client sends upward. The server stores these progress fields and
 * re-broadcasts them, so a renamed key breaks cross-device progress silently.
 */
class PlaybackRepositoryTest {

    @Test
    fun `progress event keys match the server Progress type`() {
        val event = PlaybackRepository.progressEvent(positionSeconds = 42.5, durationSeconds = 200.0)

        assertEquals("progress", event["action"]?.jsonPrimitive?.content)

        val data = event["data"]!!.jsonObject
        assertEquals(
            setOf("nowPlayTime", "maxPlayTime", "progress", "nowPlayTimeStr", "maxPlayTimeStr"),
            data.keys,
        )
        assertEquals(42.5, data["nowPlayTime"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(200.0, data["maxPlayTime"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(0.2125, data["progress"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals("00:42", data["nowPlayTimeStr"]!!.jsonPrimitive.content)
        assertEquals("03:20", data["maxPlayTimeStr"]!!.jsonPrimitive.content)
    }

    @Test
    fun `progress event decodes back into the server's own model`() {
        // Round trip through Wire.Progress: if the keys ever drift, this fails.
        val event = PlaybackRepository.progressEvent(90.0, 180.0)

        val decoded = M2cCodec.json.decodeFromJsonElement(
            Wire.Progress.serializer(),
            event["data"]!!,
        )

        assertEquals(90.0, decoded.nowPlayTime, 0.001)
        assertEquals(180.0, decoded.maxPlayTime, 0.001)
        assertEquals(0.5, decoded.progress, 0.001)
        assertEquals("01:30", decoded.nowPlayTimeStr)
        assertEquals("03:00", decoded.maxPlayTimeStr)
    }

    @Test
    fun `progress is zero for unknown duration instead of dividing by zero`() {
        val event = PlaybackRepository.progressEvent(positionSeconds = 10.0, durationSeconds = 0.0)

        val data = event["data"]!!.jsonObject
        assertEquals(0.0, data["progress"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    @Test
    fun `negative positions are clamped`() {
        val event = PlaybackRepository.progressEvent(positionSeconds = -5.0, durationSeconds = -1.0)

        val data = event["data"]!!.jsonObject
        assertEquals(0.0, data["nowPlayTime"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(0.0, data["maxPlayTime"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals("00:00", data["nowPlayTimeStr"]!!.jsonPrimitive.content)
    }

    @Test
    fun `time formatting matches the server's mm ss format`() {
        assertEquals("00:00", PlaybackRepository.formatTime(0.0))
        assertEquals("00:05", PlaybackRepository.formatTime(5.0))
        assertEquals("00:59", PlaybackRepository.formatTime(59.9))
        assertEquals("01:00", PlaybackRepository.formatTime(60.0))
        assertEquals("59:59", PlaybackRepository.formatTime(3599.0))
        // Hours are expressed as minutes, matching upstream's formatPlayTime2.
        assertEquals("60:00", PlaybackRepository.formatTime(3600.0))
    }
}
