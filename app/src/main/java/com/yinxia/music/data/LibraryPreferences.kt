package com.yinxia.music.data

import android.content.Context

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

    private companion object {
        const val PREFS_NAME = "yinxia_library"
        const val KEY_SORT_MODE = "sort_mode"
        const val KEY_SORT_ASC = "sort_ascending"
        const val KEY_FOLDER_FILTER = "folder_filter_enabled"
        const val KEY_FOLDERS = "selected_folders"
        const val KEY_MANUAL_ORDER = "manual_order"
    }
}
