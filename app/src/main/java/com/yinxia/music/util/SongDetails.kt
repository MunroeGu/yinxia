package com.yinxia.music.util

import com.yinxia.music.data.Song
import java.util.Locale

/**
 * 把歌曲的技术信息拼成一行，例如 "FLAC · 320 kbps · 44.1 kHz · 8.4 MB"。
 *
 * 拿不到的字段直接跳过（码率要 Android 11+，采样率要 Android 16+），
 * 一个都拿不到时返回 null，界面就不显示这一行。
 */
fun songDetailText(song: Song): String? {
    val parts = mutableListOf<String>()

    formatFromMime(song.mimeType)?.let { parts += it }

    song.bitrate?.takeIf { it > 0 }?.let { bps ->
        parts += "${bps / 1000} kbps"
    }

    song.sampleRate?.takeIf { it > 0 }?.let { hz ->
        parts += String.format(Locale.US, "%.1f kHz", hz / 1000f)
    }

    if (song.sizeBytes > 0) {
        parts += String.format(Locale.US, "%.1f MB", song.sizeBytes / 1024f / 1024f)
    }

    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** "audio/flac" -> "FLAC"；拿不到就返回 null */
private fun formatFromMime(mime: String?): String? {
    val subtype = mime?.substringAfter('/', "")?.takeIf { it.isNotBlank() } ?: return null
    val cleaned = when (subtype.lowercase(Locale.US)) {
        "mpeg", "mp3" -> "MP3"
        "mp4", "m4a", "aac" -> "AAC"
        "x-flac", "flac" -> "FLAC"
        "x-wav", "wav", "wave" -> "WAV"
        "ogg" -> "OGG"
        else -> subtype.uppercase(Locale.US)
    }
    return cleaned
}
