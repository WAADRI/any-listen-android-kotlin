package dev.waadri.anylisten.data.remote

import kotlinx.serialization.Serializable

/**
 * The music library as the server exposes it over `list.*` calls.
 *
 * Only what the browse screen renders is modelled. The server sends considerably more per-list
 * metadata — sync settings, local paths, per-type extras — and `ignoreUnknownKeys` drops it.
 * Mirroring the full four-way list-type union would be a maintenance liability for no gain.
 */
object Library {

    @Serializable
    data class ListSummary(
        val id: String = "",
        val name: String = "",
        val type: String = "",
        val songCount: Int = 0,
        val coverUrl: String? = null,
    )

    /**
     * Flattens `getAllUserLists()` into one ordered list. Built-in lists come first because they
     * are what a phone user reaches for most; their order is fixed here by the explicit read
     * order rather than by whatever order the response's object keys happen to be in.
     *
     * [baseUrl] is needed because the covers are not absolute URLs; see [ServerUrl].
     */
    fun summaries(all: Wire.MyAllList?, baseUrl: String = ""): List<ListSummary> {
        if (all == null) return emptyList()
        val builtIns = listOfNotNull(all.defaultList, all.loveList, all.lastPlayList)
        return (builtIns + all.userList)
            .map { summary(it, baseUrl) }
            .filter { it.id.isNotEmpty() }
    }

    private fun summary(entry: Wire.MyListEntry, baseUrl: String): ListSummary = ListSummary(
        id = entry.id,
        name = entry.name,
        type = entry.type,
        songCount = M2cCodec.intAt(entry.meta, "songCount") ?: 0,
        coverUrl = ServerUrl.resolve(M2cCodec.stringAt(entry.meta, "pic"), baseUrl),
    )

    /**
     * Cover art per track item id, resolved against the server.
     *
     * Returns a map rather than rewriting the tracks themselves: `Wire.PlayMusicInfo` mirrors what
     * the server sent, and overwriting `meta.picUrl` with a rewritten URL would make the protocol
     * model lie about its own payload.
     *
     * Only URLs are rewritten here. Nothing is fetched: the browse list is a `LazyColumn`, so a
     * cover request is made when a row scrolls into view and not before.
     */
    fun trackCovers(tracks: List<Wire.PlayMusicInfo>, baseUrl: String): Map<String, String> =
        tracks.mapNotNull { track ->
            ServerUrl.resolve(track.musicInfo.meta.picUrl, baseUrl)?.let { track.itemId to it }
        }.toMap()
}

/** `list.getListMusics(listId)` result: a bare array of tracks, no envelope. */
@Serializable
data class ListMusicEntry(
    val id: String = "",
    val name: String = "",
    val singer: String = "",
    val interval: String? = null,
    val meta: Wire.MusicMeta = Wire.MusicMeta(),
) {
    /**
     * `isLocal` is derived rather than read: the server marks local-only tracks with a
     * `deviceId` in their metadata, and the flag matters because those resolve through the
     * server's file proxy rather than a public URL.
     */
    fun toMusicInfo(): Wire.MusicInfo = Wire.MusicInfo(
        id = id,
        name = name,
        singer = singer,
        interval = interval,
        isLocal = meta.deviceId != null,
        meta = meta,
    )
}
