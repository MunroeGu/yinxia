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

        // 先把状态存下来。必须放在签名去重之前：即使这一次因为"没实质变化"不重推界面，
        // 插件下次重画（onUpdate / 拉伸尺寸）也要能拿到最新的歌名和播放状态。
        val preferences = com.yinxia.music.data.LibraryPreferences(context)
        preferences.widgetTitle = song?.title
        preferences.widgetArtist = song?.artist
        preferences.widgetIsPlaying = isPlaying
        preferences.widgetSongId = song?.id ?: -1L

        val signature = "${song?.id}|${song?.title}|${song?.artist}|$isPlaying|${cover != null}"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_STATE, null) == signature) return
        prefs.edit().putString(KEY_LAST_STATE, signature).apply()
        updateAll(context, song?.title, song?.artist, isPlaying, cover)
    }

    /**
     * 仅用上次存下来的状态重画一次，**完全不连播放服务**。
     *
     * 插件尺寸变化时走这条路：只要连接服务失败，原来那条路径就什么都不会重画，
     * 表现就是"拉成 2x2 布局也不变"。
     */
    fun renderFromStoredState(context: Context) {
        val preferences = com.yinxia.music.data.LibraryPreferences(context)
        val songId = preferences.widgetSongId.takeIf { it > 0 }
        updateAll(
            context = context,
            title = preferences.widgetTitle,
            artist = preferences.widgetArtist,
            isPlaying = preferences.widgetIsPlaying,
            cover = coverFor(songId),
        )
    }

    /**
     * 点一下播放/暂停时**立刻**把图标翻过来（不连服务、不等回调）。
     *
     * 两个作用：
     *  1. 用户马上看到反馈，而不是"按了没反应"；
     *  2. 这是唯一能远程判断"点击到底有没有送到我们进程"的方法 ——
     *     如果图标翻了，说明广播到了，问题在命令那一段；如果图标纹丝不动，说明点击根本没送到。
     * 真正状态稍后由服务回调（或 App 下一次推送）纠正。
     */
    fun toggleIconOptimistically(context: Context) {
        val preferences = com.yinxia.music.data.LibraryPreferences(context)
        val nowPlaying = !preferences.widgetIsPlaying
        preferences.widgetIsPlaying = nowPlaying
        updateAll(
            context = context,
            title = preferences.widgetTitle,
            artist = preferences.widgetArtist,
            isPlaying = nowPlaying,
            cover = coverFor(preferences.widgetSongId.takeIf { it > 0 }),
        )
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

        bindClick(
            R.id.widget_toggle,
            commandIntent(context, PlayerWidgetProvider.ACTION_TOGGLE, REQUEST_TOGGLE),
        )
        bindClick(
            R.id.widget_prev,
            commandIntent(context, PlayerWidgetProvider.ACTION_PREV, REQUEST_PREV),
        )
        bindClick(
            R.id.widget_next,
            commandIntent(context, PlayerWidgetProvider.ACTION_NEXT, REQUEST_NEXT),
        )
        // 注意：不要把"打开 App"挂在根布局上 —— 小米启动器会把它当成整块插件的点击，
        // 子按钮的点击就收不到了（用户反馈的"按钮无响应"很可能就是这个）。
        // 改成只有封面能点开 App。
        bindClick(R.id.widget_cover, openAppIntent(context))
    }

    /**
     * 绑定点击。Android 12+ 用新的 setOnClickResponse（框架现在推荐的方式，
     * 重建视图后更不容易丢），低版本退回 setOnClickPendingIntent。
     *
     * 注意 RemoteResponse 是 RemoteViews 的**嵌套类**（android/widget/RemoteViews$RemoteResponse），
     * 没有 android.widget.RemoteResponse 这个顶层类，写错了会直接编译不过。
     */
    private fun RemoteViews.bindClick(viewId: Int, pendingIntent: PendingIntent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            setOnClickResponse(
                viewId,
                android.widget.RemoteViews.RemoteResponse.fromPendingIntent(pendingIntent),
            )
        } else {
            setOnClickPendingIntent(viewId, pendingIntent)
        }
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
