package com.yinxia.music.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.yinxia.music.R
import com.yinxia.music.data.FolderEntry
import com.yinxia.music.data.LibraryPreferences
import com.yinxia.music.data.MusicRepository
import com.yinxia.music.data.Playlist
import com.yinxia.music.data.Song
import com.yinxia.music.data.SortMode
import com.yinxia.music.player.PlaybackConnection
import com.yinxia.music.player.toMediaItem
import com.yinxia.music.widget.PlayerWidget
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
import kotlinx.coroutines.withContext

const val NO_SONG_ID = -1L

data class LibraryUiState(
    /** 过滤 + 排序后的结果，界面直接显示它，同时它就是播放队列 */
    val songs: List<Song> = emptyList(),
    /** 扫描到的全部歌曲，「全选」和文件夹汇总要用 */
    val allSongs: List<Song> = emptyList(),
    val folders: List<FolderEntry> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val sortMode: SortMode = SortMode.TITLE,
    val sortAscending: Boolean = true,
    /**
     * 是否处在「手动排序」的拖动状态。
     * 退出后手动顺序依然生效，只是列表恢复成普通形态，不再显示任何拖拽相关的东西。
     */
    val manualEditing: Boolean = false,
    /** 用户自定义的默认主题色（ARGB）；null = 用主题自带颜色 */
    val defaultAccentArgb: Int? = null,
    /** 是否在列表里显示码率/采样率这类详细信息 */
    val showSongDetails: Boolean = false,
    /** 自建歌单 */
    val playlists: List<Playlist> = emptyList(),
    /** 当前只看哪个歌单；null = 看全部 */
    val activePlaylistId: Long? = null,
    /** false = 扫描全部文件夹；此时界面上的勾选一律显示为全选 */
    val folderFilterEnabled: Boolean = false,
    val selectedFolders: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val selectedSongIds: Set<Long> = emptySet(),
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
    private val preferences = LibraryPreferences(application)
    private val connection = PlaybackConnection(application)

    // 自带作用域，不依赖 lifecycle-viewmodel-ktx，onCleared 里统一取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 手动排序顺序，常驻内存免得每次重排都读一遍 SharedPreferences */
    private var manualOrder: MutableList<Long> = preferences.manualOrder.toMutableList()

    /** 等待系统删除确认的歌曲，删除结果回来后要用 */
    private var pendingDeleteSongs: List<Song> = emptyList()

    private val _library = MutableStateFlow(
        LibraryUiState(
            sortMode = preferences.sortMode,
            sortAscending = preferences.sortAscending,
            folderFilterEnabled = preferences.folderFilterEnabled,
            selectedFolders = preferences.selectedFolders,
            defaultAccentArgb = preferences.defaultAccentArgb,
            showSongDetails = preferences.showSongDetails,
            playlists = preferences.playlists,
        ),
    )
    val library: StateFlow<LibraryUiState> = _library.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    /** 需要交给系统弹确认框的删除请求 */
    private val _pendingDelete = MutableStateFlow<List<Song>>(emptyList())
    val pendingDelete: StateFlow<List<Song>> = _pendingDelete.asStateFlow()

    private var progressJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlayback()
            if (isPlaying) startProgressUpdates() else stopProgressUpdates()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncPlayback()
            persistQueueIndex()
        }

        override fun onPlaybackStateChanged(playbackState: Int) = syncPlayback()

        override fun onRepeatModeChanged(repeatMode: Int) = syncPlayback()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = syncPlayback()
    }

    // ---------------------------------------------------------------- 播放

    fun connectPlayer() {
        connection.connect { controller ->
            controller.addListener(playerListener)
            syncPlayback()
            if (controller.isPlaying) startProgressUpdates()
        }
    }

    /** 点列表里的一首歌：当前显示的整个列表作为播放队列 */
    fun playSong(song: Song) {
        val controller = connection.controller ?: return
        val queue = _library.value.songs
        val startIndex = queue.indexOfFirst { it.id == song.id }
        if (startIndex < 0) return

        controller.setMediaItems(queue.map { it.toMediaItem() }, startIndex, 0L)
        // 把队列存下来：系统杀掉进程后，桌面插件要靠它把队列恢复回来
        preferences.queueSongIds = queue.map { it.id }
        preferences.queueIndex = startIndex
        controller.prepare()
        controller.play()
    }

    /**
     * 列表里点一首歌：
     *  - 点的就是当前这首 → 暂停 / 继续（不会从头开始，位置保留）
     *  - 点的是别的歌 → 从这首重新开始播
     */
    fun onSongSelected(song: Song) {
        if (song.id == _playback.value.currentSongId) {
            togglePlayPause()
        } else {
            playSong(song)
        }
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
        val song = _library.value.allSongs.firstOrNull { it.id == currentSongId }
        val playerDuration = controller.duration
        // 播放器还没解析出时长时，用扫描时拿到的时长兜底，进度条不会突然跳成 0
        val fallbackDuration = song?.durationMs ?: 0L

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

        // 桌面插件：内部会按"歌曲 + 播放状态"去重，
        // 所以每秒两次的进度刷新不会造成重复推送
        PlayerWidget.push(getApplication(), song, controller.isPlaying)
    }

    // ------------------------------------------------------------ 音乐库

    fun loadLibrary() {
        scope.launch {
            _library.update { it.copy(loading = true) }
            val songs = repository.loadSongs()
            syncManualOrder(songs)
            dropMissingSongsFromPlaylists(songs)
            _library.update {
                it.copy(
                    allSongs = songs,
                    folders = MusicRepository.buildFolders(songs),
                    loading = false,
                )
            }
            recompute()
        }
    }

    /**
     * 让手动顺序覆盖到刚扫到的歌曲：新歌按名称追加在末尾，已删除的 id 清掉。
     * 这样用户没手动排过的时候，手动模式也是一个合理的初始顺序。
     */
    private fun syncManualOrder(all: List<Song>) {
        val validIds = all.mapTo(HashSet()) { it.id }
        val removed = manualOrder.removeAll { it !in validIds }

        val known = manualOrder.toHashSet()
        val added = all.asSequence()
            .filter { it.id !in known }
            .sortedBy { it.title.lowercase() }
            .map { it.id }
            .toList()
        if (added.isNotEmpty()) manualOrder.addAll(added)

        if (removed || added.isNotEmpty()) preferences.manualOrder = manualOrder
    }

    /**
     * 把"当前队列 + 播到第几首"写进偏好设置。
     *
     * 每次切歌写一次，代价是可以接受的（一首歌才一次），换来的好处是
     * 进程被系统杀掉之后还能把队列恢复出来 —— 否则桌面插件的按钮会对一个空队列下命令。
     */
    private fun persistQueueIndex() {
        val controller = connection.controller ?: return
        if (controller.mediaItemCount == 0) return

        val ids = (0 until controller.mediaItemCount).mapNotNull { index ->
            controller.getMediaItemAt(index).mediaId.toLongOrNull()
        }
        if (ids.isEmpty()) return

        preferences.queueSongIds = ids
        preferences.queueIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
    }

    /**
     * 歌曲被删掉后，把歌单里失效的 id 清掉，避免歌单里挂着永远播不了的条目。
     */
    private fun dropMissingSongsFromPlaylists(all: List<Song>) {
        val validIds = all.mapTo(HashSet()) { it.id }
        var changed = false
        val cleaned = _library.value.playlists.map { playlist ->
            val kept = playlist.songIds.filter { it in validIds }
            if (kept.size != playlist.songIds.size) {
                changed = true
                playlist.copy(songIds = kept)
            } else {
                playlist
            }
        }
        if (!changed) return
        preferences.playlists = cleaned
        _library.update { it.copy(playlists = cleaned) }
    }

    /** 按 扫描范围 -> 搜索词 -> 排序 重新算出展示列表 */
    private fun recompute() {
        val state = _library.value
        val query = state.query.trim()

        // 只看某个歌单时，按歌单里的 id 过滤；歌单里已经不存在的歌（被删了/不在扫描范围）自动忽略
        val playlistSongIds = state.activePlaylistId
            ?.let { active -> state.playlists.firstOrNull { it.id == active }?.songIds }
            ?.toHashSet()

        val filtered = state.allSongs.filter { song ->
            val folderOk = !state.folderFilterEnabled || song.folderKey in state.selectedFolders
            val playlistOk = playlistSongIds == null || song.id in playlistSongIds
            val queryOk = query.isEmpty() ||
                song.title.contains(query, ignoreCase = true) ||
                song.artist?.contains(query, ignoreCase = true) == true ||
                song.album?.contains(query, ignoreCase = true) == true
            folderOk && playlistOk && queryOk
        }

        val ordered = when (state.sortMode) {
            SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }

            SortMode.DATE_ADDED -> filtered.sortedBy { it.dateAddedMs }

            SortMode.MANUAL -> {
                val orderIndex = manualOrder.withIndex().associate { (index, id) -> id to index }
                filtered.sortedWith(
                    compareBy<Song>({ orderIndex[it.id] ?: Int.MAX_VALUE }, { it.title.lowercase() }),
                )
            }
        }

        // 手动顺序自带方向，不再额外反转
        val displayed = if (state.sortMode == SortMode.MANUAL || state.sortAscending) {
            ordered
        } else {
            ordered.reversed()
        }

        _library.update { it.copy(songs = displayed) }
    }

    fun setQuery(query: String) {
        _library.update { it.copy(query = query) }
        recompute()
    }

    /** 设置默认主题色（只在歌曲没有封面时生效） */
    fun setDefaultAccent(argb: Int?) {
        preferences.defaultAccentArgb = argb
        _library.update { it.copy(defaultAccentArgb = argb) }
    }

    /** 显示/隐藏歌曲详细信息（码率、采样率等） */
    fun setShowSongDetails(show: Boolean) {
        preferences.showSongDetails = show
        _library.update { it.copy(showSongDetails = show) }
    }

    /** 只看某个歌单；传 null 表示看全部 */
    fun setActivePlaylist(playlistId: Long?) {
        _library.update { it.copy(activePlaylistId = playlistId) }
        recompute()
    }

    /**
     * 用当前选中的歌曲新建歌单（[songIds] 为空时只建一个空歌单）。
     * 返回新歌单的 id。名称会去掉首尾空格，空名回退成"新建歌单"。
     */
    fun createPlaylist(name: String, songIds: List<Long>): Long {
        val cleanName = name.trim().ifEmpty { "新建歌单" }
        val playlists = _library.value.playlists.toMutableList()
        // 用当前最大 id + 1，避免和已存在的撞号
        val newId = (playlists.maxOfOrNull { it.id } ?: 0L) + 1L
        playlists += Playlist(id = newId, name = cleanName, songIds = songIds.distinct())
        applyPlaylists(playlists)
        return newId
    }

    /** 把歌曲加入歌单（已经在里面的不重复加） */
    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        val playlists = _library.value.playlists.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(songIds = (playlist.songIds + songIds).distinct())
            } else {
                playlist
            }
        }
        applyPlaylists(playlists)
    }

    /** 把歌曲从歌单里移除 */
    fun removeSongsFromPlaylist(playlistId: Long, songIds: List<Long>) {
        val removing = songIds.toHashSet()
        val playlists = _library.value.playlists.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(songIds = playlist.songIds.filterNot { it in removing })
            } else {
                playlist
            }
        }
        applyPlaylists(playlists)
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        val cleanName = name.trim().ifEmpty { return }
        val playlists = _library.value.playlists.map { playlist ->
            if (playlist.id == playlistId) playlist.copy(name = cleanName) else playlist
        }
        applyPlaylists(playlists)
    }

    fun deletePlaylist(playlistId: Long) {
        val playlists = _library.value.playlists.filterNot { it.id == playlistId }
        // 正在看这个歌单时，删掉后自动退回"全部"
        val stillActive = _library.value.activePlaylistId == playlistId
        if (stillActive) {
            preferences.playlists = playlists
            _library.update { it.copy(playlists = playlists, activePlaylistId = null) }
            recompute()
            return
        }
        applyPlaylists(playlists)
    }

    /** 歌单改动统一走这里：落盘 + 更新状态 + 重算列表 */
    private fun applyPlaylists(playlists: List<Playlist>) {
        preferences.playlists = playlists
        _library.update { it.copy(playlists = playlists) }
        recompute()
    }

    fun setSortMode(mode: SortMode, ascending: Boolean) {
        preferences.sortMode = mode
        preferences.sortAscending = ascending
        _library.update {
            it.copy(sortMode = mode, sortAscending = ascending, manualEditing = false)
        }
        recompute()
    }

    /** 进入手动排序：列表变成可长按拖动；同时退出多选，避免两种模式打架 */
    fun startManualSort() {
        preferences.sortMode = SortMode.MANUAL
        _library.update {
            it.copy(
                sortMode = SortMode.MANUAL,
                manualEditing = true,
                selectionMode = false,
                selectedSongIds = emptySet(),
            )
        }
        recompute()
    }

    /** 结束手动排序：顺序保留，退出拖动状态 */
    fun finishManualSort() {
        commitManualOrder()
        _library.update { it.copy(manualEditing = false) }
    }

    /**
     * 拖动排序：把 [draggedId] 插到 [targetId] 当前所在的位置。
     *
     * 拖动时每跨过一行就会调用一次，所以这里刻意不做整表重算、也不写磁盘，
     * 只改内存里的展示列表和手动顺序，松手时再由 [commitManualOrder] 落盘。
     */
    fun moveSongTo(draggedId: Long, targetId: Long) {
        val displayed = _library.value.songs.toMutableList()
        val from = displayed.indexOfFirst { it.id == draggedId }
        val to = displayed.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || from == to) return

        displayed.add(to, displayed.removeAt(from))

        // 用"当前显示顺序 + 没显示出来的歌曲原顺序"重建整份手动顺序，
        // 这样即使开着搜索或只扫描了部分文件夹，也不会把没显示的歌曲搞乱
        val displayedIds = displayed.mapTo(HashSet()) { it.id }
        manualOrder = (
            displayed.map { it.id } + manualOrder.filter { it !in displayedIds }
            ).toMutableList()

        _library.update { it.copy(sortMode = SortMode.MANUAL, songs = displayed) }
    }

    /** 把当前手动顺序写进 SharedPreferences */
    fun commitManualOrder() {
        preferences.manualOrder = manualOrder
        preferences.sortMode = SortMode.MANUAL
    }

    // -------------------------------------------------------- 扫描范围

    fun setFolderSelected(folderKey: String, selected: Boolean) {
        val allKeys = _library.value.folders.mapTo(HashSet()) { it.key }
        if (allKeys.isEmpty()) return

        val current = if (_library.value.folderFilterEnabled) {
            _library.value.selectedFolders.toMutableSet()
        } else {
            allKeys.toMutableSet()
        }
        if (selected) current += folderKey else current -= folderKey

        // 全部勾选等价于不限制，直接关掉过滤，避免存一份没意义的全集
        val scanningAll = current.size == allKeys.size && current.containsAll(allKeys)

        preferences.folderFilterEnabled = !scanningAll
        preferences.selectedFolders = current
        _library.update {
            it.copy(folderFilterEnabled = !scanningAll, selectedFolders = current)
        }
        recompute()
    }

    fun scanAllFolders() {
        preferences.folderFilterEnabled = false
        preferences.selectedFolders = emptySet()
        _library.update { it.copy(folderFilterEnabled = false, selectedFolders = emptySet()) }
        recompute()
    }

    // ------------------------------------------------------------ 多选

    fun startSelection(songId: Long) {
        _library.update { it.copy(selectionMode = true, selectedSongIds = setOf(songId)) }
    }

    fun toggleSelection(songId: Long) {
        val current = _library.value.selectedSongIds
        val next = if (songId in current) current - songId else current + songId
        _library.update { it.copy(selectionMode = next.isNotEmpty(), selectedSongIds = next) }
    }

    fun selectAllVisible() {
        _library.update { it.copy(selectedSongIds = it.songs.mapTo(HashSet()) { song -> song.id }) }
    }

    fun clearSelection() {
        _library.update { it.copy(selectionMode = false, selectedSongIds = emptySet()) }
    }

    // ------------------------------------------------------------ 删除

    fun requestDeleteSelection() {
        val selected = _library.value.allSongs.filter { it.id in _library.value.selectedSongIds }
        if (selected.isEmpty()) return
        pendingDeleteSongs = selected
        _pendingDelete.value = selected
    }

    /** 界面已经拿去发起删除请求了，清掉状态避免重复触发；结果由 onDeleteResult 收尾 */
    fun consumeDeleteRequest() {
        _pendingDelete.value = emptyList()
    }

    /** 系统确认框的结果（或低版本直接删除的结果） */
    fun onDeleteResult(success: Boolean) {
        val deleted = pendingDeleteSongs
        pendingDeleteSongs = emptyList()
        _pendingDelete.value = emptyList()
        if (!success || deleted.isEmpty()) return

        removeDeletedFromQueue(deleted.mapTo(HashSet()) { it.id })
        clearSelection()
        loadLibrary()
    }

    /**
     * Android 10 及以下没有 createDeleteRequest，只能直接删：
     * 9 及以下需要 WRITE_EXTERNAL_STORAGE；10 可能抛 RecoverableSecurityException，这里按失败处理。
     */
    fun deleteDirectly(uris: List<Uri>) {
        scope.launch {
            val anyDeleted = withContext(Dispatchers.IO) {
                var any = false
                uris.forEach { uri ->
                    try {
                        if (getApplication<Application>().contentResolver.delete(uri, null, null) > 0) {
                            any = true
                        }
                    } catch (_: Throwable) {
                        // 权限不足或文件被占用；下面统一报失败
                    }
                }
                any
            }
            if (!anyDeleted) {
                Toast.makeText(
                    getApplication<Application>(),
                    R.string.delete_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            onDeleteResult(anyDeleted)
        }
    }

    private fun removeDeletedFromQueue(deletedIds: Set<Long>) {
        val controller = connection.controller ?: return
        // 倒着删，避免索引位移
        for (index in controller.mediaItemCount - 1 downTo 0) {
            val id = controller.getMediaItemAt(index).mediaId.toLongOrNull()
            if (id != null && id in deletedIds) controller.removeMediaItem(index)
        }
    }

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
