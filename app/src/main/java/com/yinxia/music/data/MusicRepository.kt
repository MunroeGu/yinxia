package com.yinxia.music.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通过 MediaStore 扫描设备上的本地音频，并汇总出文件夹列表。
 *
 * 不走文件系统遍历：一是 Android 10 以后分区存储不允许随便读目录，
 * 二是 MediaStore 有索引，几万首歌也很快。
 */
class MusicRepository(private val context: Context) {

    suspend fun loadSongs(): List<Song> = withContext(Dispatchers.IO) {
        val songs = ArrayList<Song>(256)

        // Android 10 起用相对路径（如 "Music/Albums/"）标识文件夹，
        // 更老的系统只有绝对路径 "_data" 可用。
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val folderColumnName = resolveFolderColumnName(useRelativePath)

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            folderColumnName,
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
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val folderColumn = cursor.getColumnIndexOrThrow(folderColumnName)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn)
                    val folderKey = folderKeyOf(
                        rawFolder = cursor.getString(folderColumn),
                        useRelativePath = useRelativePath,
                    )

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
                        folderKey = folderKey,
                        folderName = folderNameOf(folderKey),
                        // DATE_ADDED 是秒，统一成毫秒
                        dateAddedMs = cursor.getLong(dateAddedColumn) * 1000L,
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

    /** MediaStore 对缺失的歌手/专辑会塞字面量 "<unknown>"，直接显示很难看，统一转成 null。 */
    private fun String.cleanMediaStoreValue(): String? =
        takeIf { it.isNotBlank() && !it.equals(UNKNOWN_MEDIA_STORE_VALUE, ignoreCase = true) }

    @Suppress("DEPRECATION")
    private fun resolveFolderColumnName(useRelativePath: Boolean): String =
        if (useRelativePath) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA

    /**
     * 把 MediaStore 返回的原始路径归一成文件夹标识：
     * 相对路径去掉首尾斜杠（"Music/Albums/" -> "Music/Albums"），
     * 绝对路径取父目录（"/storage/emulated/0/Music/a.mp3" -> "/storage/emulated/0/Music"）。
     */
    private fun folderKeyOf(rawFolder: String?, useRelativePath: Boolean): String {
        if (rawFolder.isNullOrBlank()) return ""
        val trimmed = rawFolder.trim()
        return if (useRelativePath) {
            trimmed.trim('/')
        } else {
            trimmed.substringBeforeLast('/', "")
        }
    }

    private fun folderNameOf(folderKey: String): String {
        if (folderKey.isEmpty()) return ROOT_FOLDER_NAME
        val last = folderKey.trimEnd('/').substringAfterLast('/')
        return last.ifEmpty { folderKey }
    }

    companion object {
        private const val MIN_DURATION_MS = 15_000L
        private const val UNKNOWN_MEDIA_STORE_VALUE = "<unknown>"
        private const val UNKNOWN_TITLE = "未知曲目"
        const val ROOT_FOLDER_NAME = "内部存储根目录"

        /** 按文件夹汇总，用于「扫描范围」里的勾选列表。 */
        fun buildFolders(songs: List<Song>): List<FolderEntry> =
            songs.groupBy { it.folderKey }
                .map { (key, group) ->
                    FolderEntry(key = key, displayName = group.first().folderName, songCount = group.size)
                }
                .sortedWith(
                    compareBy<FolderEntry>({ it.displayName.lowercase() }, { it.key }),
                )
    }
}
