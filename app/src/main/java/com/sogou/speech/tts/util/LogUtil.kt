package com.sogou.speech.tts.util

import android.util.Log

/**
 * 搜狗语音 SDK 的日志工具类。
 *
 * `libsgtts_lib.so` 的 `JNI_OnLoad → NativePrint::InitJNI` 会通过
 * `FindClass("com/sogou/speech/tts/util/LogUtil")` 加载本类，并缓存
 * `sendLog(ILjava/lang/String;Ljava/lang/String;)V` 静态方法 ID，随后在
 * `printLog` 中调用它输出 SDK 日志。
 *
 * 因此本类（与方法签名）必须存在，否则 JNI 层 abort 崩溃。
 * 为保持与原生 SDK 类名/签名一致，方法均通过 companion object + @JvmStatic
 * 生成在 LogUtil 类上的静态方法。此处为精简实现，不依赖原 apk 的 p3/a、d3/a、m5/c 类。
 */
class LogUtil {

    companion object {
        @Volatile
        private var enableFlag: Boolean = true

        @JvmStatic
        fun isEnable(): Boolean = enableFlag

        @JvmStatic
        fun setEnable(v: Boolean) { enableFlag = v }

        @JvmStatic
        fun sendLog(level: Int, tag: String?, msg: String?) {
            if (!enableFlag) return
            val t = tag ?: "SogouTTS"
            val m = msg ?: ""
            when (level) {
                2 -> Log.v(t, m)
                3 -> Log.d(t, m)
                4 -> Log.i(t, m)
                5 -> Log.w(t, m)
                6 -> Log.e(t, m)
                else -> Log.i(t, m)
            }
        }

        @JvmStatic
        fun d(tag: String?, msg: String?) = sendLog(3, tag, msg)

        @JvmStatic
        fun i(tag: String?, msg: String?) = sendLog(4, tag, msg)

        @JvmStatic
        fun w(tag: String?, msg: String?) = sendLog(5, tag, msg)

        @JvmStatic
        fun e(tag: String?, msg: String?) = sendLog(6, tag, msg)
    }
}
