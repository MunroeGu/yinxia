package com.yinxia.music.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yinxia.music.R
import com.yinxia.music.data.SortMode
import kotlinx.coroutines.launch

/**
 * 应用主界面：权限门 -> 音乐库 -> 迷你播放条 -> 全屏播放页，
 * 外加排序面板、扫描范围面板、多选删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YinxiaApp(
    viewModel: PlayerViewModel,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDeleteRequest: (List<Uri>) -> Unit,
) {
    val library by viewModel.library.collectAsState()
    val playback by viewModel.playback.collectAsState()
    val pendingDelete by viewModel.pendingDelete.collectAsState()

    var searchVisible by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showFolderSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 启动就连播放服务；拿到权限后（或用户刚授权回来）扫描一次
    LaunchedEffect(Unit) { viewModel.connectPlayer() }
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) viewModel.loadLibrary()
    }

    // 删除要交给 Activity 发起：Android 11+ 必须弹系统确认框，UI 层拿不到那个能力
    LaunchedEffect(pendingDelete) {
        if (pendingDelete.isNotEmpty()) {
            onDeleteRequest(pendingDelete.map { it.uri })
            viewModel.consumeDeleteRequest()
        }
    }

    // 正在播放的歌可能被扫描范围过滤掉了，所以从全量列表里找
    val currentSong = library.allSongs.firstOrNull { it.id == playback.currentSongId }
    val manualSorting = library.sortMode == SortMode.MANUAL

    Scaffold(
        topBar = {
            Column {
                if (library.selectionMode) {
                    TopAppBar(
                        title = {
                            Text(
                                text = stringResource(
                                    R.string.selected_count,
                                    library.selectedSongIds.size,
                                ),
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = viewModel::clearSelection) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = stringResource(R.string.action_cancel),
                                )
                            }
                        },
                        actions = {
                            TextButton(onClick = viewModel::selectAllVisible) {
                                Text(stringResource(R.string.action_select_all))
                            }
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = stringResource(R.string.action_delete),
                                )
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = stringResource(R.string.library_title),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                if (permissionGranted && library.songs.isNotEmpty()) {
                                    Text(
                                        text = stringResource(
                                            R.string.songs_count,
                                            library.songs.size,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        actions = {
                            if (permissionGranted && library.allSongs.isNotEmpty()) {
                                IconButton(onClick = { showSortSheet = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_sort),
                                        contentDescription = stringResource(R.string.action_sort),
                                    )
                                }
                                IconButton(onClick = { showFolderSheet = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_folder),
                                        contentDescription = stringResource(R.string.action_scan_range),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        searchVisible = !searchVisible
                                        if (!searchVisible) viewModel.setQuery("")
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (searchVisible) R.drawable.ic_close else R.drawable.ic_search,
                                        ),
                                        contentDescription = stringResource(R.string.search_hint),
                                    )
                                }
                            }
                        },
                    )

                    if (permissionGranted && manualSorting) {
                        Text(
                            text = stringResource(R.string.manual_sort_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }

                    if (permissionGranted && searchVisible) {
                        OutlinedTextField(
                            value = library.query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (currentSong != null) {
                MiniPlayer(
                    song = currentSong,
                    isPlaying = playback.isPlaying,
                    progress = playback.progress,
                    onExpand = { showNowPlaying = true },
                    onTogglePlay = viewModel::togglePlayPause,
                    onNext = viewModel::next,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                !permissionGranted -> PermissionPage(
                    onGrant = onRequestPermission,
                    onOpenSettings = onOpenAppSettings,
                )

                library.loading -> LoadingPage()

                library.allSongs.isEmpty() -> EmptyPage(onRefresh = viewModel::loadLibrary)

                library.songs.isEmpty() && library.folderFilterEnabled -> EmptyPage(
                    onRefresh = viewModel::loadLibrary,
                    title = stringResource(R.string.state_no_folder),
                    hint = stringResource(R.string.state_no_folder_hint),
                )

                library.songs.isEmpty() -> EmptyPage(
                    onRefresh = viewModel::loadLibrary,
                    title = stringResource(R.string.state_no_result),
                    hint = null,
                )

                else -> LibraryScreen(
                    songs = library.songs,
                    currentSongId = playback.currentSongId,
                    isPlaying = playback.isPlaying,
                    selectionMode = library.selectionMode,
                    selectedIds = library.selectedSongIds,
                    manualSorting = manualSorting,
                    onSongClick = viewModel::playSong,
                    onSongLongClick = { song ->
                        if (library.selectionMode) {
                            viewModel.toggleSelection(song.id)
                        } else {
                            viewModel.startSelection(song.id)
                        }
                    },
                    onToggleSelection = { song -> viewModel.toggleSelection(song.id) },
                    onMoveUp = { song -> viewModel.moveSong(song.id, -1) },
                    onMoveDown = { song -> viewModel.moveSong(song.id, 1) },
                    contentPadding = PaddingValues(bottom = 8.dp),
                )
            }
        }
    }

    if (showSortSheet) {
        SortSheet(
            sortMode = library.sortMode,
            sortAscending = library.sortAscending,
            onSelect = { mode, ascending ->
                viewModel.setSortMode(mode, ascending)
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false },
        )
    }

    if (showFolderSheet) {
        FolderSheet(
            folders = library.folders,
            filterEnabled = library.folderFilterEnabled,
            selectedFolders = library.selectedFolders,
            onToggle = viewModel::setFolderSelected,
            onScanAll = viewModel::scanAllFolders,
            onDismiss = { showFolderSheet = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = {
                Text(
                    stringResource(R.string.delete_message, library.selectedSongIds.size),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.requestDeleteSelection()
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showNowPlaying && currentSong != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        // 先播收起动画，动画结束再真正移出组合，避免"啪"地一下消失
        val closeSheet: () -> Unit = {
            scope.launch { sheetState.hide() }.invokeOnCompletion { showNowPlaying = false }
        }

        ModalBottomSheet(
            onDismissRequest = { showNowPlaying = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            NowPlayingContent(
                song = currentSong,
                isPlaying = playback.isPlaying,
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                shuffleEnabled = playback.shuffleEnabled,
                repeatMode = playback.repeatMode,
                onCollapse = closeSheet,
                onTogglePlay = viewModel::togglePlayPause,
                onNext = viewModel::next,
                onPrevious = viewModel::previous,
                onSeek = viewModel::seekTo,
                onToggleShuffle = viewModel::toggleShuffle,
                onCycleRepeat = viewModel::cycleRepeat,
            )
        }
    }
}

@Composable
private fun PermissionPage(
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.permission_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onGrant) {
            Text(stringResource(R.string.permission_grant))
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.permission_settings))
        }
    }
}

@Composable
private fun LoadingPage() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.state_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyPage(
    onRefresh: () -> Unit,
    title: String = stringResource(R.string.state_empty),
    hint: String? = stringResource(R.string.state_empty_hint),
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}
