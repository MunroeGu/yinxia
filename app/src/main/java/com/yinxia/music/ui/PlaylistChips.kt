package com.yinxia.music.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yinxia.music.R
import com.yinxia.music.data.Playlist

/**
 * 音乐库上方的歌单条：横向滚动的一排 chip。
 *
 * 第一个是「全部」（activeId == null），中间每个歌单一个，最后一个「新建歌单」。
 *
 * 长按实现说明：FilterChip 的 onClick 是必填参数，没法置空，所以这里把
 * [Modifier.combinedClickable] 直接挂在 chip 的 modifier 上，让同一个手势区域
 * 既处理点击（切歌单）又处理长按（弹出重命名/删除）。点击时 chip 自身的 onClick
 * 和外层 combinedClickable 可能都会触发，但 `onSelect(id)` 只是幂等地设置
 * activePlaylistId，重复调用没有任何副作用，所以不额外包一层 Box。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistChips(
    playlists: List<Playlist>,
    activeId: Long?,
    onSelect: (Long?) -> Unit,
    onLongPress: (Playlist) -> Unit,
    onCreateNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = activeId == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.playlist_all)) },
            )
        }

        items(items = playlists, key = { it.id }) { playlist ->
            FilterChip(
                selected = activeId == playlist.id,
                onClick = { onSelect(playlist.id) },
                label = {
                    Text(
                        text = playlist.name + " · " +
                            stringResource(R.string.songs_count, playlist.songIds.size),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.combinedClickable(
                    onClick = { onSelect(playlist.id) },
                    onLongClick = { onLongPress(playlist) },
                ),
            )
        }

        item {
            AssistChip(
                onClick = onCreateNew,
                label = { Text(stringResource(R.string.playlist_new)) },
            )
        }
    }
}
