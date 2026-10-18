package com.sogou.speech.proxy

import com.mtts.app.loader.NativeSoLoader
import com.mtts.app.util.MttsLog

/**
 * 搜狗语音 SDK JNI 桥 —— 完整复刻 MultiTTS NativeProxy.smali。
 *
 * 类名/包名必须为 com.sogou.speech.proxy.NativeProxy，才能与
 * libsgtts_lib.so 导出的 JNI 符号 `Java_com_sogou_speech_proxy_NativeProxy_native_*` 绑定。
 *
 * 调用序列：
 *   searchService(服务名, initJson) → connect(mode, initJson) → write(textJson) → read([int]) → destroyService(服务名)
 */
class NativeProxy {
    /** 服务句柄 */
    @Volatile
    private var a: Long = 0L
    // ---- 原生方法（由 libsgtts_lib.so 提供）----
    external fun native_searchService(serviceName: String, initJson: String): Long
    external fun native_connect(handle: Long, mode: Int, initJson: String): Int
    external fun native_write(handle: Long, textJson: String): Int
    external fun native_read(handle: Long, outLen: IntArray?): ByteArray
    external fun native_destroyService(handle: Long, serviceName: String): Int
    external fun native_cancel(handle: Long): Int
    // ---- 便捷包装（对应 smali 方法 a/b/c/d/e）----
    fun searchService(serviceName: String, initJson: String): Long {
        a = native_searchService(serviceName, initJson)
        return a
    }
    fun connect(initJson: String): Int = native_connect(a, 1, initJson)
    fun write(textJson: String): Int = native_write(a, textJson)
    fun read(outLen: IntArray? = null): ByteArray = native_read(a, outLen)
    fun destroy(serviceName: String) = native_destroyService(a, serviceName)

    companion object {
        /**
         * 从外部导入目录动态加载搜狗 SDK（不内置 .so）。
         * 依赖顺序（原 MultiTTS o3/b.smali <clinit>）：
         *   omp → sgsplit → sgtts → sgproxy_lib → sgtts_lib
         * 某些库可选，加载失败忽略（由 NativeSoLoader 统一处理）。
         */
        @JvmStatic
        fun loadLibraries(externalDir: java.io.File? = null) {
            val root = externalDir ?: com.mtts.app.loader.NativeSoLoader.externalRoot
            if (root == null || !root.isDirectory) {
                MttsLog.w("SogouSDK", "loadLibraries: external root missing $root")
                return
            }
            val engineDir = java.io.File(root, "sougou")
            if (!engineDir.isDirectory) {
                MttsLog.w("SogouSDK", "loadLibraries: sougou dir missing $engineDir")
                return
            }
            val order = listOf("omp", "sgsplit", "sgtts", "sgproxy_lib", "sgtts_lib")
            val result = NativeSoLoader.stageAndLoad(engineDir, order)
            MttsLog.d("SogouSDK", "dynamic load result: $result")
        }
    }
}
