package dev.waadri.anylisten.data.remote

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the flattening of `list.getAllUserLists()`, which is the shape most likely to drift
 * silently: the response nests built-in lists as separate keys, then user lists in an array, and
 * a wrong key here surfaces as an empty browse screen rather than an error.
 */
class LibraryTest {

    private fun list(
        id: String,
        name: String,
        type: String = "general",
        songCount: Int = 0,
        pic: String? = null,
    ): Wire.MyListEntry = Wire.MyListEntry(
        id = id,
        name = name,
        type = type,
        meta = kotlinx.serialization.json.buildJsonObject {
            put("songCount", JsonPrimitive(songCount))
            if (pic != null) put("pic", JsonPrimitive(pic))
        },
    )

    @Test
    fun `built-in lists come first in a fixed order`() {
        val all = Wire.MyAllList(
            defaultList = list("default", "默认列表", type = "default", songCount = 3),
            loveList = list("love", "我喜欢", type = "default", songCount = 5),
            lastPlayList = list("last_played", "最近播放", type = "default", songCount = 2),
            userList = listOf(list("u1", "我的歌单")),
        )

        val summaries = Library.summaries(all)

        assertEquals(listOf("default", "love", "last_played", "u1"), summaries.map { it.id })
    }

    @Test
    fun `names and song counts are read`() {
        val all = Wire.MyAllList(loveList = list("love", "我喜欢", type = "default", songCount = 42))

        val summary = Library.summaries(all).single()

        assertEquals("我喜欢", summary.name)
        assertEquals(42, summary.songCount)
        assertEquals("default", summary.type)
    }

    @Test
    fun `a blank cover url is normalised to null`() {
        val all = Wire.MyAllList(
            loveList = list("love", "我喜欢", pic = ""),
            userList = listOf(list("u1", "有封面", pic = "http://host/pic.jpg")),
        )

        val summaries = Library.summaries(all)

        assertNull(summaries.first { it.id == "love" }.coverUrl)
        assertEquals("http://host/pic.jpg", summaries.first { it.id == "u1" }.coverUrl)
    }

    @Test
    fun `a relative cover url is resolved against the server`() {
        // Real shape from the device: covers come back as a document-relative proxy path, which is
        // why no cover rendered until it was resolved.
        val all = Wire.MyAllList(userList = listOf(list("u1", "有封面", pic = "./api/p_static/abc.jpeg")))

        val summaries = Library.summaries(all, "https://music.waadri.top")

        assertEquals("https://music.waadri.top/api/p_static/abc.jpeg", summaries.single().coverUrl)
    }

    @Test
    fun `missing lists are skipped rather than producing empty rows`() {
        val all = Wire.MyAllList(loveList = list("love", "我喜欢"))

        assertEquals(listOf("love"), Library.summaries(all).map { it.id })
    }

    @Test
    fun `a null catalog yields no rows`() {
        assertEquals(emptyList<Library.ListSummary>(), Library.summaries(null))
    }

    @Test
    fun `entries without an id are dropped`() {
        val all = Wire.MyAllList(userList = listOf(Wire.MyListEntry(id = "", name = "坏数据")))

        assertEquals(emptyList<Library.ListSummary>(), Library.summaries(all))
    }

    @Test
    fun `song count tolerates a missing meta block`() {
        val all = Wire.MyAllList(userList = listOf(Wire.MyListEntry(id = "u1", name = "无 meta")))

        assertEquals(0, Library.summaries(all).single().songCount)
    }

    @Test
    fun `catalog decodes from the server envelope`() {
        val raw = """
            {"defaultList":{"id":"default","name":"默认列表","type":"default",
             "meta":{"songCount":7,"pic":"","playCount":0,"createTime":0,"updateTime":0,
             "posTime":0,"desc":""}},
             "loveList":{"id":"love","name":"我喜欢","type":"default","meta":{"songCount":1}},
             "lastPlayList":{"id":"last_played","name":"最近播放","type":"default","meta":{"songCount":1}},
             "userList":[{"id":"u1","parentId":null,"name":"我的歌单","type":"general","meta":{"songCount":2}}]}
        """.trimIndent()

        val decoded = M2cCodec.json.decodeFromString(Wire.MyAllList.serializer(), raw)
        val summaries = Library.summaries(decoded)

        assertEquals(listOf("default", "love", "last_played", "u1"), summaries.map { it.id })
        assertEquals(7, summaries.first().songCount)
    }

    @Test
    fun `list detail entries map onto the queue model`() {
        val entry = ListMusicEntry(
            id = "m1",
            name = "歌名",
            singer = "歌手",
            interval = "03:21",
            meta = Wire.MusicMeta(musicId = "m1", albumName = "专辑", picUrl = "http://host/p.jpg"),
        )

        val info = entry.toMusicInfo()

        assertEquals("m1", info.id)
        assertEquals("歌名", info.name)
        assertEquals("歌手", info.singer)
        assertEquals("专辑", info.meta.albumName)
    }

    @Test
    fun `a device id marks the track as local`() {
        val remote = ListMusicEntry(id = "m1", name = "在线", meta = Wire.MusicMeta(musicId = "m1"))
        val local = ListMusicEntry(
            id = "m2",
            name = "本地",
            meta = Wire.MusicMeta(musicId = "m2", deviceId = "device-1"),
        )

        assertEquals(false, remote.toMusicInfo().isLocal)
        assertEquals(true, local.toMusicInfo().isLocal)
    }

    @Test
    fun `track array decodes from a bare json array`() {
        val raw = """[{"id":"m1","name":"a","singer":"s","interval":"01:00","meta":{"musicId":"m1"}},
                      {"id":"m2","name":"b","singer":"s","meta":{"musicId":"m2"}}]"""

        val decoded = M2cCodec.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ListMusicEntry.serializer()),
            raw,
        )

        assertEquals(listOf("m1", "m2"), decoded.map { it.id })
        assertEquals("01:00", decoded.first().interval)
    }
}
