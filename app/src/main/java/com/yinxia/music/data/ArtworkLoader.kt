package com.yinxia.music.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面加载与配色提取：不引第三方图片库，也不引 Palette。
 *
 * Android 10+ 直接对歌曲的 content URI 调 loadThumbnail，系统会返回内嵌封面；
 * 更老的系统退回老的 albumart 表。任何失败都返回 null，界面用渐变占位兜底。
 */
object ArtworkLoader {

    private const val CACHE_SIZE = 80
    private val memoryCache = LruCache<Long, ImageBitmap>(CACHE_SIZE)

    /** 每首歌从封面里挑出的"种子色"（还没有按明暗主题调整），key 是歌曲 id */
    private val seedCache = LruCache<Long, Color>(CACHE_SIZE)

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

    /**
     * 取这首歌封面的主色（种子色，未按主题调整）。
     *
     * 已经有封面缓存时直接复用那张图，不重复解码。
     * 返回 null 表示这首歌没有可用封面 —— 调用方应该退回用户设定的默认色。
     */
    suspend fun coverColor(context: Context, song: Song): Color? {
        seedCache.get(song.id)?.let { return it }

        val bitmap = memoryCache.get(song.id)?.asAndroidBitmap()
            ?: withContext(Dispatchers.IO) { decode(context, song) }
            ?: return null

        val seed = withContext(Dispatchers.Default) { extractDominantColor(bitmap) } ?: return null
        seedCache.put(song.id, seed)
        return seed
    }

    fun clear() {
        memoryCache.evictAll()
        seedCache.evictAll()
    }

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

    /**
     * 从封面里挑一个能当主题色用的颜色。
     *
     * 不用"取平均色"：平均之后通常是一片发灰的泥浆色，放到界面上很难看。这里做了三件事：
     *  1. 缩到 24x24 再统计，几百张封面也不会卡；
     *  2. 量化成 4 位/通道的直方图，按 [出现次数 × (0.25 + 平均饱和度)] 打分 ——
     *     让"面积小但鲜艳"的颜色也能和"面积大但发灰"的颜色竞争；
     *  3. 丢掉接近黑/白/灰的像素，它们当主题色没有意义。
     */
    private fun extractDominantColor(bitmap: Bitmap): Color? {
        val scaled = try {
            Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        } catch (_: Throwable) {
            return null
        }
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        scaled.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        // 每个桶累加：[像素数, r, g, b, 饱和度之和]
        val buckets = HashMap<Int, FloatArray>(64)
        val hsv = FloatArray(3)

        pixels.forEach { argb ->
            if (((argb ushr 24) and 0xFF) < 128) return@forEach

            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF

            AndroidColor.RGBToHSV(r, g, b, hsv)
            val saturation = hsv[1]
            val value = hsv[2]
            if (saturation < 0.15f || value < 0.15f || value > 0.95f) return@forEach

            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val bucket = buckets.getOrPut(key) { FloatArray(5) }
            bucket[0] += 1f
            bucket[1] += r
            bucket[2] += g
            bucket[3] += b
            bucket[4] += saturation
        }

        if (buckets.isEmpty()) return null

        var winner: FloatArray? = null
        var bestScore = 0f
        buckets.values.forEach { bucket ->
            val count = bucket[0]
            val averageSaturation = bucket[4] / count
            val score = count * (0.25f + averageSaturation)
            if (score > bestScore) {
                bestScore = score
                winner = bucket
            }
        }

        val best = winner ?: return null
        val count = best[0]
        return Color(
            red = (best[1] / count) / 255f,
            green = (best[2] / count) / 255f,
            blue = (best[3] / count) / 255f,
            alpha = 1f,
        )
    }

    /** 老的 albumart 表按专辑 id 索引，注意不是歌曲 id。 */
    private fun legacyAlbumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(
        Uri.parse("content://media/external/audio/albumart"),
        albumId,
    )

    private const val SAMPLE_SIZE = 24
    private val THUMBNAIL_SIZE = Size(384, 384)
}
