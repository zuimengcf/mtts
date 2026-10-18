package com.mtts.app.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * 文件日志 —— 将日志追加写入应用私有目录，便于开发时查看历史/崩溃上下文。
 *
 * 文件：{filesDir}/logs/mtts-YYYYMMdd.log
 * 线程安全：异步队列写入，不阻塞主线程。
 */
object FileLog {

    private const val TAG = "FileLog"
    // ★ 改用 LinkedBlockingQueue：take() 在无日志时阻塞（不消耗 CPU），替代旧 ConcurrentLinkedQueue + sleep(100) 轮询
    private val queue = LinkedBlockingQueue<String>()
    private var logDir: File? = null
    @Volatile private var workerStarted = false

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** 初始化日志目录并启动写线程（应在 Application/首次使用时调用） */
    fun init(context: Context) {
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
        if (!workerStarted) {
            workerStarted = true
            thread(isDaemon = true, name = "mtts-filelog") { writeLoop() }
        }
        i("log-dir", logDir?.absolutePath ?: "null")
    }

    private fun ensureFile(): File {
        val dir = logDir ?: File("/sdcard/mtts-logs")
        val name = "mtts-${dateFmt.format(Date())}.log"
        return File(dir, name)
    }

    private fun writeLoop() {
        while (true) {
            try {
                // ★ take() 在无日志时阻塞（不消耗 CPU / 不阻止深度休眠）；
                //   旧实现用 poll() + sleep(100) 轮询，空闲时每秒唤醒 10 次，导致后台发热。
                val line = queue.take()
                try {
                    ensureFile().appendText(line + "\n")
                } catch (t: Throwable) {
                    // 写失败不影响主流程
                }
            } catch (_: InterruptedException) {
                // 被中断则退出
                break
            }
        }
    }

    fun v(sub: String, msg: String) { enqueue("V", sub, msg) }
    fun d(sub: String, msg: String) { enqueue("D", sub, msg) }
    fun i(sub: String, msg: String) { enqueue("I", sub, msg) }
    fun w(sub: String, msg: String) { enqueue("W", sub, msg) }
    fun e(sub: String, msg: String) { enqueue("E", sub, msg) }
    /** 公共写入口（供 MttsLog 等镜像到文件） */
    fun log(level: String, sub: String, msg: String) { enqueue(level.uppercase(), sub, msg) }

    private fun enqueue(level: String, sub: String, msg: String) {
        val t = timeFmt.format(Date())
        queue.offer("$t [$level] mtts/$sub: $msg")
    }

/** 读取今天的日志内容 */
    fun readToday(context: Context): String {
        val dir = File(context.filesDir, "logs")
        val f = File(dir, "mtts-${dateFmt.format(Date())}.log")
        return if (f.exists()) f.readText() else ""
    }
    /** 列出所有日志文件 */
    fun listFiles(context: Context): List<File> {
        val dir = File(context.filesDir, "logs")
        return dir.listFiles { _, name -> name.endsWith(".log") }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    /**
     * 抓取 logcat 中 mtts / 搜狗原生(JNI、com_log) 相关日志并写回文件。
     * 用于 HTTP /logs 接口在进程内快捷读取 native 合成细节。
     */
    fun captureLogcat(context: Context, filter: String = "mtts|JNI|com_log|sogou|sgtts|ClientProxy|module"): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
            val all = proc.inputStream.bufferedReader().use { it.readText() }
            val lines = all.lines().filter {
                it.contains("mtts", true) || it.contains("sogou", true) ||
                it.contains("sgtts", true) || it.contains("com_log", true) ||
                it.contains("JNI", true) || it.contains("ClientProxy", true) ||
                it.contains("module", true)
            }
            val body = lines.joinToString("\n").takeLast(20000)
            if (body.isNotBlank()) {
                val captureFile = File(File(context.filesDir, "logs"), "logcat-capture-${dateFmt.format(Date())}.log")
                captureFile.appendText("\n===== logcat capture ${timeFmt.format(Date())} =====\n" + body + "\n")
            }
            body
        } catch (t: Throwable) {
            "logcat capture failed: ${t.message}"
        }
    }
}