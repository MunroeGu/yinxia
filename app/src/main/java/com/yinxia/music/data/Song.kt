package com.yinxia.music.data

import android.net.Uri

/**
 * 一首本地歌曲。
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val uri: Uri,
    /** 只用于 Android 9 及以下的封面回退（老的 albumart 表按专辑 id 索引） */
    val albumId: Long,
    /** 所在文件夹的稳定标识，空字符串表示内部存储根目录 */
    val folderKey: String,
    /** 文件夹短名，给界面显示用 */
    val folderName: String,
    /** MediaStore 记录的加入时间（毫秒） */
    val dateAddedMs: Long,
)
