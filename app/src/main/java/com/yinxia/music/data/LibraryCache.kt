package com.yinxia.music.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 音乐库的本地缓存。
 *
 * 冷启动时 MediaStore 扫描要等一会儿，界面会一直空着 —— 用户感受到的就是"几秒卡顿"。
 * 这里把上次扫到的结果存成 JSON，下次启动先把旧数据画出来（几乎瞬间），
 * 再在后台扫描并用新结果覆盖，所以看到的永远是"有内容 + 稍后自动更新"。
 *
 * 用 org.json 而不是引入序列化库：字段少、结构简单，不值得多一个依赖。
 */
object LibraryCache {

    private const val FILE_NAME = "library_cache.json"

    suspend fun save(context: Context, songs: List<Song>) = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray()
            songs.forEach { song ->
                array.put(
                    JSONObject().apply {
                        put("id", song.id)
                        put("title", song.title)
                        put("artist", song.artist)
                        put("album", song.album)
                        put("durationMs", song.durationMs)
                        put("uri", song.uri.toString())
                        put("albumId", song.albumId)
                        put("folderKey", song.folderKey)
                        put("folderName", song.folderName)
                        put("dateAddedMs", song.dateAddedMs)
                        put("bitrate", song.bitrate ?: -1)
                        put("sampleRate", song.sampleRate ?: -1)
                        put("sizeBytes", song.sizeBytes)
                        put("mimeType", song.mimeType)
                    },
                )
            }
            file(context).writeText(array.toString())
        }
        Unit
    }

    suspend fun load(context: Context): List<Song>? = withContext(Dispatchers.IO) {
        runCatching {
            val target = file(context)
            if (!target.exists()) return@runCatching null

            val array = JSONArray(target.readText())
            if (array.length() == 0) return@runCatching null

            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optLong("id", -1L)
                val uri = item.optString("uri", "")
                if (id <= 0L || uri.isEmpty()) return@mapNotNull null

                Song(
                    id = id,
                    title = item.optString("title"),
                    artist = item.optString("artist").ifBlank { null },
                    album = item.optString("album").ifBlank { null },
                    durationMs = item.optLong("durationMs"),
                    // Song.uri 是 android.net.Uri，缓存里存的是字符串，取出来必须还原
                    uri = Uri.parse(uri),
                    albumId = item.optLong("albumId", -1L),
                    folderKey = item.optString("folderKey", ""),
                    folderName = item.optString("folderName", ""),
                    dateAddedMs = item.optLong("dateAddedMs"),
                    bitrate = item.optInt("bitrate", -1).takeIf { it > 0 },
                    sampleRate = item.optInt("sampleRate", -1).takeIf { it > 0 },
                    sizeBytes = item.optLong("sizeBytes", 0L),
                    mimeType = item.optString("mimeType").ifBlank { null },
                )
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
