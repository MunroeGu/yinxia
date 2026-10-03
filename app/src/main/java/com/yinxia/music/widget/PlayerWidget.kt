package com.yinxia.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.widget.RemoteViews
import androidx.compose.ui.graphics.asAndroidBitmap
import com.yinxia.music.MainActivity
import com.yinxia.music.R
import com.yinxia.music.data.ArtworkLoader
import com.yinxia.music.data.Song

/**
 * 桌面插件的渲染。
 *
 * 为什么由 App 主动 push，而不是插件自己去查播放状态：
 * RemoteViews 只能"构建好推给系统"，而封面位图只有 App 进程的内存缓存里才有
 * （插件进程重新解码一次既慢、又要重新处理权限）。所以正常更新路径是：
 * 播放状态变化 → App 构建 RemoteViews → 推给桌面。
 *
 * 插件按钮的点击由 [PlayerWidgetProvider] 处理，那里只做局部更新。
 */
object PlayerWidget {

    private const val PREFS = "yinxia_widget"
    private const val KEY_LAST_STATE = "last_state"
    private const val COVER_SIZE = 192
    private const val COVER_RADIUS = 34f

    /** 上一次处理过的封面，避免每次播放/暂停都重新缩一次图 */
    private var coverSongId = -1L
    private var coverImage: Bitmap? = null

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))

    /**
     * 播放状态变化时调用。内部会跳过"没有实质变化"的调用，
     * 所以每秒两次的进度刷新不会造成重复推送。
     */
    fun push(context: Context, song: Song?, isPlaying: Boolean) {
        // 封面是几百毫秒后才解码好的，所以"这一首有没有封面"也要进签名：
        // 否则切歌那一瞬间的推送会把占位图发到桌面，而且之后再也不会重推真封面。
        // coverOf 在拿不到封面时不写缓存，所以后面再调用一次就能取到。
        val cover = coverOf(song)
        val signature = "${song?.id}|${song?.title}|${song?.artist}|$isPlaying|${cover != null}"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_STATE, null) == signature) return
        prefs.edit().putString(KEY_LAST_STATE, signature).apply()
        updateAll(context, song?.title, song?.artist, isPlaying, cover)
    }

    fun updateAll(
        context: Context,
        title: String?,
        artist: String?,
        isPlaying: Boolean,
        cover: Bitmap?,
    ) {
        val ids = widgetIds(context)
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        ids.forEach { id ->
            val layoutRes = layoutFor(manager.getAppWidgetOptions(id))
            manager.updateAppWidget(id, buildViews(context, title, artist, isPlaying, cover, layoutRes))
        }
    }

    /** 只改播放/暂停图标，封面和文字不动 —— 按一下按钮不该让整个插件闪一下 */
    fun updatePlayState(context: Context, isPlaying: Boolean) {
        val ids = widgetIds(context)
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        ids.forEach { id ->
            val partial = RemoteViews(
                context.packageName,
                layoutFor(manager.getAppWidgetOptions(id)),
            ).apply {
                setImageViewResource(
                    R.id.widget_toggle,
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                )
                setContentDescription(
                    R.id.widget_toggle,
                    context.getString(if (isPlaying) R.string.action_pause else R.string.action_play),
                )
                setOnClickPendingIntent(
                    R.id.widget_toggle,
                    commandIntent(context, PlayerWidgetProvider.ACTION_TOGGLE, REQUEST_TOGGLE),
                )
            }
            manager.partiallyUpdateAppWidget(id, partial)
        }
    }

    fun buildViews(
        context: Context,
        title: String?,
        artist: String?,
        isPlaying: Boolean,
        cover: Bitmap?,
        layoutRes: Int,
    ): RemoteViews = RemoteViews(context.packageName, layoutRes).apply {
        setTextViewText(R.id.widget_title, title ?: context.getString(R.string.app_name))
        setTextViewText(R.id.widget_artist, artist ?: context.getString(R.string.widget_idle))

        if (cover != null) {
            setImageViewBitmap(R.id.widget_cover, cover)
        } else {
            setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
        }

        setImageViewResource(
            R.id.widget_toggle,
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
        )
        setContentDescription(
            R.id.widget_toggle,
            context.getString(if (isPlaying) R.string.action_pause else R.string.action_play),
        )

        setOnClickPendingIntent(
            R.id.widget_toggle,
            commandIntent(context, PlayerWidgetProvider.ACTION_TOGGLE, REQUEST_TOGGLE),
        )
        setOnClickPendingIntent(
            R.id.widget_prev,
            commandIntent(context, PlayerWidgetProvider.ACTION_PREV, REQUEST_PREV),
        )
        setOnClickPendingIntent(
            R.id.widget_next,
            commandIntent(context, PlayerWidgetProvider.ACTION_NEXT, REQUEST_NEXT),
        )
        // 注意：不要把"打开 App"挂在根布局上 —— 小米启动器会把它当成整块插件的点击，
        // 子按钮的点击就收不到了（用户反馈的"按钮无响应"很可能就是这个）。
        // 改成只有封面能点开 App。
        setOnClickPendingIntent(R.id.widget_cover, openAppIntent(context))
    }

    /**
     * 按插件当前尺寸挑布局：宽高比 >= 2 用横排，否则用竖排。
     * 2x2 的宽高比接近 1，会走竖排 —— 横排的按钮在窄宽度下会被挤出可用区域。
     */
    fun layoutFor(options: android.os.Bundle): Int {
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        if (minHeight <= 0) return R.layout.widget_player
        return if (minWidth.toFloat() / minHeight >= 2f) {
            R.layout.widget_player
        } else {
            R.layout.widget_player_square
        }
    }

    private fun commandIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PlayerWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 取封面并缩到插件用的小图。
     *
     * 顺便把圆角画进图里：RemoteViews 没法给 ImageView 做圆角裁剪，
     * 只能在生成位图的时候就裁好。
     */
    private fun coverOf(song: Song?): Bitmap? {
        if (song == null) return null
        if (song.id == coverSongId) return coverImage

        val source = ArtworkLoader.cached(song.id)?.asAndroidBitmap() ?: return null
        val scaled = try {
            Bitmap.createScaledBitmap(source, COVER_SIZE, COVER_SIZE, true)
        } catch (_: Throwable) {
            return null
        }

        val rounded = try {
            Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.ARGB_8888).also { output ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                }
                Canvas(output).drawRoundRect(
                    0f,
                    0f,
                    COVER_SIZE.toFloat(),
                    COVER_SIZE.toFloat(),
                    COVER_RADIUS,
                    COVER_RADIUS,
                    paint,
                )
            }
        } catch (_: Throwable) {
            scaled
        }

        coverSongId = song.id
        coverImage = rounded
        return rounded
    }

    private const val REQUEST_TOGGLE = 11
    private const val REQUEST_PREV = 12
    private const val REQUEST_NEXT = 13
}
