package com.mtts.app.util

import android.content.Context

/**
 * 全局崩溃日志捕获 —— 将未捕获异常写入文件日志，便于开发定位。
 */
object CrashLogger {

    private var installed = false
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /** 安装全局异常捕获（幂等） */
    fun install(context: Context) {
        if (installed) return
        installed = true
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sb = StringBuilder()
            sb.append("UNCAUGHT EXCEPTION on thread: ").append(thread.name).append('\n')
            sb.append(throwable.toString()).append('\n')
            for (st in throwable.stackTrace) {
                sb.append("    at ").append(st.toString()).append('\n')
            }
            throwable.cause?.let { cause ->
                sb.append("Caused by: ").append(cause.toString()).append('\n')
                for (st in cause.stackTrace) {
                    sb.append("    at ").append(st.toString()).append('\n')
                }
            }
            FileLog.e("Crash", sb.toString())
            // 交给默认处理器（系统崩溃对话框等）
            defaultHandler?.uncaughtException(thread, throwable)
        }
        MttsLog.i("CrashLogger", "installed")
    }
}