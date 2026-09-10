package dev.waadri.anylisten.domain

import dev.waadri.anylisten.data.remote.PlayMethod
import dev.waadri.anylisten.data.remote.Wire

/**
 * Everything the play-order rules need to decide what plays next.
 *
 * Modelled from any-listen's `playerActions.ts` `parsePlayList` / `getNextPlayMusicInfo` /
 * `getPrevPlayMusicInfo`, including the "play later" queue that jumps the normal order.
 */
data class PlayQueueSnapshot(
    val list: List<Wire.PlayMusicInfo> = emptyList(),
    val currentIndex: Int = 0,
    val historyList: List<Wire.HistoryItem> = emptyList(),
    /** Random mode resumes the previous session by replaying `historyList` from here. */
    val historyIndex: Int = 0,
    val method: PlayMethod = PlayMethod.LIST_LOOP,
    /** Item ids the user disliked; excluded from the normal list but kept if currently playing. */
    val dislikedIds: Set<String> = emptySet(),
) {
    val current: Wire.PlayMusicInfo? get() = list.getOrNull(currentIndex)
}

/** What to play next, or why playback should stop. */
sealed interface PlayOrderDecision {
    data class Play(val music: Wire.PlayMusicInfo) : PlayOrderDecision

    /** No successor exists under the current mode — the caller must stop and report `ended`. */
    data object Stop : PlayOrderDecision
}

/**
 * Pure play-order state machine. Deliberately free of Android and coroutine types so the
 * whole of any-listen's skip/single-loop/random/resume behaviour is unit-testable on the JVM.
 */
object PlayOrderResolver {

    fun next(
        snapshot: PlayQueueSnapshot,
        random: java.util.Random = java.util.Random(),
    ): PlayOrderDecision {
        val current = snapshot.current

        // "Play later" entries always win over normal order and loop within themselves.
        // Note the upstream branch shape: when the current track is a play-later entry the
        // normal list is never consulted, so a lone play-later entry simply repeats — which is
        // also what makes "play this one next" behave as expected.
        val playLater = snapshot.list.filter { it.playLater }
        if (current?.playLater == true) {
            if (playLater.isEmpty()) return PlayOrderDecision.Stop
            val idx = playLater.indexOfFirst { it.itemId == current.itemId }
            val nextIdx = if (idx < 0) 0 else (idx + 1) % playLater.size
            return PlayOrderDecision.Play(playLater[nextIdx])
        }
        if (playLater.isNotEmpty()) {
            return PlayOrderDecision.Play(playLater.first())
        }

        val normal = normalList(snapshot)
        // Upstream guard: a lone, disliked, non-play-later track has nowhere to go, so the
        // switch-based rule below (which would wrap back onto itself) must not run.
        if (normal.isEmpty()) return PlayOrderDecision.Stop
        if (
            normal.size == 1 &&
            current != null &&
            !current.playLater &&
            snapshot.dislikedIds.contains(current.itemId)
        ) {
            return PlayOrderDecision.Stop
        }

        if (snapshot.method == PlayMethod.RANDOM) {
            return nextRandom(snapshot, normal, random)
        }

        val currentNormalIndex = normal.indexOfFirst { it.itemId == current?.itemId }
        if (currentNormalIndex < 0) {
            // Current track is not part of the normal order (e.g. disliked while playing):
            // resume from the top rather than skipping blindly.
            return PlayOrderDecision.Play(normal.first())
        }

        val nextIndex = when (snapshot.method) {
            PlayMethod.LIST_LOOP -> if (currentNormalIndex == normal.size - 1) 0 else currentNormalIndex + 1
            PlayMethod.LIST, PlayMethod.STOP_AT_END -> if (currentNormalIndex == normal.size - 1) -1 else currentNormalIndex + 1
            PlayMethod.SINGLE_LOOP -> currentNormalIndex
            PlayMethod.RANDOM -> currentNormalIndex
        }

        return if (nextIndex < 0) PlayOrderDecision.Stop else PlayOrderDecision.Play(normal[nextIndex])
    }

    /**
     * Random: walk forward through the recorded history when we are resuming a session,
     * otherwise prefer tracks never played in this pass, and fall back to any track once
     * everything has been played (a new pass begins).
     */
    private fun nextRandom(
        snapshot: PlayQueueSnapshot,
        normal: List<Wire.PlayMusicInfo>,
        random: java.util.Random,
    ): PlayOrderDecision {
        if (snapshot.historyIndex >= 0) {
            var idx = snapshot.historyIndex + 1
            while (idx < snapshot.historyList.size) {
                val targetId = snapshot.historyList[idx].id
                normal.firstOrNull { it.itemId == targetId }?.let { return PlayOrderDecision.Play(it) }
                idx++
            }
        }

        val currentItemId = snapshot.current?.itemId
        val unplayed = normal.filter { !it.played && it.itemId != currentItemId }
        val pool = unplayed.ifEmpty { normal }
        val chosen = pool[random.nextInt(pool.size)]
        return PlayOrderDecision.Play(chosen)
    }

    /**
     * Previous. Mirrors upstream: in random mode the recorded history is authoritative, and
     * when there is nothing to go back to it falls through to the normal ordered rules.
     */
    fun prev(snapshot: PlayQueueSnapshot): PlayOrderDecision {
        if (snapshot.method == PlayMethod.RANDOM && snapshot.historyIndex > 0) {
            val targetId = snapshot.historyList.getOrNull(snapshot.historyIndex - 1)?.id
            val target = targetId?.let { id -> snapshot.list.firstOrNull { it.itemId == id } }
            if (target != null) return PlayOrderDecision.Play(target)
        }

        val normal = normalList(snapshot)
        if (normal.isEmpty()) return PlayOrderDecision.Stop

        val currentNormalIndex = normal.indexOfFirst { it.itemId == snapshot.current?.itemId }
        if (currentNormalIndex < 0) return PlayOrderDecision.Play(normal.first())

        val prevIndex = if (currentNormalIndex == 0) normal.size - 1 else currentNormalIndex - 1
        return PlayOrderDecision.Play(normal[prevIndex])
    }

    /**
     * `parsePlayList` from upstream. "Play later" entries are removed from the normal order,
     * and disliked entries are dropped — except the one currently playing, which must stay
     * visible or it would vanish mid-song.
     */
    fun normalList(snapshot: PlayQueueSnapshot): List<Wire.PlayMusicInfo> {
        val currentId = snapshot.current?.itemId
        return snapshot.list.filter { item ->
            if (item.playLater) return@filter false
            !snapshot.dislikedIds.contains(item.itemId) || item.itemId == currentId
        }
    }
}
