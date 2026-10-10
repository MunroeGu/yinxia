package com.yinxia.music.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yinxia.music.R
import com.yinxia.music.data.ArtworkLoader
import com.yinxia.music.data.Song
import com.yinxia.music.ui.theme.gradientFor

/**
 * 封面。尺寸完全交给调用方（modifier），这样列表、迷你条、播放页能共用一套逻辑。
 * 封面取不到时用与歌曲 id 绑定的渐变色 + 音符图标兜底，列表不会出现空洞。
 */
@Composable
fun Artwork(
    song: Song?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
) {
    val context = LocalContext.current
    var image: ImageBitmap? by remember(song?.id) {
        mutableStateOf(ArtworkLoader.cached(song?.id ?: -1L))
    }

    LaunchedEffect(song?.id) {
        if (song != null) {
            image = ArtworkLoader.load(context, song)
        }
    }

    // 占位渐变只跟歌曲有关，不随重组变。以前每次重组都要新建一个 Pair
    // （gradientFor）+ 一个 List + 一个 Brush；列表滚动时每行每帧都会走到这里，
    // 纯属给 GC 添活。remember 住它，滚动的绘制路径上就没有这次分配了。
    val gradientBrush = remember(song?.id) {
        val gradient = gradientFor(song?.id ?: 0L)
        Brush.linearGradient(listOf(gradient.first, gradient.second))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(gradientBrush),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 没有封面就显示统一的默认封面（用户提供的那张插画）。
            // 以前是用与歌曲 id 绑定的渐变色 + 音符兜底，看起来比较"空"。
            Image(
                painter = painterResource(R.drawable.cover_placeholder),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
