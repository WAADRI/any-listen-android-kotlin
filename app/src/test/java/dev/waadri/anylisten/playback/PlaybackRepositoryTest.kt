package dev.waadri.anylisten.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `formatTime` is the only piece of the player that is pure enough to pin here.
 *
 * Everything else in [PlaybackRepository] touches `android.content.Context`, `DataStore` or
 * ExoPlayer, so it is covered by the resolver tests in `domain/` plus manual device checks. The
 * upside of the local-playback design is that far less of the player needs a fake server now.
 */
class PlaybackRepositoryTest {

    @Test
    fun `formats minutes and seconds with a leading zero`() {
        assertEquals("00:00", PlaybackRepository.formatTime(0.0))
        assertEquals("00:07", PlaybackRepository.formatTime(7.4))
        assertEquals("00:42", PlaybackRepository.formatTime(42.5))
        assertEquals("03:05", PlaybackRepository.formatTime(185.0))
    }

    @Test
    fun `formats hours as minutes rather than rolling over`() {
        // A 90-minute track reads "90:00", not "1:30:00": the layout has room for mm:ss only.
        assertEquals("90:00", PlaybackRepository.formatTime(5400.0))
    }

    @Test
    fun `clamps negative positions to zero`() {
        // ExoPlayer reports -1 while a source is being prepared, which must not render as "-1:-1".
        assertEquals("00:00", PlaybackRepository.formatTime(-1.0))
    }

    @Test
    fun `rounds down part-seconds`() {
        assertEquals("00:59", PlaybackRepository.formatTime(59.99))
    }
}
