package dev.waadri.anylisten.domain

/**
 * The saved resume point, and the rules that decide where it may land.
 *
 * ## The bug this type documents
 *
 * Resume-on-launch used to read the saved point as soon as `ClientSession` handed the socket over.
 * But the socket is handed over *before* `start()` is called on it, so it was still `Idle`, every
 * request failed instantly, and the feature did nothing at all — with no error and no log. The fix
 * was not a better check but a different trigger: the restore now runs on the socket's
 * `Connected` emission ([dev.waadri.anylisten.playback.PlaybackRepository.observeResume]), which
 * also means a failed first connect retries instead of giving up.
 *
 * A `ResumePolicy` decision function was written alongside this and then removed: `observeResume`
 * already only runs when the socket is connected, so the function had no caller. Keeping tested
 * dead code would have implied coverage of a path nothing takes.
 */
data class ResumePoint(
    val listId: String,
    val trackIndex: Int,
    val positionMs: Long,
) {
    /**
     * Clamps the saved index into a list of [trackCount] tracks.
     *
     * Lists change between launches: tracks get deleted, or the whole list is replaced. A stale
     * index must land somewhere valid rather than crashing or resuming a track that is not there.
     * Returns null when there is nothing to index into.
     */
    fun indexFor(trackCount: Int): Int? {
        if (trackCount <= 0) return null
        return trackIndex.coerceIn(0, trackCount - 1)
    }

    /**
     * The position to seek to.
     *
     * ExoPlayer reports -1 before its source is prepared, so a negative saved value is normalised
     * to the start of the track rather than passed on as an invalid seek target.
     */
    fun positionFor(): Long = positionMs.coerceAtLeast(0L)
}
