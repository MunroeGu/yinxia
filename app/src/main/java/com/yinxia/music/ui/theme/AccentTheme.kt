package com.yinxia.music.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * 把"种子色"调整成当前明暗主题下能当强调色用的颜色。
 *
 * 封面里挑出来的颜色和用户自选的颜色都走这里：
 *  - 饱和度抬到至少 0.45：否则灰扑扑的没有颜色感；
 *  - 亮度按主题夹进区间：深色主题要够亮（否则看不见），浅色主题要够深（否则发白看不清）。
 *
 * 所以调色板里存的是种子色，而不是最终颜色 —— 同一个种子在两种主题下都能用。
 */
fun accentForTheme(seed: Color, darkTheme: Boolean): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(seed.toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.45f)
    hsv[2] = if (darkTheme) {
        hsv[2].coerceIn(0.72f, 0.92f)
    } else {
        hsv[2].coerceIn(0.40f, 0.55f)
    }
    return Color(AndroidColor.HSVToColor(hsv))
}

/** 压在这个颜色上的文字/图标该用白还是黑。 */
fun onAccentColor(color: Color): Color =
    if (color.luminance() < 0.5f) Color.White else Color.Black

/**
 * 用强调色覆盖 Material 主题的主色。
 *
 * 刻意只改 primary / onPrimary / primaryContainer / onPrimaryContainer 四个：
 * 界面里所有"强调"的地方（播放按钮、进度条、选中行、图标）会自动跟着变，
 * 而背景、正文颜色仍走原来的配色 —— 整个界面不会变成一锅花里胡哨的颜色。
 *
 * [accent] 为 null 时（没在播放、封面提取失败、用户没自定义默认色）保持原样。
 */
@Composable
fun AccentTheme(
    accent: Color?,
    content: @Composable () -> Unit,
) {
    if (accent == null) {
        content()
        return
    }

    val base = MaterialTheme.colorScheme
    val container = lerp(base.surface, accent, 0.28f)

    MaterialTheme(
        colorScheme = base.copy(
            primary = accent,
            onPrimary = onAccentColor(accent),
            primaryContainer = container,
            onPrimaryContainer = onAccentColor(container),
        ),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
