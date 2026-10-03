package com.yinxia.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 界面偏好的持久化：排序方式、扫描范围、手动顺序。
 *
 * 数据量极小（一个字符串加一个 id 列表），用 SharedPreferences 就够了，
 * 不值得为此引入 DataStore。
 */
class LibraryPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var sortMode: SortMode
        get() = runCatching { SortMode.valueOf(prefs.getString(KEY_SORT_MODE, null).orEmpty()) }
            .getOrDefault(SortMode.TITLE)
        set(value) {
            prefs.edit().putString(KEY_SORT_MODE, value.name).apply()
        }

    /** 仅对名称/时间排序有意义；手动排序忽略它 */
    var sortAscending: Boolean
        get() = prefs.getBoolean(KEY_SORT_ASC, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SORT_ASC, value).apply()
        }

    /**
     * 是否启用了「只扫描部分文件夹」。
     * 关掉时扫描全部，界面上的勾选状态统一显示为全选。
     */
    var folderFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_FOLDER_FILTER, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FOLDER_FILTER, value).apply()
        }

    var selectedFolders: Set<String>
        get() = prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_FOLDERS, value).apply()
        }

    /** 手动排序：按顺序保存的歌曲 id，逗号分隔（StringSet 不保序，所以用字符串） */
    var manualOrder: List<Long>
        get() = prefs.getString(KEY_MANUAL_ORDER, null)
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()
        set(value) {
            prefs.edit().putString(KEY_MANUAL_ORDER, value.joinToString(",")).apply()
        }

    /**
     * 用户自定义的默认主题色（ARGB）。null 表示"没指定"，界面用主题自带的紫罗兰。
     * 用 contains 判断有没有设过：没设过和设成 0 是两件事。
     */
    var defaultAccentArgb: Int?
        get() = if (prefs.contains(KEY_DEFAULT_ACCENT)) prefs.getInt(KEY_DEFAULT_ACCENT, 0) else null
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_DEFAULT_ACCENT) else putInt(KEY_DEFAULT_ACCENT, value)
            }.apply()
        }

    /** 是否在列表里显示码率/采样率这类详细信息 */
    var showSongDetails: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DETAILS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_DETAILS, value).apply()
        }

    /** 自建歌单。用 JSON 存：数量少、结构简单，不值得为此引入数据库 */
    var playlists: List<Playlist>
        get() {
            val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    val ids = item.optJSONArray("songIds") ?: JSONArray()
                    Playlist(
                        id = item.getLong("id"),
                        name = item.getString("name"),
                        songIds = (0 until ids.length()).map { ids.getLong(it) },
                    )
                }
            }.getOrDefault(emptyList())
        }
        set(value) {
            val array = JSONArray()
            value.forEach { playlist ->
                val ids = JSONArray()
                playlist.songIds.forEach { ids.put(it) }
                array.put(
                    JSONObject().apply {
                        put("id", playlist.id)
                        put("name", playlist.name)
                        put("songIds", ids)
                    },
                )
            }
            prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
        }

    private companion object {
        const val PREFS_NAME = "yinxia_library"
        const val KEY_SORT_MODE = "sort_mode"
        const val KEY_SORT_ASC = "sort_ascending"
        const val KEY_FOLDER_FILTER = "folder_filter_enabled"
        const val KEY_FOLDERS = "selected_folders"
        const val KEY_MANUAL_ORDER = "manual_order"
        const val KEY_DEFAULT_ACCENT = "default_accent_argb"
        const val KEY_SHOW_DETAILS = "show_song_details"
        const val KEY_PLAYLISTS = "playlists"
    }
}
