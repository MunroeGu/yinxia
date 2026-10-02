package com.yinxia.music.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.yinxia.music.data.MusicRepository
import com.yinxia.music.data.Song
import com.yinxia.music.player.PlaybackConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val NO_SONG_ID = -1L

data class LibraryUiState(
    val songs: List<Song> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
)

data class PlaybackUiState(
    val currentSongId: Long = NO_SONG_ID,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasQueue: Boolean = false,
) {
    val progress: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * 界面状态持有者。
 *
 * 播放本身活在 PlaybackService 里，这里只保存"界面要显示什么"，
 * 所以旋转屏幕、退到后台再回来都不会中断播放。
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val connection = PlaybackConnection(application)

    // 自带作用域，不依赖 lifecycle-viewmodel-ktx，onCleared 里统一取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _library = MutableStateFlow(LibraryUiState())
    val library: StateFlow<LibraryUiState> = _library.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    private var progressJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlayback()
            if (isPlaying) startProgressUpdates() else stopProgressUpdates()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = syncPlayback()

        override fun onPlaybackStateChanged(playbackState: Int) = syncPlayback()

        override fun onRepeatModeChanged(repeatMode: Int) = syncPlayback()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = syncPlayback()
    }

    fun connectPlayer() {
        connection.connect { controller ->
            controller.addListener(playerListener)
            syncPlayback()
            if (controller.isPlaying) startProgressUpdates()
        }
    }

    fun loadLibrary() {
        scope.launch {
            _library.update { it.copy(loading = true) }
            val songs = repository.loadSongs()
            _library.update { it.copy(songs = songs, loading = false) }
        }
    }

    fun setQuery(query: String) {
        _library.update { it.copy(query = query) }
    }

    /** 点列表里的一首歌：整个列表作为播放队列，从这首开始。 */
    fun playSong(song: Song) {
        val controller = connection.controller ?: return
        val queue = _library.value.songs
        val startIndex = queue.indexOfFirst { it.id == song.id }
        if (startIndex < 0) return

        controller.setMediaItems(queue.map { it.toMediaItem() }, startIndex, 0L)
        controller.prepare()
        controller.play()
    }

    fun togglePlayPause() {
        val controller = connection.controller ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
            controller.play()
        }
    }

    fun next() {
        connection.controller?.seekToNextMediaItem()
    }

    fun previous() {
        connection.controller?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        connection.controller?.seekTo(positionMs)
    }

    fun toggleShuffle() {
        val controller = connection.controller ?: return
        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
    }

    /** 关 -> 列表循环 -> 单曲循环 -> 关 */
    fun cycleRepeat() {
        val controller = connection.controller ?: return
        controller.repeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /**
     * 播放进度靠轮询而不是靠插值：切歌、seek、暂停这些事件都会改变位置，
     * 每 500ms 同步一次的代价很小，但状态永远和播放器一致。
     */
    private fun startProgressUpdates() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                syncPlayback()
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
        syncPlayback()
    }

    private fun syncPlayback() {
        val controller = connection.controller ?: return

        val currentSongId = controller.currentMediaItem?.mediaId?.toLongOrNull() ?: NO_SONG_ID
        val playerDuration = controller.duration
        // 播放器还没解析出时长时，用扫描时拿到的时长兜底，进度条不会突然跳成 0
        val fallbackDuration = _library.value.songs
            .firstOrNull { it.id == currentSongId }
            ?.durationMs
            ?: 0L

        _playback.update {
            it.copy(
                currentSongId = currentSongId,
                isPlaying = controller.isPlaying,
                positionMs = controller.currentPosition.coerceAtLeast(0L),
                durationMs = if (playerDuration > 0L) playerDuration else fallbackDuration,
                shuffleEnabled = controller.shuffleModeEnabled,
                repeatMode = controller.repeatMode,
                hasQueue = controller.mediaItemCount > 0,
            )
        }
    }

    private fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    override fun onCleared() {
        connection.controller?.removeListener(playerListener)
        scope.cancel()
        connection.release()
        super.onCleared()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
