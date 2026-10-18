package com.mtts.app

import android.app.Application
import com.mtts.app.engine.EngineRegistry
import com.mtts.app.engine.VoicePackageManager
import com.mtts.app.server.LocalHttpServer
import com.mtts.app.util.CrashLogger
import com.mtts.app.util.FileLog
import com.mtts.app.util.MttsLog

class MttsApplication : Application() {

    companion object {
        @Volatile
        private var instance: MttsApplication? = null
        /** 返回 App 上下文（供日志/资源读取） */
        @JvmStatic
        fun ctx(): MttsApplication? = instance
        /** 返回 App 的 native 库目录（供搜狗等 SDK 定位 .so 路径） */
        @JvmStatic
        fun nativeLibDir(): String =
            instance?.applicationInfo?.nativeLibraryDir ?: "/data/app/com.mtts.app/lib/arm64"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化日志系统（logcat + 文件 + 崩溃捕获）
        FileLog.init(this)
        CrashLogger.install(this)
        // 让全部 MttsLog 同步镜像到文件（供 HTTP /logs 读取）
        MttsLog.attachFileSink { level, sub, msg -> FileLog.log(level, sub, msg) }
        MttsLog.d("App", "mtts application start")

        // 初始化语音包资源管理器
        VoicePackageManager.init(this)
        // 将 assets 内置语音包解压到 Android/data 私有目录（后台线程，避免阻塞主线程导致 ANR）
        Thread {
            VoicePackageManager.unpackAssets(this)
            MttsLog.d("App", "voice assets unpacked")
        }.apply {
            name = "voice-unpack"
            isDaemon = true
        }.start()

        // 预注册引擎
        EngineRegistry.init()
        MttsLog.d("App", "engines registered: ${EngineRegistry.availableCodes()}")

        // 后台预热 jieba 分词器（对话功能角色识别用）：词典 ~2MB，首次加载慢。
        // 必须在非主线程做，否则用户点「自动识别角色」时首次加载会卡死主线程导致 ANR。
        Thread {
            val t0 = System.currentTimeMillis()
            com.mtts.app.util.JiebaNameExtractor.warmUp()
            MttsLog.d("App", "jieba warmed up in ${System.currentTimeMillis() - t0}ms")
        }.apply {
            name = "jieba-warmup"
            isDaemon = true
        }.start()

        // 后台预热 sherpa-onnx 引擎（若已导入模型）：52MB onnx 首次加载 + session 初始化耗时
        // 5~15s，必须提前到启动时，否则用户第一次点合成会卡很久。
        // 受设置开关控制（sherpa_warmup_enabled，默认开；关掉则首次合成时才加载）。
        Thread {
            try {
                if (!com.mtts.app.util.AppPrefs.isSherpaWarmupEnabled()) {
                    MttsLog.d("App", "sherpa warmup disabled by settings, skip")
                    return@Thread
                }
                val eng = EngineRegistry.get("sherpa") as? com.mtts.app.engine.SherpaOnnxEngine
                if (eng != null) {
                    val t0 = System.currentTimeMillis()
                    eng.warmUp()
                    MttsLog.d("App", "sherpa warmed up in ${System.currentTimeMillis() - t0}ms")
                } else {
                    MttsLog.d("App", "sherpa engine not registered, skip warmup")
                }
            } catch (t: Throwable) {
                MttsLog.w("App", "sherpa warmup failed: ${t.message}")
            }
        }.apply {
            name = "sherpa-warmup"
            isDaemon = true
        }.start()

        // 启动本地 HTTP 调试网关（调用本地引擎）
        // 端口由设置里的 http_port 控制（默认 8080）；若被占用会自动回退。注意 PC 侧 debug_server 占用 8000，切勿与它冲突。
        // 受设置里的 HTTP 开关控制：关闭则不启动网关。
        if (com.mtts.app.util.AppPrefs.isHttpEnabled()) {
            LocalHttpServer.start(com.mtts.app.util.AppPrefs.httpPort())
        } else {
            LocalHttpServer.stop()
        }
        MttsLog.i("App", "local http server port=${LocalHttpServer.actualPort}")
        // 启动保活前台服务（常驻进程 + 网关自愈），保证后台可用性。
        // 受退出开关控制：用户在设置里点「退出」后置 keepAlive=false，则不再常驻。
        if (com.mtts.app.util.AppPrefs.isKeepAliveEnabled()) {
            com.mtts.app.service.KeepAliveService.start(this)
        }
    }
}