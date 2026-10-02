package com.yinxia.music.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
// scrollBy 不是 LazyListState 的成员方法，而是 foundation.gestures 里的扩展函数，必须显式 import
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yinxia.music.R
import com.yinxia.music.data.Song
import com.yinxia.music.util.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 音乐库列表。三种形态：
 *  - 普通：点一下播放，长按进入多选
 *  - 多选：每行前面出现勾选框
 *  - 手动排序（[sorting] = true）：长按整行上下拖动排序。此模式行内不放任何图标，
 *    歌曲信息不会被遮挡；排完点顶部「完成」退出，列表恢复成普通形态。
 *
 * 拖动实现上有三个坑，这里都处理了：
 *  1. 用长按触发（[detectDragGesturesAfterLongPress]）。普通拖动会和列表滚动抢手势。
 *  2. 判定"跨过相邻行"必须用**下标**而不是 key：换位后被拖项的下标就等于目标下标，
 *     天然排除了"同一帧里反复换位"；如果用 key 做去重，往回拖时会被误判成重复而卡死。
 *  3. 拖到边缘要自动滚动，否则几百首的列表里没法长距离调整。
 */
@Composable
fun LibraryScreen(
    songs: List<Song>,
    currentSongId: Long,
    isPlaying: Boolean,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    sorting: Boolean,
    onSongClick: (Song) -> Unit,
    onSongLongClick: (Song) -> Unit,
    onToggleSelection: (Song) -> Unit,
    onMoveSong: (draggedId: Long, targetId: Long) -> Unit,
    onDragFinished: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 被拖动的行：key 用来发起移动，index 用来做位置判定 —— 两者都要
    var draggingKey by remember { mutableStateOf<Long?>(null) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    // 拖动行相对它当前布局位置的偏移
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 拖到边缘时的滚动速度，0 表示不滚
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }

    val currentOnMove = rememberUpdatedState(onMoveSong)
    val currentOnDragFinished = rememberUpdatedState(onDragFinished)

    val density = LocalDensity.current
    val edgeTriggerPx = with(density) { 72.dp.toPx() }
    val autoScrollStepPx = with(density) { 10.dp.toPx() }

    fun resetDrag() {
        draggingKey = null
        draggingIndex = -1
        dragOffset = 0f
        autoScrollSpeed = 0f
    }

    /**
     * 判定被拖动的行是否跨过了相邻行，跨过就换位。
     *
     * 换位后必须同步修正 dragOffset，否则这一行的视觉位置会跳一下：
     * 换位前它的布局位置是 info.offset，换位后变成 target.offset，
     * 想让视觉位置不变，偏移就要补上两者的差。
     */
    fun evaluateCrossing() {
        val draggedKey = draggingKey ?: return
        if (draggingIndex < 0) return

        val layout = listState.layoutInfo
        val info = layout.visibleItemsInfo.firstOrNull { it.index == draggingIndex } ?: return

        val visualTop = info.offset + dragOffset
        val visualCenter = visualTop + info.size / 2f

        val target = layout.visibleItemsInfo.firstOrNull { candidate ->
            candidate.index != draggingIndex &&
                visualCenter.toInt() in candidate.offset until (candidate.offset + candidate.size)
        } ?: return

        val targetKey = target.key as? Long ?: return
        currentOnMove.value(draggedKey, targetKey)

        // 换位后被拖项就落在目标的槽位上
        dragOffset = visualTop - target.offset
        draggingIndex = target.index
    }

    // 拖到边缘时自动滚动：没有这个，长列表里只能在小范围内调整顺序
    LaunchedEffect(draggingKey) {
        if (draggingKey == null) return@LaunchedEffect
        while (isActive) {
            val delta = autoScrollSpeed
            if (delta != 0f) {
                listState.scrollBy(delta)
                // 列表滚了 delta，所有行的布局位置都后退 delta，
                // 偏移同步加回来，被拖的行才会继续贴着手指
                dragOffset += delta
                evaluateCrossing()
            }
            delay(16)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        itemsIndexed(items = songs, key = { _, song -> song.id }) { _, song ->
            val isDragging = sorting && draggingKey == song.id

            SongRow(
                song = song,
                isCurrent = song.id == currentSongId,
                isPlaying = isPlaying && song.id == currentSongId,
                selectionMode = selectionMode,
                selected = song.id in selectedIds,
                sorting = sorting,
                isDragging = isDragging,
                dragOffsetY = if (isDragging) dragOffset else 0f,
                onClick = { if (selectionMode) onToggleSelection(song) else onSongClick(song) },
                onLongClick = { onSongLongClick(song) },
                dragModifier = if (sorting) {
                    Modifier.pointerInput(song.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                // 起点下标从当前布局里取，避免用到过期下标
                                draggingIndex = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == song.id }
                                    ?.index
                                    ?: -1
                                draggingKey = song.id
                                dragOffset = 0f
                                autoScrollSpeed = 0f
                            },
                            onDragEnd = {
                                resetDrag()
                                currentOnDragFinished.value()
                            },
                            onDragCancel = {
                                resetDrag()
                                currentOnDragFinished.value()
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y

                                val layout = listState.layoutInfo
                                val info = layout.visibleItemsInfo
                                    .firstOrNull { it.index == draggingIndex }
                                if (info != null) {
                                    val top = info.offset + dragOffset
                                    val bottom = top + info.size
                                    autoScrollSpeed = when {
                                        top < layout.viewportStartOffset + edgeTriggerPx ->
                                            -autoScrollStepPx

                                        bottom > layout.viewportEndOffset - edgeTriggerPx ->
                                            autoScrollStepPx

                                        else -> 0f
                                    }
                                }

                                evaluateCrossing()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    sorting: Boolean,
    isDragging: Boolean,
    dragOffsetY: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    dragModifier: Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val rowColor = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer { translationY = dragOffsetY }
            // 被拖起来的行给个底色和投影，明确"它现在跟着手指"
            .then(
                if (isDragging) {
                    Modifier.shadow(elevation = 8.dp, clip = false)
                } else {
                    Modifier
                },
            )
            .background(
                if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else rowColor,
            )
            // 排序模式下禁用点击：长按要用来拖动，避免误触播放
            .combinedClickable(
                enabled = !sorting,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .then(dragModifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(4.dp))
        }

        Artwork(song = song, modifier = Modifier.size(48.dp), cornerRadius = 10.dp)
        Spacer(Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.subtitle(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 排序模式下这里什么都不放：不放箭头，也不放拖拽把手，歌曲信息占满可用宽度
        if (!sorting) {
            Spacer(Modifier.width(12.dp))
            if (isPlaying) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = stringResource(R.string.now_playing),
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    text = formatDuration(song.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "歌手 · 专辑"，缺字段时自动省略。 */
@Composable
private fun Song.subtitle(): String {
    val unknown = stringResource(R.string.unknown_artist)
    return buildString {
        append(artist ?: unknown)
        album?.takeIf { it.isNotBlank() }?.let {
            append(" · ")
            append(it)
        }
    }
}
