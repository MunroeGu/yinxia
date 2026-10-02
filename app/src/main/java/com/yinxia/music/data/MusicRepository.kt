package com.yinxia.music.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通过 MediaStore 扫描设备上的本地音频。
 *
 * 不走文件系统遍历：一是 Android 10 以后分区存储不允许随便读目录，
 * 二是 MediaStore 有索引，几万首歌也很快。
 */
class MusicRepository(private val context: Context) {

    suspend fun loadSongs(): List<Song> = withContext(Dispatchers.IO) {
        val songs = ArrayList<Song>(256)

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        )

        // IS_MUSIC 能滤掉铃声、通知音、录音；时长下限再滤掉系统音效
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0" +
            " AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf(MIN_DURATION_MS.toString())
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn)
                    songs += Song(
                        id = id,
                        title = title?.takeIf { it.isNotBlank() } ?: UNKNOWN_TITLE,
                        artist = cursor.getString(artistColumn)?.cleanMediaStoreValue(),
                        album = cursor.getString(albumColumn)?.cleanMediaStoreValue(),
                        durationMs = cursor.getLong(durationColumn),
                        uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id,
                        ),
                        albumId = cursor.getLong(albumIdColumn),
                    )
                }
            }
        } catch (_: SecurityException) {
            // 没有读音频权限，交给界面去提示，这里当空库处理
            return@withContext emptyList()
        } catch (_: IllegalArgumentException) {
            // 个别 ROM 的 MediaStore 实现异常，同样按空库处理而不是崩溃
            return@withContext emptyList()
        }

        songs
    }

    /**
     * MediaStore 对缺失的歌手/专辑会塞字面量 "<unknown>"，直接显示很难看，统一转成 null。
     */
    private fun String.cleanMediaStoreValue(): String? =
        takeIf { it.isNotBlank() && !it.equals(UNKNOWN_MEDIA_STORE_VALUE, ignoreCase = true) }

    private companion object {
        const val MIN_DURATION_MS = 15_000L
        const val UNKNOWN_MEDIA_STORE_VALUE = "<unknown>"
        const val UNKNOWN_TITLE = "未知曲目"
    }
}
