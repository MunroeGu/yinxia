package com.yinxia.music.data

import android.net.Uri

/**
 * 一首本地歌曲。只保留播放和列表要用到的字段。
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
)
