package com.yinxia.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.yinxia.music.player.PlaybackConnection
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 桌面插件。
 *
 * 界面的正常刷新由 App 主动 push（见 [PlayerWidget]），这里负责两件事：
 *  1. 插件刚被添加到桌面、或系统要求刷新时，按播放服务的真实状态画一次；
 *  2. 处理插件上的按钮点击。
 *
 * 按钮要真正控制播放，必须通过 MediaController 连到 PlaybackService。
 * 连上或失败都必须结束 goAsync 的任务，否则系统会认为广播处理超时。
 */
class PlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 先画一个静态样子，避免插件刚加上去是一片空白
        PlayerWidget.updateAll(context, null, null, false, null)
        refreshFromService(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        // 用户拉伸了插件：立刻按新尺寸重画一次，并顺手从播放服务取一次真实状态
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        refreshFromService(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (val action = intent.action) {
            ACTION_TOGGLE, ACTION_NEXT, ACTION_PREV -> handleCommand(context, action)
        }
    }

    private fun refreshFromService(context: Context) {
        withConnection(context) { controller ->
            PlayerWidget.updateAll(
                context = context,
                title = controller.currentMediaItem?.mediaMetadata?.title?.toString(),
                artist = controller.currentMediaItem?.mediaMetadata?.artist?.toString(),
                isPlaying = controller.isPlaying,
                cover = PlayerWidget.coverFor(controller.currentMediaItem?.mediaId?.toLongOrNull()),
            )
        }
    }

    private fun handleCommand(context: Context, action: String) {
        withConnection(context) { controller ->
            // 队列空了（进程刚被杀过）就先按上次的顺序重建，再执行命令
            QueueRestore.restoreIfEmpty(context, controller)

            when (action) {
                ACTION_TOGGLE -> if (controller.isPlaying) controller.pause() else {
                    if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                    controller.play()
                }

                ACTION_NEXT -> controller.seekToNextMediaItem()

                ACTION_PREV -> controller.seekToPreviousMediaItem()
            }

            // 一律整块重画，不用局部更新：局部更新在部分启动器上会丢掉按钮的点击绑定
            //（用户反馈"暂停后再按播放没反应"就是这个），整块重画会重新注册所有 PendingIntent
            PlayerWidget.updateAll(
                context = context,
                title = controller.currentMediaItem?.mediaMetadata?.title?.toString(),
                artist = controller.currentMediaItem?.mediaMetadata?.artist?.toString(),
                isPlaying = controller.isPlaying,
                cover = PlayerWidget.coverFor(controller.currentMediaItem?.mediaId?.toLongOrNull()),
            )
        }
    }

    /** 连上播放服务执行一段操作，无论成功失败都在超时前结束广播任务。 */
    private fun withConnection(
        context: Context,
        block: suspend (androidx.media3.session.MediaController) -> Unit,
    ) {
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) {
                scope.cancel()
                pending.finish()
            }
        }

        val connection = PlaybackConnection(context)
        connection.connect { controller ->
            scope.launch {
                try {
                    block(controller)
                } finally {
                    // 延迟断开：命令是转给播放服务后才生效的，立刻断开有丢掉它的风险
                    Handler(Looper.getMainLooper()).postDelayed({
                        connection.release()
                        finishOnce()
                    }, RELEASE_DELAY_MS)
                }
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({ finishOnce() }, TIMEOUT_MS)
    }

    companion object {
        const val ACTION_TOGGLE = "com.yinxia.music.widget.ACTION_TOGGLE"
        const val ACTION_NEXT = "com.yinxia.music.widget.ACTION_NEXT"
        const val ACTION_PREV = "com.yinxia.music.widget.ACTION_PREV"
        // 恢复播放队列时要在广播窗口里扫一遍媒体库（大库可能几百毫秒到几秒），
        // 5 秒太紧：超时会取消协程、把命令吞掉，表现成"进程被杀后第一次点按钮没反应"。
        // goAsync 给的上限约 10 秒，这里取 9 秒。
        private const val TIMEOUT_MS = 9000L
        private const val RELEASE_DELAY_MS = 600L
    }
}
