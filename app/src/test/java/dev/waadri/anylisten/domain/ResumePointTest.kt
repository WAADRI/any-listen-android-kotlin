package dev.waadri.anylisten.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a saved resume point is applied to a list that may have changed since it was written.
 *
 * The trigger bug that made resume-on-launch silently useless is documented on [ResumePoint];
 * these cases cover the other half — what happens once the list actually loads.
 */
class ResumePointTest {

    private fun point(trackIndex: Int, positionMs: Long = 0L) = ResumePoint(
        listId = "list-1",
        trackIndex = trackIndex,
        positionMs = positionMs,
    )

    @Test
    fun `clamps a stale index into the current list`() {
        // Lists shrink between launches. An index past the end must land on the last track rather
        // than resuming nothing or crashing.
        assertEquals(4, point(trackIndex = 9).indexFor(trackCount = 5))
        assertEquals(0, point(trackIndex = -3).indexFor(trackCount = 5))
        assertEquals(2, point(trackIndex = 2).indexFor(trackCount = 5))
    }

    @Test
    fun `a single track list always resolves to that track`() {
        assertEquals(0, point(trackIndex = 7).indexFor(trackCount = 1))
    }

    @Test
    fun `reports no index for an empty or invalid list`() {
        assertNull(point(trackIndex = 0).indexFor(trackCount = 0))
        assertNull(point(trackIndex = 3).indexFor(trackCount = -1))
    }

    @Test
    fun `treats a negative position as the start of the track`() {
        // ExoPlayer reports -1 before its source is prepared; seeking to that is rejected.
        assertEquals(0L, point(trackIndex = 0, positionMs = -1L).positionFor())
        assertEquals(0L, point(trackIndex = 0, positionMs = 0L).positionFor())
        assertEquals(42_000L, point(trackIndex = 0, positionMs = 42_000L).positionFor())
    }
}
