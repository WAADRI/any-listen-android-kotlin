package dev.waadri.anylisten.domain

import java.util.Base64

/** One timed lyric line, already merged with its translation and romanisation. */
data class LyricLine(
    val timeMs: Long,
    /** Original lyric text for this timestamp. */
    val text: String,
    /** Translation, when the server supplied one for this exact timestamp. */
    val translation: String? = null,
    /** Romanisation, when the server supplied one for this exact timestamp. */
    val romanisation: String? = null,
) {
    /** What the UI renders for this line. */
    val display: String
        get() = listOfNotNull(text.takeIf { it.isNotBlank() }, translation?.takeIf { it.isNotBlank() })
            .joinToString("\n")
}

/** A parsed lyric document, ordered by time. */
data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * Index of the line that should be highlighted at [positionMs], or `-1` before the first line
     * starts (intro) and for an empty document.
     *
     * Binary search rather than a scan: this is called on every playback tick, and a long song can
     * carry a few hundred lines.
     */
    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (positionMs < lines[0].timeMs) return -1

        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    companion object {
        val EMPTY = Lyrics()
    }
}

/**
 * Parses the server's lyric payload.
 *
 * ## Why this is less trivial than it looks
 *
 * any-listen does not send structured lyric fields. It sends a **single LRC string** in which the
 * translation, romanisation and word-by-word lyrics are base64-encoded into tags appended to the
 * plain text:
 *
 * ```text
 * [awlrc:lrc:<base64>,tlrc:<base64>,rlrc:<base64>,awlrc:<base64>]
 *
 * [00:01.00]plain text
 * [00:05.00]...
 * ```
 *
 * The server produces this in `lrcTool.ts` (`buildAwlyric` / `buildLyrics`), and the web client
 * unwraps it with `parseLyrics`. Decoding it here rather than on the server is deliberate: the
 * server is not modified by this project.
 *
 * Three details, each of which silently produces empty or wrong lyrics if missed:
 *
 *  1. The tag payload is comma-separated and **the base64 value itself contains no commas**
 *     (base64 alphabet is `A-Za-z0-9+/=`), so splitting on `,` is safe. The whole tag, however,
 *     does contain commas, so the tag body cannot be matched with a comma-excluding pattern.
 *  2. The timed lyric lives under the tag's `lrc` key, and the body is often **not timed at all**.
 *     `buildLyrics` prepends the word-by-word block to the body and then drops every line that
 *     carries a timestamp, so a parser that reads the body first — or that looks for the tag under
 *     a key named after the outer `awlrc:` label rather than the inner `lrc:` key — produces a
 *     completely empty lyric pane on real data. `awlrc` (word-by-word markup) is preferred when
 *     present because it is the richer source for the same instants.
 *  3. The timestamp key must be normalised before use as a map key. A translation line may be
 *     written `[0:04.2]` where the lyric says `[00:04.20]`; comparing raw strings loses the match
 *     and every translation silently disappears.
 */
object LyricsParser {

    /** `[awlrc:<payload>]`. The payload may contain commas, so it is matched loosely then split. */
    private val AWLRC_TAG = Regex("""\[awlrc:([^]]*)]""", RegexOption.IGNORE_CASE)

    /** One `key:base64` pair inside the tag payload. */
    private val TAG_ENTRY = Regex("""^([a-z]+):(.+)$""", RegexOption.IGNORE_CASE)

    /** Leading timestamp fields on a line, e.g. the `[00:01.00][00:05.00]` of a repeated line. */
    private val TIME_FIELD = Regex("""(?:\[[\d:.]*])+""")

    /** A single `mm:ss(.fff)` inside a timestamp field. */
    private val TIME = Regex("""(\d{1,3}):(\d{1,3})(?:\.(\d{1,3}))?""")

    /** Word-by-word markup the plain lyric may carry, e.g. `<00:04.20>`. */
    private val AW_MARKUP = Regex("""<\d+:\d+(?:\.\d+)?>""")

    /**
     * `parseLyrics` from `lrcTool.ts`: lifts the base64 payload out of the tag and returns the
     * remaining text as the plain lyric body.
     */
    fun unwrap(raw: String): Unwrapped {
        val match = AWLRC_TAG.find(raw)
        if (match == null) return Unwrapped(payload = emptyMap(), body = raw.trim())

        val payload = mutableMapOf<String, String>()
        for (entry in match.groupValues[1].split(',')) {
            val parsed = TAG_ENTRY.find(entry.trim()) ?: continue
            val key = parsed.groupValues[1].lowercase()
            // First occurrence wins, so a repeated key cannot overwrite a good value.
            if (key in payload) continue
            decodeBase64(parsed.groupValues[2])?.let { payload[key] = it }
        }

        return Unwrapped(payload = payload, body = raw.replace(match.value, "").trim())
    }

    /**
     * Parses a payload into a document.
     *
     * @param raw the `lyric` string exactly as the server sent it.
     * @param plainFallback an already-known plain LRC string, used only if neither the tag nor the
     *   body carries timing. Normally null.
     */
    fun parse(raw: String, plainFallback: String? = null): Lyrics {
        if (raw.isBlank() && plainFallback.isNullOrBlank()) return Lyrics.EMPTY
        val unwrapped = unwrap(raw)

        // The timed source, in order of authority.
        //
        // The tag's ordinary key is `lrc`, and that is where the timed lyric physically lives:
        // `buildLyrics` prepends the word-by-word block to the body and then strips every line
        // carrying a timestamp, so in the common case THE BODY HAS NO TIMING AT ALL and reading it
        // yields a completely empty lyric pane. `awlrc` (word-by-word markup) is consulted first
        // when present because it is the richer source for the same instants; the body is only a
        // last resort for payloads that carry no tag.
        val base = sequenceOf("awlrc", "lrc")
            .mapNotNull { unwrapped.payload[it] }
            .plus(unwrapped.body)
            .plus(sequenceOf(plainFallback))
            .filterNotNull()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && hasTimestamps(it) }
            ?: return Lyrics.EMPTY

        // Translation and romanisation come from the tag payload only. The body is never a
        // translation source, so including it would only duplicate the original line under itself.
        val translations = indexByTime(unwrapped.payload["tlrc"])
        val romanisations = indexByTime(unwrapped.payload["rlrc"])

        val lines = mutableListOf<LyricLine>()
        for (rawLine in base.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val timeField = TIME_FIELD.find(line) ?: continue
            // Metadata such as [ar:...] or [ti:...] has no leading timestamp and is skipped here;
            // a line whose whole content is a metadata tag yields empty text and is dropped below.
            val text = AW_MARKUP.replace(line.replace(timeField.value, ""), "").trim()
            if (text.isEmpty()) continue

            for (time in TIME.findAll(timeField.value)) {
                val timeMs = toMillis(time) ?: continue
                lines += LyricLine(
                    timeMs = timeMs,
                    text = text,
                    translation = translations[timeKey(time)],
                    romanisation = romanisations[timeKey(time)],
                )
            }
        }

        // Stable sort: multi-timestamp lines were emitted in file order and must stay that way for
        // equal times, which is what a displayed lyric expects.
        return Lyrics(lines.sortedBy { it.timeMs })
    }

    private fun indexByTime(source: String?): Map<String, String> {
        if (source.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (rawLine in source.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val timeField = TIME_FIELD.find(line) ?: continue
            val text = AW_MARKUP.replace(line.replace(timeField.value, ""), "").trim()
            if (text.isEmpty()) continue
            for (time in TIME.findAll(timeField.value)) {
                result.putIfAbsent(timeKey(time), text)
            }
        }
        return result
    }

    /**
     * The normalised `mm:ss.fff` identity of a timestamp.
     *
     * Two spellings of the same instant (`0:04.2` and `00:04.20`) must produce the same key, which
     * is why this is not the raw matched text.
     */
    private fun timeKey(match: MatchResult): String {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fraction = match.groupValues[3]
        // `.2` is two tenths, `.20` is twenty hundredths and `.200` is two hundred milliseconds:
        // right-pad to milliseconds so all three compare equal.
        val millis = fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return "%d:%02d.%03d".format(minutes, seconds, millis)
    }

    private fun toMillis(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fraction = match.groupValues[3]
        val millis = if (fraction.isEmpty()) 0L else fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun hasTimestamps(text: String): Boolean = TIME.containsMatchIn(text)

    private fun decodeBase64(value: String): String? = try {
        String(Base64.getDecoder().decode(value.trim()), Charsets.UTF_8).trim().ifBlank { null }
    } catch (e: IllegalArgumentException) {
        // Not valid base64: the tag exists but is not a payload we understand. Dropping it is
        // better than showing base64 noise as lyrics.
        null
    }

    /** The two halves of an unwrapped payload. */
    data class Unwrapped(val payload: Map<String, String>, val body: String)
}
