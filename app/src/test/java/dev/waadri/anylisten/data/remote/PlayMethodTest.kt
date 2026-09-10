package dev.waadri.anylisten.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Play order is now decided entirely on the device, so these values are the only thing tying this
 * client's behaviour to any-listen's `player.togglePlayMethod` vocabulary. If the wire strings
 * drift, nothing breaks loudly — the mode just silently reads back as the default.
 */
class PlayMethodTest {

    @Test
    fun `wire values match the server vocabulary`() {
        assertEquals("listLoop", PlayMethod.LIST_LOOP.wire)
        assertEquals("random", PlayMethod.RANDOM.wire)
        assertEquals("list", PlayMethod.LIST.wire)
        assertEquals("singleLoop", PlayMethod.SINGLE_LOOP.wire)
        assertEquals("none", PlayMethod.STOP_AT_END.wire)
    }

    @Test
    fun `every mode round-trips through its wire value`() {
        PlayMethod.entries.forEach { method ->
            assertEquals(method, PlayMethod.fromWire(method.wire))
        }
    }

    @Test
    fun `unknown and missing values fall back to list loop`() {
        // A fresh install has no stored mode, and an older build could have written a value this
        // one does not know. Both must land on the same default the web client uses.
        assertEquals(PlayMethod.LIST_LOOP, PlayMethod.fromWire(null))
        assertEquals(PlayMethod.LIST_LOOP, PlayMethod.fromWire(""))
        assertEquals(PlayMethod.LIST_LOOP, PlayMethod.fromWire("shuffle"))
    }

    @Test
    fun `cycle visits every user-facing mode and returns to the start`() {
        // The player's tap-to-cycle button. 'none' is deliberately not in the cycle: it is
        // reachable from the settings screen only, so it cannot become a permanent extra tap.
        val visited = mutableListOf<PlayMethod>()
        var current = PlayMethod.LIST_LOOP
        repeat(4) {
            visited += current
            current = current.next()
        }

        assertEquals(
            listOf(
                PlayMethod.LIST_LOOP,
                PlayMethod.RANDOM,
                PlayMethod.SINGLE_LOOP,
                PlayMethod.LIST,
            ),
            visited,
        )
        assertEquals(PlayMethod.LIST_LOOP, current)
    }

    @Test
    fun `next always yields a mode inside the cycle`() {
        // 'none' is reachable only from the settings picker and is intentionally skipped by the
        // cycle: one tap from it lands in the cycle and never returns. Guarding that here keeps a
        // future mode from being added to next() in a way that strands the button.
        PlayMethod.entries.forEach { method ->
            assertEquals(
                "从 $method 出发的 next() 落在了循环之外",
                true,
                method.next() in CYCLE,
            )
        }
    }

    @Test
    fun `every mode is reachable from the cycle`() {
        // 'none' is skipped by the cycle, so it is excluded from what must be reachable; every
        // other mode must be, or the button could never select it.
        val reachable = mutableSetOf<PlayMethod>()
        var current = PlayMethod.LIST_LOOP
        repeat(PlayMethod.entries.size) {
            reachable += current
            current = current.next()
        }

        assertEquals(CYCLE, reachable)
    }

    private companion object {
        /** The modes the player's tap-to-cycle button can actually reach, in order. */
        val CYCLE = setOf(
            PlayMethod.LIST_LOOP,
            PlayMethod.RANDOM,
            PlayMethod.SINGLE_LOOP,
            PlayMethod.LIST,
        )
    }
}
