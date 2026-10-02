package com.yinxia.music.ui

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
import kotlinx.coroutines.launch

/**
 * 应用主界面：权限门 -> 音乐库 -> 迷你播放条 -> 全屏播放页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YinxiaApp(
    viewModel: PlayerViewModel,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val library by viewModel.library.collectAsState()
    val playback by viewModel.playback.collectAsState()

    var searchVisible by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }

    // 启动就连播放服务；拿到权限后（或用户刚授权回来）扫描一次
    LaunchedEffect(Unit) { viewModel.connectPlayer() }
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) viewModel.loadLibrary()
    }

    val currentSong = library.songs.firstOrNull { it.id == playback.currentSongId }

    val query = library.query.trim()
    val visibleSongs = remember(library.songs, query) {
        if (query.isEmpty()) {
            library.songs
        } else {
            library.songs.filter { song ->
                song.title.contains(query, ignoreCase = true) ||
                    song.artist?.contains(query, ignoreCase = true) == true ||
                    song.album?.contains(query, ignoreCase = true) == true
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.library_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            if (permissionGranted && library.songs.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.songs_count, library.songs.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        if (permissionGranted && library.songs.isNotEmpty()) {
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

                library.songs.isEmpty() -> EmptyPage(onRefresh = viewModel::loadLibrary)

                visibleSongs.isEmpty() -> EmptyPage(
                    onRefresh = viewModel::loadLibrary,
                    isSearchResult = true,
                )

                else -> LibraryScreen(
                    songs = visibleSongs,
                    currentSongId = playback.currentSongId,
                    isPlaying = playback.isPlaying,
                    onSongClick = viewModel::playSong,
                    contentPadding = PaddingValues(bottom = 8.dp),
                )
            }
        }
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
    isSearchResult: Boolean = false,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.state_empty),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!isSearchResult) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.state_empty_hint),
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
