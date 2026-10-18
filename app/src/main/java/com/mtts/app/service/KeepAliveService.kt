package com.mtts.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.mtts.app.R
import com.mtts.app.server.LocalHttpServer
import com.mtts.app.ui.MainActivity
import com.mtts.app.util.MttsLog

/**
 * 保活前台服务。
 *
 * 以「数据同步」前台服务常驻进程，显著降低应用被系统低内存清理(LMK)回收的概率，
 * 从而保证本地 HTTP 网关(8080) 与离线 TTS 引擎持续在后台可用。
 *
 * 保活策略（极简低功耗）：
 *  1. startForeground 常驻通知（Android 13+ 需 POST_NOTIFICATIONS 说明用途）
 *  2. 无额外后台线程/轮询——纯通知保活，不通过通知以外的手段耗电
 *
 * HTTP 网关自愈已移除：网关由 MttsApplication.onCreate 启动，服务被系统重建时
 * onStartCommand 会检查 keepAlive 开关，若已关闭则直接 stopSelf 不再拉起。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"
        private const val CHANNEL_ID = "mtts_keep_alive"
        private const val NOTIF_ID = 0x4d545453 // 'MTTS'

        /** 退出动作：停止保活服务 + HTTP 网关 */
        const val ACTION_EXIT = "com.mtts.app.action.EXIT_APP"

        /** 启动保活前台服务 */
        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                MttsLog.w(TAG, "startForegroundService failed: ${t.message}")
            }
        }
        /** 停止保活服务（并记录退出意图，防再次自启） */
        fun stop(context: Context, disablePermanent: Boolean = false) {
            try {
                if (disablePermanent) com.mtts.app.util.AppPrefs.setKeepAliveEnabled(false)
                // ★ 关闭常驻时同步关闭 HTTP 网关：保活服务存在的意义就是保证本地 HTTP 网关
                //   持续在后台可用；停掉常驻即不再保证后台可用，应一并关掉网关，
                //   避免进程仍在时 8080 网关空跑监听、以及 watchdog 竞态下又被当成"需自愈"重启。
                try { LocalHttpServer.stop() } catch (_: Throwable) {}
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (_: Throwable) {}
        }
    }

    private var alive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 收到「退出」动作：完全退出 —— 停 HTTP 网关 + 停 TTS 前台服务 + 持久化关闭 + 杀进程
        if (intent?.action == ACTION_EXIT) {
            MttsLog.i(TAG, "exit action received, full shutdown")
            try { LocalHttpServer.stop() } catch (_: Throwable) {}
            // 停掉系统 TTS 前台服务（若在运行），释放其前台通知
            try { stopService(Intent(this, TtsService::class.java)) } catch (_: Throwable) {}
            // 同步落盘 keepAlive=false（commit 阻塞写盘，确保 killProcess 前已保存，防重启被拉起）
            com.mtts.app.util.AppPrefs.setKeepAliveEnabledSync(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            // 稍等让 onDestroy 清理完毕，再真正结束进程（前台服务 stop 后进程仍存活的根因：没杀进程）
            Thread {
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
                try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Throwable) {}
            }.apply {
                name = "mtts-exit-killer"
                isDaemon = false
            }.start()
            return START_NOT_STICKY
        }
        // 若已被退出开关停用，直接停止本服务不再常驻
        if (!com.mtts.app.util.AppPrefs.isKeepAliveEnabled()) {
            MttsLog.d(TAG, "keep-alive disabled, stop self")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        // ★ 极简保活：无看门狗线程，仅前台通知保活
        // START_NOT_STICKY：进程被杀后不自动重建（由用户/App 启动时重新拉起）
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "MTTS 保活服务", NotificationManager.IMPORTANCE_LOW)
                )
            }
            startForeground(NOTIF_ID, buildNotification(nm))
            MttsLog.d(TAG, "foreground started")
        } catch (t: Throwable) {
            MttsLog.w(TAG, "startForeground failed: ${t.message}")
        }
    }

    private fun buildNotification(nm: NotificationManager): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder: Notification.Builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, CHANNEL_ID)
            else
                Notification.Builder(this)
        builder.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MTTS 服务运行中")
            .setContentText("本地 TTS 引擎与 HTTP 网关持续在线")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "退出", exitIntent())
        return builder.build()
    }

    /** 「退出」按钮：发给本服务处理（停止 HTTP 网关 + 关闭保活） */
    private fun exitIntent(): PendingIntent {
        val i = Intent(this, KeepAliveService::class.java).setAction(ACTION_EXIT)
        return PendingIntent.getService(this, 1, i, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onDestroy() {
        alive = false
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        try {
            (getSystemService(NotificationManager::class.java))
                ?.cancel(NOTIF_ID)
        } catch (_: Throwable) {}
        super.onDestroy()
        MttsLog.d(TAG, "destroyed")
    }
}