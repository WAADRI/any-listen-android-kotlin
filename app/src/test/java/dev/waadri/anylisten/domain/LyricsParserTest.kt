package dev.waadri.anylisten.domain

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lyrics arrive as one LRC string with base64 tags for the translation, romanisation and
 * word-by-word layers. Every one of these cases came out of reading `lrcTool.ts`; without them the
 * failure mode is silent — a lyric pane that is simply empty, or one that shows base64 noise.
 */
class LyricsParserTest {

    private fun payload(vararg parts: Pair<String, String>): String {
        val encoder = Base64.getEncoder()
        val body = parts.joinToString(",") { (key, value) ->
            "$key:${encoder.encodeToString(value.toByteArray(Charsets.UTF_8))}"
        }
        return "[awlrc:$body]"
    }

    @Test
    fun `parses plain lrc with no tags`() {
        val lyrics = LyricsParser.parse("[00:01.00]first\n[00:05.50]second\n")

        assertEquals(2, lyrics.lines.size)
        assertEquals("first", lyrics.lines[0].text)
        assertEquals(1_000L, lyrics.lines[0].timeMs)
        assertEquals(5_500L, lyrics.lines[1].timeMs)
    }

    @Test
    fun `reads the timed lyric out of the awlrc tag`() {
        // The real shape produced by buildAwlyric + buildLyrics: the tag key is `awlrc` but it
        // holds ordinary timed lyrics, and the body is the same lyric in plain form. Reading the
        // body alone works here by luck; the next test covers the shape where it does not.
        val timed = "[00:01.00]tagged line\n[00:09.00]another"
        val raw = payload("lrc" to timed) + "\n\n[00:01.00]tagged line\n"

        val lyrics = LyricsParser.parse(raw)

        assertEquals(2, lyrics.lines.size)
        assertEquals("tagged line", lyrics.lines[0].text)
        assertEquals("another", lyrics.lines[1].text)
    }

    @Test
    fun `the tag wins over the body`() {
        // Regression: when the server assembles the display lyric through buildLyrics, the timed
        // text exists ONLY inside the tag and the body is a plain copy with no timestamps at all.
        // A parser that reads the body first returns an empty document here, which is exactly the
        // "lyrics pane is blank" failure this test exists to prevent.
        val raw = payload("lrc" to "[00:01.00]timed one\n[00:09.00]timed two") +
            "\n\ntimed one\ntimed two\n"

        val lyrics = LyricsParser.parse(raw)

        assertEquals(2, lyrics.lines.size)
        assertEquals(listOf(1_000L, 9_000L), lyrics.lines.map { it.timeMs })
        assertEquals(listOf("timed one", "timed two"), lyrics.lines.map { it.text })
    }

    @Test
    fun `merges the translation layer by timestamp`() {
        val raw = payload(
            "lrc" to "[00:01.00]こんにちは\n[00:09.00]さようなら",
            "tlrc" to "[00:01.00]你好\n[00:09.00]再见",
        )

        val lyrics = LyricsParser.parse(raw)

        assertEquals("你好", lyrics.lines[0].translation)
        assertEquals("再见", lyrics.lines[1].translation)
        assertEquals("こんにちは\n你好", lyrics.lines[0].display)
    }

    @Test
    fun `merges the romanisation layer`() {
        val raw = payload(
            "lrc" to "[00:01.00]こんにちは",
            "rlrc" to "[00:01.00]konnichiwa",
        )

        assertEquals("konnichiwa", LyricsParser.parse(raw).lines[0].romanisation)
    }

    @Test
    fun `matches timestamps written in different widths`() {
        // The translation may spell the same instant `0:04.2` where the lyric says `00:04.20`.
        // Comparing the raw strings instead of normalising loses every translation on the line.
        val raw = payload(
            "lrc" to "[00:04.20]line",
            "tlrc" to "[0:04.2]译文",
        )

        assertEquals("译文", LyricsParser.parse(raw).lines[0].translation)
    }

    @Test
    fun `treats missing and short fractions as the same instant`() {
        val raw = payload(
            "lrc" to "[00:04]no fraction",
            "tlrc" to "[00:04.000]译文",
        )

        assertEquals("译文", LyricsParser.parse(raw).lines[0].translation)
    }

    @Test
    fun `expands a line carrying several timestamps`() {
        val lyrics = LyricsParser.parse("[00:01.00][00:30.00]repeated\n[00:10.00]other")

        assertEquals(3, lyrics.lines.size)
        // Sorted by time, so the repeated line appears twice at its two positions.
        assertEquals(listOf(1_000L, 10_000L, 30_000L), lyrics.lines.map { it.timeMs })
        assertEquals("repeated", lyrics.lines[2].text)
    }

    @Test
    fun `drops metadata tags and empty lines`() {
        val lyrics = LyricsParser.parse(
            "[ar:artist]\n[ti:title]\n[00:01.00]real line\n[00:02.00]\n",
        )

        assertEquals(1, lyrics.lines.size)
        assertEquals("real line", lyrics.lines[0].text)
    }

    @Test
    fun `strips word-by-word markup from displayed text`() {
        val lyrics = LyricsParser.parse("[00:04.20]<00:04.20>He<00:04.60>llo\n")

        assertEquals("Hello", lyrics.lines[0].text)
    }

    @Test
    fun `ignores a tag whose payload is not valid base64`() {
        // Better an empty lyric pane than base64 noise rendered as lyrics.
        val lyrics = LyricsParser.parse("[awlrc:lrc:!!!not base64!!!]\n\n[00:01.00]body line\n")

        assertEquals(1, lyrics.lines.size)
        assertEquals("body line", lyrics.lines[0].text)
    }

    @Test
    fun `returns empty for blank input`() {
        assertTrue(LyricsParser.parse("").isEmpty)
        assertTrue(LyricsParser.parse("   \n  ").isEmpty)
    }

    @Test
    fun `returns empty when the text has no timestamps`() {
        assertTrue(LyricsParser.parse("just some prose\nwith no timing").isEmpty)
    }

    @Test
    fun `keeps a base64 body containing plus and slash intact`() {
        // The payload is split on commas, so the base64 value must not be mangled by that split.
        // This fixture is chosen because its encoding actually contains '+' and '/'; a fixture that
        // merely looks symbolic (such as "a/b+c=d") encodes to a plain alphanumeric string and
        // would prove nothing.
        val tricky = "[00:01.00]?~"
        val encoded = Base64.getEncoder().encodeToString(tricky.toByteArray())
        assertTrue("夹具必须真正用到 + 或 / 才能验证", encoded.any { it == '+' || it == '/' })

        assertEquals("?~", LyricsParser.parse(payload("lrc" to tricky)).lines[0].text)
    }

    @Test
    fun `first occurrence of a repeated tag key wins`() {
        // The second tag is consumed as body text by the unwrap, so it must be an untimed string
        // for the body to stay unusable and the tagged copy to remain the timed source.
        val raw = payload("lrc" to "[00:01.00]kept") + "\n\n" + payload("lrc" to "[00:01.00]ignored")

        assertEquals(listOf("kept"), LyricsParser.parse(raw).lines.map { it.text })
    }

    @Test
    fun `uses the supplied fallback when neither tag nor body carries timing`() {
        // The fallback exists for the degenerate payload where the original lyric has no timestamps
        // at all. Rendering the timed layer alone is still better than an empty pane, and this
        // pins that it is actually reachable.
        val lyrics = LyricsParser.parse("plain prose only", plainFallback = "[00:07.00]fallback")

        assertEquals("fallback", lyrics.lines[0].text)
    }

    // ------------------------------------------------------------------ indexAt

    @Test
    fun `indexAt selects the line covering the position`() {
        val lyrics = LyricsParser.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c")

        assertEquals(-1, lyrics.indexAt(0))
        assertEquals(-1, lyrics.indexAt(999))
        assertEquals(0, lyrics.indexAt(1_000))
        assertEquals(0, lyrics.indexAt(4_999))
        assertEquals(1, lyrics.indexAt(5_000))
        assertEquals(2, lyrics.indexAt(9_000))
        // Past the end the last line stays highlighted, rather than nothing.
        assertEquals(2, lyrics.indexAt(600_000))
    }

    @Test
    fun `indexAt is correct for an empty document`() {
        assertEquals(-1, Lyrics.EMPTY.indexAt(5_000))
    }

    @Test
    fun `indexAt finds every line of a longer document`() {
        // Guards the binary search at the boundaries, where an off-by-one hides.
        val source = (0 until 50).joinToString("\n") { "[00:%02d.00]line $it".format(it) }
        val lyrics = LyricsParser.parse(source)

        assertEquals(50, lyrics.lines.size)
        for (i in 0 until 50) {
            assertEquals(i, lyrics.indexAt(i * 1_000L))
            assertEquals(i, lyrics.indexAt(i * 1_000L + 999))
        }
    }
}
