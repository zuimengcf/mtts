package com.mtts.app.server

import com.mtts.app.engine.EngineRegistry
import com.mtts.app.util.FileLog
import com.mtts.app.util.MttsLog
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 本地 HTTP 调试网关 —— 在安卓 App 内启动，暴露 HTTP 接口调用本地引擎。
 *
 * 通过 http://<手机IP>:<port>/api/v1/... 可调用 app 内的在线/规则/离线(JNI)引擎。
 * 使用 ServerSocket + 极简 HTTP 解析（Android 无 com.sun.net.httpserver）。
 *
 * 接口：
 *   GET /health                            健康检查
 *   GET /api/v1/status                     引擎状态
 *   GET /api/v1/engines                    引擎列表
 *   GET /api/v1/voices?engine=<code>       语音列表
 *   GET /api/v1/synthesize?engine=<code>&text=..&voice=..  合成（返回音频字节）
 *   GET /api/v1/detect?text=..             角色识别：粘贴小说文本，返回说话人列表（人名+命中规则+性别+出现次数，按次数降序）
 *   GET /api/v1/synthesize_dialog?text=..  对话合成（模拟外部 TTS 请求）：整段小说文本走对话识别→分说话人/旁白逐段合成→拼接返回音频
 *   GET /api/v1/logs                       读取日志
 */
object LocalHttpServer {

    /** 认证令牌：从设置读取（AppPrefs），不再硬编码 */
    private val token: String get() = com.mtts.app.util.AppPrefs.httpToken()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var acceptor: Thread? = null

    /** 实际生效端口 */
    @Volatile var actualPort: Int = 0
        private set

    /**
     * 启动 HTTP 服务（监听 0.0.0.0）。
     * 若首选端口被占用（如 PC 侧 debug_server 占用了 8000），自动回退到下一个可用端口。
     */
    fun start(port: Int = 8080) {
        if (running) return
        var ss: ServerSocket? = null
        var boundPort = 0
        // 最多尝试 20 个端口，保证总能绑上一个
        for (p in port until port + 20) {
            try {
                ss = ServerSocket(p)
                boundPort = p
                break
            } catch (_: Exception) {
                MttsLog.w("HttpServer", "port $p busy, try next")
            }
        }
        if (ss == null) {
            MttsLog.e("HttpServer", "start failed: no free port in [$port, ${port + 20})")
            running = false
            return
        }
        try {
            serverSocket = ss
            actualPort = boundPort
            running = true
            val t = Thread {
                while (running) {
                    try {
                        val client = ss.accept()
                        Thread { handle(client) }.start()
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            t.name = "mtts-http-accept"
            t.isDaemon = true
            t.start()
            acceptor = t
            MttsLog.i("HttpServer", "listening on :$port")
        } catch (e: Exception) {
            MttsLog.e("HttpServer", "start failed", e)
            running = false
        }
    }

    /** 停止服务 */
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        MttsLog.i("HttpServer", "stopped")
    }

    val isRunning: Boolean get() = running

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            // 读取请求行
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            if (method != "GET") { sendJson(socket, 405, mapOf("error" to "仅支持 GET")); return }

            // 解析 path 和 query
            val qIdx = target.indexOf('?')
            val path = if (qIdx >= 0) target.substring(0, qIdx) else target
            val query = if (qIdx >= 0) target.substring(qIdx + 1) else ""
            val params = parseQuery(query)

            // 认证（health 除外）
            var tokenOk = false
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("X-Auth-Token:")) {
                    tokenOk = line.substringAfter(':').trim() == token
                }
            }
            if (path != "/health" && !tokenOk) { sendJson(socket, 401, mapOf("error" to "未授权")); return }

            when {
                path == "/health" ->
                    sendJson(socket, 200, mapOf("ok" to true, "secure" to false))

                path == "/api/v1/status" ->
                    sendJson(socket, 200, apiStatus())

                path == "/api/v1/engines" ->
                    sendJson(socket, 200, apiEngines())

                path == "/api/v1/voices" -> {
                    val engine = params["engine"] ?: ""
                    sendJson(socket, 200, apiVoices(engine))
                }

                path == "/api/v1/synthesize" -> {
                    val engine = params["engine"] ?: ""
                    val text = params["text"] ?: ""
                    if (engine.isBlank() || text.isBlank()) {
                        sendJson(socket, 400, mapOf("error" to "缺少 engine/text 参数")); return
                    }
                    synthesize(socket, engine, text, params["voice"].orEmpty(),
                        params["speed"]?.toFloatOrNull() ?: 1.0f,
                        params["volume"]?.toFloatOrNull() ?: 1.0f,
                        params["pitch"]?.toFloatOrNull() ?: 0.0f)
                }

                path == "/api/v1/detect" -> {
                    val text = params["text"] ?: ""
                    if (text.isBlank()) { sendJson(socket, 400, mapOf("error" to "缺少 text 参数")); return }
                    val roles = com.mtts.app.util.DialogFeature.autoDetectCharacters(text)
                    sendJson(socket, 200, mapOf(
                        "ok" to true,
                        "count" to roles.size,
                        "roles" to roles.map {
                            mapOf("name" to it.name, "rule" to it.rule, "gender" to it.gender, "count" to it.count)
                        }
                    ))
                }

                path == "/api/v1/synthesize_dialog" -> {
                    val text = params["text"] ?: ""
                    if (text.isBlank()) { sendJson(socket, 400, mapOf("error" to "缺少 text 参数")); return }
                    synthesizeDialog(socket, text,
                        params["speed"]?.toFloatOrNull() ?: 1.0f,
                        params["volume"]?.toFloatOrNull() ?: 1.0f,
                        params["pitch"]?.toFloatOrNull() ?: 0.0f)
                }

                path == "/api/v1/logs" -> apiLogs(socket, params)

                else ->
                    sendJson(socket, 404, mapOf("error" to "未知路径 $path"))
            }
        } catch (e: Exception) {
            try { sendJson(socket, 500, mapOf("error" to "服务异常: ${e.message}")) } catch (_: Exception) {}
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun synthesize(
        socket: Socket, engine: String, text: String, voice: String,
        speed: Float, volume: Float, pitch: Float
    ) {
        val eng = EngineRegistry.get(engine)
        if (eng == null) { sendJson(socket, 404, mapOf("error" to "引擎 $engine 不存在")); return }
        try {
            val audio = runBlocking {
                val v = voice.ifBlank { eng.listVoices().firstOrNull()?.shortName.orEmpty() }
                // ★ 剥离停顿/音量/音调标记，避免 ⟨pause:ms⟩ 等被引擎当普通文字朗读（与 TtsService 一致）
                val clean = com.mtts.app.util.TextPreprocessor.stripAllMarkers(text)
                eng.synthesize(clean, v, speed, volume, pitch)
            }
            if (audio.isEmpty()) { sendJson(socket, 500, mapOf("error" to "合成结果为空")); return }

            val header = ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/mpeg\r\n" +
                "Content-Length: ${audio.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
            val out: OutputStream = socket.getOutputStream()
            out.write(header)
            out.write(audio)
            out.flush()
        } catch (e: Exception) {
            MttsLog.e("HttpServer", "synthesize failed [$engine]", e)
            sendJson(socket, 500, mapOf("error" to "合成失败: ${e.message}"))
        }
    }

    /**
     * 对话合成（模拟外部 TTS 请求）：
     * 整段小说文本 → DialogFeature.process 识别说话人/旁白分段 → 逐段合成 → 拼接为完整音频返回。
     * 与 TtsService 的对话朗读链路一致。
     */
    private fun synthesizeDialog(
        socket: Socket, text: String,
        speed: Float, volume: Float, pitch: Float
    ) {
        try {
            // 对话功能未启用时与普通 synthesize 等效
            val segs = com.mtts.app.util.DialogFeature.process(text, { ec ->
                runBlocking {
                    EngineRegistry.get(ec)?.listVoices() ?: emptyList()
                }
            }, defaultEngine(), defaultVoice())
            val segments = segs ?: listOf(com.mtts.app.util.DialogSeg(text, true, "", defaultEngine(), defaultVoice(), speed, volume, pitch))

            // 逐段合成拼接
            val out = java.io.ByteArrayOutputStream()
            val segMeta = ArrayList<Map<String, Any>>()
            for (seg in segments) {
                if (seg.text.isBlank()) continue
                val eng = EngineRegistry.get(seg.engineCode)
                if (eng == null) continue
                val clean = com.mtts.app.util.TextPreprocessor.stripAllMarkers(seg.text)
                if (clean.isBlank()) continue
                val segVoice = seg.voice.ifBlank {
                    runBlocking { eng.listVoices().firstOrNull()?.shortName }.orEmpty()
                }
                val audio = runBlocking {
                    eng.synthesize(clean, segVoice,
                        (speed * seg.speed).coerceIn(0.25f, 4f),
                        (volume * seg.volume).coerceIn(0f, 2f),
                        (pitch + seg.pitch).coerceIn(-1f, 1f))
                }
                if (audio.isNotEmpty()) {
                    out.write(audio)
                    segMeta.add(mapOf(
                        "speaker" to (seg.speaker.ifBlank { if (seg.isSpeech) "对白" else "旁白" }),
                        "engine" to eng.code, "voice" to segVoice,
                        "text" to clean.take(30), "bytes" to audio.size))
                }
            }
            if (out.size() == 0) { sendJson(socket, 500, mapOf("error" to "合成结果为空")); return }

            val body = out.toByteArray()
            val header = ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/mpeg\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "X-Dialog-Segments: ${segMeta.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
            val o = socket.getOutputStream()
            o.write(header)
            o.write(body)
            o.flush()
        } catch (e: Exception) {
            MttsLog.e("HttpServer", "synthesizeDialog failed", e)
            sendJson(socket, 500, mapOf("error" to "对话合成失败: ${e.message}"))
        }
    }

    /** 当前默认引擎（与 TtsService 一致：上次选择的引擎或 microsoft 兜底） */
    private fun defaultEngine(): String =
        com.mtts.app.util.AppPrefs.getSelectedEngine()?.takeIf {
            EngineRegistry.get(it) != null
        } ?: "microsoft"

    /** 当前默认音色（该引擎上次选择的发言人，空则由引擎自动挑） */
    private fun defaultVoice(): String =
        com.mtts.app.util.AppPrefs.getSelectedVoice(defaultEngine()).orEmpty()

    private fun apiLogs(socket: Socket, params: Map<String, String>) {
        val ctx = com.mtts.app.MttsApplication.ctx()
        if (ctx == null) { sendJson(socket, 500, mapOf("error" to "app context 未就绪")); return }
        try {
            val source = params["tab"] ?: "file"
            val n = params["n"]?.toIntOrNull() ?: 2000
            val level = params["level"] ?: "all"   // all / error / warn / info / debug
            val raw: String = when (source) {
                "logcat" -> FileLog.captureLogcat(ctx)
                "logcat-raw" -> {
                    val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
                    p.inputStream.bufferedReader().use { it.readText() }
                }
                else -> FileLog.readToday(ctx)
            }
            // 按级别过滤
            var text = raw
            if (level != "all" && source != "logcat") {
                val re = when (level) {
                    "error" -> "\n.*\\[E\\].*"
                    "warn" -> "\n.*\\[[EW]\\].*"
                    "info" -> "\n.*\\[[EWI]\\].*"
                    "debug" -> "\n.*\\[[EWID]\\].*"
                    else -> ""
                }
                if (re.isNotEmpty()) text = raw.replace(Regex(dropFirstNewline(re)), "\n")
            }
            // 取尾部 n 行
            val lines = text.split("\n")
            val tail = if (text.contains("mtts/")) lines else lines
            val body = if (tail.size > n) tail.takeLast(n).joinToString("\n") else text
            sendJson(socket, 200, mapOf(
                "ok" to true,
                "source" to source,
                "level" to level,
                "lines" to (if (source == "logcat") lines.size else tail.size),
                "bytes" to text.length,
                "log" to body
            ))
        } catch (e: Exception) {
            sendJson(socket, 500, mapOf("error" to "日志读取失败: ${e.message}"))
        }
    }
    private fun dropFirstNewline(s: String) = s.removePrefix("\n")

    private fun apiStatus(): Map<String, Any> =
        mapOf("engines" to EngineRegistry.all().map {
            mapOf("code" to it.code, "name" to it.name, "status" to it.status.label)
        })

    private fun apiEngines(): Map<String, Any> =
        mapOf("engines" to EngineRegistry.all().map {
            mapOf("code" to it.code, "name" to it.name, "status" to it.status.label,
                  "category" to it.category.name)
        })

    private fun apiVoices(engine: String): Map<String, Any> {
        val eng = EngineRegistry.get(engine)
        if (eng == null) return mapOf("error" to "引擎 $engine 不存在")
        val voices = runBlocking { eng.listVoices() }
        return mapOf("engine" to engine, "kind" to eng.category.name, "voices" to
            voices.map { mapOf("shortName" to it.shortName, "name" to it.name, "locale" to it.locale) })
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = HashMap<String, String>()
        if (query.isBlank()) return map
        query.split("&").forEach {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) map[kv[0]] = URLDecoder.decode(kv[1], "UTF-8")
        }
        return map
    }

    private fun sendJson(socket: Socket, code: Int, obj: Any) {
        val body = com.google.gson.Gson().toJson(obj).toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 500 -> "Internal Server Error"
            else -> "OK"
        }
        val header = ("HTTP/1.1 $code $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
        val out: OutputStream = socket.getOutputStream()
        out.write(header)
        out.write(body)
        out.flush()
    }
}