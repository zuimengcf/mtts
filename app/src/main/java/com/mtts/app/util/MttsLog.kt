package com.mtts.app.util

import android.util.Log

/**
 * 开发调试日志工具。
 *
 * 特性：
 *  - 统一 tag 前缀 [TAG_PREFIX]，便于过滤
 *  - 分级：V/D/I/W/E
 *  - 全局开关 [ENABLE]，release 可关闭
 *  - 可携带结构化附加数据（map）
 *  - 可选文件镜像（[attachFileSink] 注入 FileLog，使日志同时写文件，
 *    供 HTTP /logs 接口与离线查看）
 *
 * 使用：
 *   MttsLog.d("EdgeTts", "connected", mapOf("voice" to "zh-CN"))
 */
object MttsLog {

    private const val TAG_PREFIX = "mtts"
    var ENABLE: Boolean = true

    /** 文件日志镜像（App 初始化时经 attachFileSink 注入，幂等） */
    @Volatile
    private var fileSink: ((String, String, String) -> Unit)? = null

    /** 打开文件镜像（幂等）。之后所有日志同时写入文件。 */
    fun attachFileSink(sink: (String, String, String) -> Unit) {
        if (fileSink == null) fileSink = sink
    }

    private fun tag(sub: String) = "$TAG_PREFIX/$sub"

    private fun mirror(level: String, sub: String, msg: String) {
        try { fileSink?.invoke(level, "mtts/$sub", msg) } catch (_: Throwable) {}
    }

    fun v(sub: String, msg: String) { if (ENABLE) { Log.v(tag(sub), msg); mirror("V", sub, msg) } }
    fun d(sub: String, msg: String) { if (ENABLE) { Log.d(tag(sub), msg); mirror("D", sub, msg) } }
    fun i(sub: String, msg: String) { if (ENABLE) { Log.i(tag(sub), msg); mirror("I", sub, msg) } }
    fun w(sub: String, msg: String) { if (ENABLE) { Log.w(tag(sub), msg); mirror("W", sub, msg) } }
    fun e(sub: String, msg: String, tr: Throwable? = null) {
        if (ENABLE) {
            if (tr != null) Log.e(tag(sub), msg, tr) else Log.e(tag(sub), msg)
            mirror("E", sub, if (tr != null) "$msg $tr" else msg)
        }
    }

    /** 带附加数据的日志（序列化为 key=value 追加） */
    fun d(sub: String, msg: String, data: Map<String, Any?>) {
        if (!ENABLE) return
        val sb = StringBuilder(msg)
        if (data.isNotEmpty()) {
            sb.append("  {")
            sb.append(data.entries.joinToString(", ") { "${it.key}=${it.value}" })
            sb.append("}")
        }
        Log.d(tag(sub), sb.toString())
        mirror("D", sub, sb.toString())
    }

    /** 网络/协议层专用：输出 URL、状态码、耗时 */
    fun http(sub: String, method: String, url: String,
             code: Int? = null, ms: Long? = null, extra: String? = null) {
        if (!ENABLE) return
        val sb = StringBuilder()
        sb.append(method).append(" ").append(url)
        code?.let { sb.append(" → ").append(it) }
        ms?.let { sb.append(" (").append(it).append("ms)") }
        extra?.let { sb.append(" [").append(it).append("]") }
        Log.i(tag(sub), sb.toString())
        mirror("I", sub, sb.toString())
    }
}