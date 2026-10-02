package com.yinxia.music.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面加载：不引第三方图片库。
 *
 * Android 10+ 直接对歌曲的 content URI 调 loadThumbnail，系统会返回内嵌封面；
 * 更老的系统退回老的 albumart 表。任何失败都返回 null，界面用渐变占位兜底。
 */
object ArtworkLoader {

    private const val CACHE_SIZE = 80
    private val memoryCache = LruCache<Long, ImageBitmap>(CACHE_SIZE)

    /** 同步取缓存，用于滚动时先显示已有封面，避免闪一下占位图。 */
    fun cached(songId: Long): ImageBitmap? = memoryCache.get(songId)

    suspend fun load(context: Context, song: Song): ImageBitmap? {
        if (song.albumId <= 0L && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        memoryCache.get(song.id)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { decode(context, song) } ?: return null
        val image = bitmap.asImageBitmap()
        memoryCache.put(song.id, image)
        return image
    }

    fun clear() = memoryCache.evictAll()

    private fun decode(context: Context, song: Song): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(song.uri, THUMBNAIL_SIZE, null)
        } else {
            @Suppress("DEPRECATION")
            context.contentResolver.openInputStream(legacyAlbumArtUri(song.albumId))
                ?.use { stream -> BitmapFactory.decodeStream(stream) }
        }
    } catch (_: Throwable) {
        // 封面缺失、解码失败、权限边缘情况都当没有封面
        null
    }

    /** 老的 albumart 表按专辑 id 索引，注意不是歌曲 id。 */
    private fun legacyAlbumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(
        Uri.parse("content://media/external/audio/albumart"),
        albumId,
    )

    private val THUMBNAIL_SIZE = Size(384, 384)
}
