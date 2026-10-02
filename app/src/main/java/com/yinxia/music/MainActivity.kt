package com.yinxia.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.yinxia.music.ui.PlayerViewModel
import com.yinxia.music.ui.YinxiaApp
import com.yinxia.music.ui.theme.YinxiaTheme

/**
 * 唯一的 Activity。
 *
 * 权限状态放在 Activity 而不是 Compose 里，是为了用 onResume 处理
 * "用户跑去系统设置里开权限再回来"这条路：一回来就重新检查。
 */
class MainActivity : ComponentActivity() {

    private val permissionGranted = mutableStateOf(false)

    private val viewModel: PlayerViewModel by lazy {
        // 显式给 AndroidViewModelFactory：不依赖各版本 lifecycle 对默认工厂的推断
        ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[PlayerViewModel::class.java]
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionGranted.value = hasAudioPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容延伸到状态栏/导航栏底下，由 Compose 的 Scaffold 负责避开
        WindowCompat.setDecorFitsSystemWindows(window, false)
        permissionGranted.value = hasAudioPermission()

        setContent {
            YinxiaTheme {
                YinxiaApp(
                    viewModel = viewModel,
                    permissionGranted = permissionGranted.value,
                    onRequestPermission = { permissionLauncher.launch(requiredPermissions()) },
                    onOpenAppSettings = ::openAppSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionGranted.value = hasAudioPermission()
    }

    private fun hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        audioPermission(),
    ) == PackageManager.PERMISSION_GRANTED

    /** Android 13 起读音频用的是 READ_MEDIA_AUDIO，老的存储权限不再有效。 */
    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun requiredPermissions(): Array<String> = buildList {
        add(audioPermission())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }
}
