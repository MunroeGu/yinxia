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
 * 插件按钮的点击由 [PlayerWidgetProvider] 处理，那里一律整块重画（不用局部更新：
 * 局部更新在部分启动器上会丢掉按钮的点击绑定）。
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
        // coverFor 在拿不到封面时不写缓存，所以后面再调用一次就能取到。
        val cover = coverFor(song?.id)
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
     * 按插件高度挑布局：
     *  - 高度足够（>= 100dp）→ 竖排：封面 + 歌名 + 歌手 + 按钮能叠着放下；
     *  - 否则 → 横排（3x1 / 4x1 这种扁的）。
     *
     * 用高度而不是宽高比：2x2 在不同启动器上上报的宽高差别很大，
     * 而"高度不够就放不下竖排内容"这件事是确定的。
     */
    fun layoutFor(options: android.os.Bundle): Int {
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        if (minHeight <= 0) return R.layout.widget_player
        return if (minHeight >= 100 || minWidth < 180) {
            R.layout.widget_player_square
        } else {
            R.layout.widget_player
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

    /** 只按歌曲 id 取封面（receiver 里只有 mediaId，没有 Song 对象） */
    fun coverFor(songId: Long?): Bitmap? {
        if (songId == null) return null
        if (songId == coverSongId) return coverImage

        val source = ArtworkLoader.cached(songId)?.asAndroidBitmap() ?: return null
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

        coverSongId = songId
        coverImage = rounded
        return rounded
    }

    private const val REQUEST_TOGGLE = 11
    private const val REQUEST_PREV = 12
    private const val REQUEST_NEXT = 13
}
