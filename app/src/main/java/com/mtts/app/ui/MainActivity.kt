package com.mtts.app.ui
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mtts.app.ui.theme.MttsTheme
class MainActivity : ComponentActivity() {
    // Android 13+ 请求通知权限，保证保活前台服务的常驻通知可见
    private val requestNotifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * 完全退出：停掉保活前台服务 + HTTP 网关 + 销毁 Activity 进程。
     * 通过静态方法暴露给可组合层调用。
     */
    companion object {
        @JvmStatic
        fun exitApp(activity: android.app.Activity) {
            // 1) 先停服务、停 HTTP 网关
            try { com.mtts.app.service.KeepAliveService.stop(activity.applicationContext, true) } catch (_: Throwable) {}
            try { com.mtts.app.server.LocalHttpServer.stop() } catch (_: Throwable) {}
            // 2) 同步落盘 keepAlive=false（commit 阻塞写盘，确保 killProcess 前已保存，
            //    否则下次启动读到默认 true，KeepAlive 服务会被系统/应用重新拉起）
            try { com.mtts.app.util.AppPrefs.setKeepAliveEnabledSync(false) } catch (_: Throwable) {}
            // 3) 销毁 Activity
            try { activity.finishAffinity() } catch (_: Throwable) {}
            // 4) 结束进程
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 13+ 请求通知权限（保活服务通知展示）；已授权则跳过
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            try { requestNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
            catch (_: Throwable) {}
        }
        // 冷启动已由 Starting 主题展示品牌遮罩；内容就绪后切换到正常主题，避免遮罩残留
        // （Activity 销毁重建时保持主题稳定，仅首帧用 Starting）
        setTheme(com.mtts.app.R.style.Theme_Mtts)
        enableEdgeToEdge()
        setContent {
            MttsTheme(dynamicColor = true) {
                val background = MaterialTheme.colorScheme.background
                val isDark = isSystemInDarkTheme()
                // 状态栏 / 导航栏图标按明暗自适应（edge-to-edge 下保证可见）
                SideEffect {
                    window.statusBarColor = background.toArgb()
                    window.navigationBarColor = background.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !isDark
                        isAppearanceLightNavigationBars = !isDark
                    }
                }
                Surface(modifier = Modifier.fillMaxSize(),
                        color = background) {
                    val vm: MainViewModel = viewModel()
                    MttsApp(vm)
                }
            }
        }
    }
}