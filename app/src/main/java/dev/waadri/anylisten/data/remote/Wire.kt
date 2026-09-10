package dev.waadri.anylisten.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Protocol data models, transcribed from any-listen's shared type declarations in
 * `packages/shared/types/types` (the per-domain `.d.ts` files).
 *
 * Conventions:
 *  - Server objects carry fields the client does not need; `ignoreUnknownKeys` in
 *    [M2cCodec.json] keeps those from breaking decoding.
 *  - `encodeDefaults = false` matters for requests: `musicInfo` is echoed back to the server
 *    and must carry every field it sent us, otherwise source resolution can fail.
 *  - Fields are always declared explicitly. No `Map<String, Any>` payload bags.
 */
object Wire {

    @Serializable
    data class MusicMeta(
        val musicId: String = "",
        val albumName: String = "",
        val year: Int? = null,
        val trackNo: Int? = null,
        val discNo: Int? = null,
        val picUrl: String? = null,
        val createTime: Long = 0,
        val updateTime: Long = 0,
        val posTime: Long = 0,
        val unparsed: Boolean? = null,
        // online-only
        val source: String? = null,
        val fileName: String? = null,
        val ext: String? = null,
        val bitrateLabel: String? = null,
        val sizeStr: String? = null,
        // local-only
        val filePath: String? = null,
        val deviceId: String? = null,
    )

    @Serializable
    data class MusicInfo(
        val id: String = "",
        val name: String = "",
        val singer: String = "",
        val interval: String? = null,
        val isLocal: Boolean = false,
        val meta: MusicMeta = MusicMeta(),
    )

    @Serializable
    data class PlayMusicInfo(
        val itemId: String = "",
        val musicInfo: MusicInfo = MusicInfo(),
        val listId: String = "",
        val source: String = "",
        val playLater: Boolean = false,
        val played: Boolean = false,
    )

    /** `player.getPlayInfo()` result. */
    @Serializable
    data class PlayInfo(
        val info: SavedPlayInfo = SavedPlayInfo(),
        val list: List<PlayMusicInfo> = emptyList(),
        val listId: String? = null,
        val source: String = "",
        val historyList: List<HistoryItem> = emptyList(),
        val isCollect: Boolean = false,
    )

    @Serializable
    data class SavedPlayInfo(
        val time: Double = 0.0,
        val maxTime: Double = 0.0,
        val index: Int = 0,
        val historyIndex: Int = 0,
        val lastTrackId: String? = null,
        val isLinkedList: Boolean = false,
    )

    @Serializable
    data class HistoryItem(
        val id: String = "",
        val time: Long = 0,
    )

    @Serializable
    data class GetMusicUrlInfo(
        val musicInfo: MusicInfo,
        val isRefresh: Boolean? = null,
        val quality: String? = null,
    )

    @Serializable
    data class MusicUrlInfo(
        val url: String = "",
        val quality: String = "",
        val isFromCache: Boolean = false,
    )

    @Serializable
    data class GetMusicPicInfo(
        val musicInfo: MusicInfo,
        val isRefresh: Boolean? = null,
    )

    @Serializable
    data class MusicPicInfo(
        val url: String = "",
        val isFromCache: Boolean = false,
    )

    @Serializable
    data class MusicLyricInfo(
        val info: LyricInfo = LyricInfo(),
        val isFromCache: Boolean = false,
    )

    @Serializable
    data class LyricInfo(
        val lyric: String = "",
        val tlyric: String? = null,
        val rlyric: String? = null,
        val awlyric: String? = null,
    )

    // ---------------------------------------------------------------- player events

    @Serializable
    data class Progress(
        val nowPlayTime: Double = 0.0,
        val maxPlayTime: Double = 0.0,
        val progress: Double = 0.0,
        val nowPlayTimeStr: String = "00:00",
        val maxPlayTimeStr: String = "00:00",
    )

    /**
     * Server -> client player event. Field naming follows the wire: the server sends
     * `{ "action": "<name>", "data": <payload> }`.
     */
    @Serializable
    data class PlayerEvent(
        val action: String,
        val data: kotlinx.serialization.json.JsonElement? = null,
    )

    @Serializable
    data class ProgressEventData(
        val nowPlayTime: Double = 0.0,
        val maxPlayTime: Double = 0.0,
        val progress: Double = 0.0,
        val nowPlayTimeStr: String = "00:00",
        val maxPlayTimeStr: String = "00:00",
    )

    @Serializable
    data class MusicChangedData(
        val index: Int = 0,
        val historyIndex: Int = 0,
        val lastTrackId: String? = null,
    )

    /**
     * `data` for a `status` event is a two-element ARRAY: `["playing", true]` — a status string
     * plus the user's intent to be playing. It is positional, not an object, so it is parsed by
     * helpers in [M2cCodec] rather than a `@Serializable` class.
     */
    object StatusEventData {
        const val INDEX_STATUS = 0
        const val INDEX_PLAYING = 1
    }

    // ---------------------------------------------------------------- requests we send

    /**
     * Player action payload. any-listen models this as a union: some variants carry only an
     * action ("next", "prev", "toggle") and others carry an action plus a `data` value
     * ("seek" with seconds, "volume" with 0..1, "playbackRate" with a multiplier). A nullable
     * `data` keeps the action-only variants byte-identical to what the web client sends.
     */
    @Serializable
    data class PlayerAction(
        val action: String,
        val data: kotlinx.serialization.json.JsonElement? = null,
    )

    @Serializable
    data class PlayListSetAction(
        val listId: String? = null,
        val list: List<PlayMusicInfo> = emptyList(),
        val source: String = "",
        @SerialName("isSync") val isSync: Boolean? = null,
    )

    @Serializable
    data class PlayHistorySetAction(
        val list: List<HistoryItem> = emptyList(),
    )
}
