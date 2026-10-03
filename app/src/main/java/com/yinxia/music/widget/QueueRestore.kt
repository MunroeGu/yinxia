package com.yinxia.music.widget

import android.content.Context
import androidx.media3.session.MediaController
import com.yinxia.music.data.LibraryPreferences
import com.yinxia.music.data.MusicRepository
import com.yinxia.music.player.toMediaItem

/**
 * 进程被系统杀掉后，播放队列只在内存里，于是插件按播放等于对空队列下命令 ——
 * 表现为"按钮完全没反应"。这里用上次存下来的歌曲 id 顺序把队列重建出来。
 *
 * 返回 true 表示确实重建了（调用方随后执行原本的命令）。
 */
object QueueRestore {

    suspend fun restoreIfEmpty(context: Context, controller: MediaController): Boolean {
        if (controller.mediaItemCount > 0) return false

        val preferences = LibraryPreferences(context)
        val wantedIds = preferences.queueSongIds
        if (wantedIds.isEmpty()) return false

        val songsById = MusicRepository(context).loadSongs().associateBy { it.id }
        // 按存下来的顺序重建，歌曲已被删掉的直接跳过
        val songs = wantedIds.mapNotNull { songsById[it] }
        if (songs.isEmpty()) return false

        val index = preferences.queueIndex.coerceIn(0, songs.lastIndex)
        controller.setMediaItems(songs.map { it.toMediaItem() }, index, 0L)
        controller.prepare()
        return true
    }
}
