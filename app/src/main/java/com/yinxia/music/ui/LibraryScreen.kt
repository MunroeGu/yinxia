package com.yinxia.music.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yinxia.music.R
import com.yinxia.music.data.Song
import com.yinxia.music.util.formatDuration

/**
 * 音乐库列表。三种形态叠在一起：
 *  - 普通：点一下播放，长按进入多选
 *  - 多选：每行前面出现勾选框
 *  - 手动排序：每行右侧出现上移/下移按钮
 */
@Composable
fun LibraryScreen(
    songs: List<Song>,
    currentSongId: Long,
    isPlaying: Boolean,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    manualSorting: Boolean,
    onSongClick: (Song) -> Unit,
    onSongLongClick: (Song) -> Unit,
    onToggleSelection: (Song) -> Unit,
    onMoveUp: (Song) -> Unit,
    onMoveDown: (Song) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        itemsIndexed(items = songs, key = { _, song -> song.id }) { index, song ->
            SongRow(
                song = song,
                isCurrent = song.id == currentSongId,
                isPlaying = isPlaying && song.id == currentSongId,
                selectionMode = selectionMode,
                selected = song.id in selectedIds,
                manualSorting = manualSorting,
                canMoveUp = index > 0,
                canMoveDown = index < songs.lastIndex,
                onClick = { if (selectionMode) onToggleSelection(song) else onSongClick(song) },
                onLongClick = { onSongLongClick(song) },
                onMoveUp = { onMoveUp(song) },
                onMoveDown = { onMoveDown(song) },
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
    manualSorting: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
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
            .background(rowColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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

        Spacer(Modifier.width(8.dp))

        when {
            manualSorting -> {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_up),
                        contentDescription = stringResource(R.string.action_move_up),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_down),
                        contentDescription = stringResource(R.string.action_move_down),
                    )
                }
            }

            isPlaying -> Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = stringResource(R.string.now_playing),
                tint = accent,
                modifier = Modifier.size(18.dp),
            )

            else -> Text(
                text = formatDuration(song.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
