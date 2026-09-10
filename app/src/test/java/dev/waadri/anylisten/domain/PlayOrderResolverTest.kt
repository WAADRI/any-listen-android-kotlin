package dev.waadri.anylisten.domain

import dev.waadri.anylisten.data.remote.PlayMethod
import dev.waadri.anylisten.data.remote.Wire
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Play-order rules transcribed from any-listen's `playerActions.ts`. These guard the behaviour
 * the phone browser could not deliver (auto-advance at end of track) without a device.
 */
class PlayOrderResolverTest {

    private fun item(id: String, playLater: Boolean = false, played: Boolean = false) = Wire.PlayMusicInfo(
        itemId = id,
        musicInfo = Wire.MusicInfo(id = id, name = "song-$id", singer = "singer"),
        listId = "list",
        source = "songlist",
        playLater = playLater,
        played = played,
    )

    private fun snapshot(
        list: List<Wire.PlayMusicInfo>,
        currentIndex: Int,
        method: PlayMethod,
        historyList: List<Wire.HistoryItem> = emptyList(),
        historyIndex: Int = 0,
        disliked: Set<String> = emptySet(),
    ) = PlayQueueSnapshot(
        list = list,
        currentIndex = currentIndex,
        method = method,
        historyList = historyList,
        historyIndex = historyIndex,
        dislikedIds = disliked,
    )

    private fun played(decision: PlayOrderDecision): Wire.PlayMusicInfo {
        assertTrue("expected a Play decision, got $decision", decision is PlayOrderDecision.Play)
        return (decision as PlayOrderDecision.Play).music
    }

    // ------------------------------------------------------------------ listLoop

    @Test
    fun `listLoop advances to the next track`() {
        val list = listOf(item("a"), item("b"), item("c"))

        assertEquals("b", played(PlayOrderResolver.next(snapshot(list, 0, PlayMethod.LIST_LOOP))).itemId)
    }

    @Test
    fun `listLoop wraps from the last track back to the first`() {
        val list = listOf(item("a"), item("b"), item("c"))

        assertEquals("a", played(PlayOrderResolver.next(snapshot(list, 2, PlayMethod.LIST_LOOP))).itemId)
    }

    // ------------------------------------------------------------------ list / none

    @Test
    fun `list stops at the end instead of wrapping`() {
        val list = listOf(item("a"), item("b"))

        assertEquals(PlayOrderDecision.Stop, PlayOrderResolver.next(snapshot(list, 1, PlayMethod.LIST)))
    }

    @Test
    fun `list still advances mid-list`() {
        val list = listOf(item("a"), item("b"))

        assertEquals("b", played(PlayOrderResolver.next(snapshot(list, 0, PlayMethod.LIST))).itemId)
    }

    @Test
    fun `none behaves like list and stops at the end`() {
        val list = listOf(item("a"), item("b"))

        assertEquals(PlayOrderDecision.Stop, PlayOrderResolver.next(snapshot(list, 1, PlayMethod.STOP_AT_END)))
        assertEquals("b", played(PlayOrderResolver.next(snapshot(list, 0, PlayMethod.STOP_AT_END))).itemId)
    }

    // ------------------------------------------------------------------ singleLoop

    @Test
    fun `singleLoop repeats the same track`() {
        val list = listOf(item("a"), item("b"))

        assertEquals("a", played(PlayOrderResolver.next(snapshot(list, 0, PlayMethod.SINGLE_LOOP))).itemId)
        assertEquals("b", played(PlayOrderResolver.next(snapshot(list, 1, PlayMethod.SINGLE_LOOP))).itemId)
    }

    // ------------------------------------------------------------------ random

    @Test
    fun `random prefers tracks not yet played in this pass`() {
        val list = listOf(item("a", played = true), item("b"), item("c"))
        val snapshot = snapshot(list, 0, PlayMethod.RANDOM)

        repeat(20) { seed ->
            val chosen = played(PlayOrderResolver.next(snapshot, Random(seed.toLong()))).itemId
            assertTrue("must not replay the current track, got $chosen", chosen != "a")
            assertTrue("must stay inside the unplayed pool, got $chosen", chosen == "b" || chosen == "c")
        }
    }

    @Test
    fun `random falls back to the whole list once everything is played`() {
        val list = listOf(item("a", played = true), item("b", played = true))
        val snapshot = snapshot(list, 0, PlayMethod.RANDOM)

        val chosen = played(PlayOrderResolver.next(snapshot, Random(7))).itemId
        assertTrue("must pick from the full list, got $chosen", chosen == "a" || chosen == "b")
    }

    @Test
    fun `random resumes the recorded history when one exists`() {
        val list = listOf(item("a"), item("b"), item("c"))
        val history = listOf(Wire.HistoryItem("a", 1), Wire.HistoryItem("c", 2), Wire.HistoryItem("b", 3))
        val snapshot = snapshot(list, 0, PlayMethod.RANDOM, historyList = history, historyIndex = 0)

        assertEquals("c", played(PlayOrderResolver.next(snapshot, Random(1))).itemId)
    }

    @Test
    fun `random skips history entries that are no longer in the list`() {
        val list = listOf(item("a"), item("b"))
        val history = listOf(Wire.HistoryItem("ghost", 1), Wire.HistoryItem("b", 2))
        val snapshot = snapshot(list, 0, PlayMethod.RANDOM, historyList = history, historyIndex = 0)

        assertEquals("b", played(PlayOrderResolver.next(snapshot, Random(1))).itemId)
    }

    @Test
    fun `random history is ignored when the index is negative`() {
        val list = listOf(item("a"), item("b"))
        val history = listOf(Wire.HistoryItem("b", 1))
        val snapshot = snapshot(list, 0, PlayMethod.RANDOM, historyList = history, historyIndex = -1)

        // Falls through to the random pool rather than being pinned to the history entry.
        val chosen = played(PlayOrderResolver.next(snapshot, Random(3))).itemId
        assertEquals("b", chosen)
    }

    // ------------------------------------------------------------------ play later

    @Test
    fun `play later entries jump the queue`() {
        val list = listOf(item("a"), item("b"), item("later", playLater = true))
        val snapshot = snapshot(list, 0, PlayMethod.LIST_LOOP)

        assertEquals("later", played(PlayOrderResolver.next(snapshot)).itemId)
    }

    @Test
    fun `play later queue loops within itself`() {
        val list = listOf(item("a"), item("l1", playLater = true), item("l2", playLater = true))
        val snapshot = snapshot(list, 1, PlayMethod.LIST_LOOP)

        assertEquals("l2", played(PlayOrderResolver.next(snapshot)).itemId)
    }

    @Test
    fun `a single play later entry repeats itself`() {
        val list = listOf(item("a"), item("l1", playLater = true))
        val snapshot = snapshot(list, 1, PlayMethod.LIST_LOOP)

        // Upstream: with exactly one play-later entry the queue is re-entered, so it loops.
        assertEquals("l1", played(PlayOrderResolver.next(snapshot)).itemId)
    }

    // ------------------------------------------------------------------ dislike

    @Test
    fun `disliked tracks are skipped`() {
        val list = listOf(item("a"), item("b"), item("c"))
        val snapshot = snapshot(list, 0, PlayMethod.LIST_LOOP, disliked = setOf("b"))

        assertEquals("c", played(PlayOrderResolver.next(snapshot)).itemId)
    }

    @Test
    fun `the currently playing track stays visible even when disliked`() {
        val list = listOf(item("a"), item("b"))
        val snapshot = snapshot(list, 0, PlayMethod.LIST_LOOP, disliked = setOf("a"))

        assertEquals("b", played(PlayOrderResolver.next(snapshot)).itemId)
    }

    @Test
    fun `a fully disliked list stops playback`() {
        val list = listOf(item("a"))
        val snapshot = snapshot(list, 0, PlayMethod.LIST_LOOP, disliked = setOf("a"))

        assertEquals(PlayOrderDecision.Stop, PlayOrderResolver.next(snapshot))
    }

    // ------------------------------------------------------------------ degenerate input

    @Test
    fun `an empty list stops playback`() {
        assertEquals(PlayOrderDecision.Stop, PlayOrderResolver.next(snapshot(emptyList(), 0, PlayMethod.LIST_LOOP)))
    }

    @Test
    fun `a stale current index resumes from the top of the list`() {
        val list = listOf(item("a"), item("b"))
        val snapshot = snapshot(list, 9, PlayMethod.LIST_LOOP)

        assertEquals("a", played(PlayOrderResolver.next(snapshot)).itemId)
    }

    // ------------------------------------------------------------------ prev

    @Test
    fun `prev walks backwards and wraps`() {
        val list = listOf(item("a"), item("b"), item("c"))

        assertEquals("a", played(PlayOrderResolver.prev(snapshot(list, 1, PlayMethod.LIST_LOOP))).itemId)
        assertEquals("c", played(PlayOrderResolver.prev(snapshot(list, 0, PlayMethod.LIST_LOOP))).itemId)
    }

    @Test
    fun `prev follows the recorded history in random mode`() {
        val list = listOf(item("a"), item("b"), item("c"))
        val history = listOf(Wire.HistoryItem("a", 1), Wire.HistoryItem("c", 2), Wire.HistoryItem("b", 3))
        val snapshot = snapshot(list, 2, PlayMethod.RANDOM, historyList = history, historyIndex = 2)

        assertEquals("c", played(PlayOrderResolver.prev(snapshot)).itemId)
    }

    @Test
    fun `prev falls back to ordered rules in random mode without usable history`() {
        val list = listOf(item("a"), item("b"), item("c"))
        val snapshot = snapshot(list, 1, PlayMethod.RANDOM, historyList = emptyList(), historyIndex = 0)

        assertEquals("a", played(PlayOrderResolver.prev(snapshot)).itemId)
    }

    // ------------------------------------------------------------------ normalList

    @Test
    fun `normal list excludes play later and disliked entries`() {
        val list = listOf(item("a"), item("l", playLater = true), item("d"))
        val snapshot = snapshot(list, 0, PlayMethod.LIST_LOOP, disliked = setOf("d"))

        assertEquals(listOf("a"), PlayOrderResolver.normalList(snapshot).map { it.itemId })
    }
}
