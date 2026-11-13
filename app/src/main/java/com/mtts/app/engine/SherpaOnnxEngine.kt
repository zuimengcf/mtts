package com.mtts.app.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mtts.app.util.MttsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * sherpa 引擎（1.30 直推版）：用微软 onnxruntime 1.30 直接推理 VITS 模型，
 * 彻底绕开 sherpa-onnx JNI（其依赖 libonnxruntime.so@1.28.2 与微软 1.30 符号版本冲突）。
 * 模型目录：voice/sherpa/<modelDirName>/{*.onnx, lexicon.txt, tokens.txt, ...}
 * 前端自动识别：MeloTTS（输入含 tones）与 Piper（无 tones）。
 * 统一输出 24000Hz 16bit 单声道 PCM。
 */
class SherpaOnnxEngine : TtsEngine {
    override val code = "sherpa"
    override val name = "Sherpa离线"
    override val category = EngineCategory.OFFLINE
    override val status = EngineStatus.READY

    @Volatile var useNnapi: Boolean = false

    private val engineRoot: File?
        get() = VoicePackageManager.externalEngineDir("sherpa")
            ?: VoicePackageManager.engineDir("sherpa")

    private fun modelDirs(): List<File> {
        val root = engineRoot ?: return emptyList()
        return root.listFiles { f ->
            f.isDirectory && f.listFiles { it.extension.equals("onnx", true) }?.isNotEmpty() == true
        }?.sortedBy { it.name } ?: emptyList()
    }

    private val modelDir: File?
        get() {
            val dirs = modelDirs()
            if (dirs.isEmpty()) return null
            return dirs.firstOrNull { d ->
                d.listFiles { f -> f.name.startsWith("G_") && f.extension.equals("json", true) }?.isNotEmpty() == true
            } ?: dirs.first()
        }

    private fun resolveVoice(voice: String): Pair<File, Int> {
        val dirs = modelDirs()
        require(dirs.isNotEmpty()) { "sherpa 模型目录缺失（请导入 voice/sherpa）" }
        val sep = "::"
        if (voice.contains(sep)) {
            val dirName = voice.substringBefore(sep)
            val sidPart = voice.substringAfter(sep)
            val dir = dirs.firstOrNull { it.name == dirName }
                ?: throw RuntimeException("sherpa 音色目录不存在: $dirName (可选: ${dirs.joinToString { it.name }})")
            val sid = sidPart.removePrefix("sid").toIntOrNull() ?: 0
            return dir to sid
        }
        // 无 :: 后缀的 voice：优先按目录名精确匹配（Melo 等单说话人模型的 voice 就是目录名本身，
        //  如 vits-melo-tts-zh_en），否则其会永远落到 modelDir（排序第一个）而选错模型。
        dirs.firstOrNull { it.name == voice }?.let { return it to sidOf(voice) }
        val dir = modelDir ?: throw RuntimeException("sherpa 模型目录缺失")
        val sid = voice.removePrefix("sid").toIntOrNull() ?: 0
        return dir to sid
    }

    private fun sidOf(voice: String): Int = voice.removePrefix("sid").toIntOrNull() ?: 0

    private fun mainModel(dir: File): File? {
        // ★ 排除声码器（vocos*.onnx），它不作为主 TTS 模型
        val all = dir.listFiles { f -> f.extension.equals("onnx", true) &&
            !f.name.contains("vocos", true) }
            ?.sortedBy { it.name } ?: return null
        if (all.isEmpty()) return null
        return all.sortedWith(compareBy(
            { !it.name.contains("int8", true) && !it.name.contains("fp16", true) },
            { it.name }
        )).first()
    }

    private fun lexiconFile(dir: File): File? {
        val exact = File(dir, "lexicon.txt")
        if (exact.isFile) return exact
        val cands = dir.listFiles { f ->
            f.isFile && f.name.startsWith("lexicon") && f.extension.equals("txt", true)
        } ?: return null
        return cands.sortedBy { it.name }.firstOrNull()
    }

    private fun tokensFile(dir: File): File? {
        val t = File(dir, "tokens.txt")
        return if (t.isFile) t else null
    }

    private fun speakersJson(dir: File): File? =
        dir.listFiles { f -> f.name.startsWith("G_") && f.extension.equals("json", true) }?.firstOrNull()

    // ============ 模型元数据 / 加载 ============
    private enum class ModelKind { MELO, PIPER, KOKORO, MATCHA }

    private class VitsModel(
        val dir: File,
        val kind: ModelKind,
        val env: OrtEnvironment,
        val session: OrtSession,
        val sampleRate: Int,
        @Volatile var speakerId: Int,
        val addBlank: Boolean,
        val token2id: Map<String, Int>,
        val blankId: Int,
        // ---- Kokoro 专属 ----
        val styles: FloatArray? = null,        // [n_speakers * styleDim0 * styleDim2]
        val styleDim0: Int = 0,                // 最大 token 长（510）
        val styleDim2: Int = 0,                // style 嵌入维（256）
        val nSpeakers: Int = 0,
        val lexiconZh: Map<String, List<String>>? = null,  // 汉字→音素
        val lexiconEn: Map<String, List<String>>? = null,   // 英文词→音素
        // ---- Matcha 专属 ----
        val vocosSession: OrtSession? = null,  // Vocos 声码器
        val nFft: Int = 1024,
        val hopLength: Int = 256,
        val winLength: Int = 1024,
        val padId: Int = 0
    ) {
        @Volatile var lastUsedAt = android.os.SystemClock.elapsedRealtime()
        @Volatile var disposed = false

        /** 释放底层 ORT session（主 session + vocos 声码器）。env 是全局单例，绝不 close。 */
        fun dispose() {
            if (disposed) return
            synchronized(dispatchedLock) {
                if (disposed) return
                disposed = true
                try { session.close() } catch (_: Throwable) {}
                try { vocosSession?.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun useThreads(onnx: File): Int {
        // 自适应线程数：小模型(int8/fp16/piper/melo)少线程，避免移动端中小核竞争+功耗
        val mb = onnx.length() / (1024 * 1024)
        return when {
            mb <= 40 -> 2
            mb <= 100 -> 4
            else -> 6
        }
    }

    companion object {
        private val modelCache = ConcurrentHashMap<String, VitsModel>()
        private val modelLock = Any()
        private val dispatchedLock = Any()

        private const val IDLE_RELEASE_MS = 120_000L   // 空闲 2 分钟自动释放（对标 NER）
        private val idleScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "sherpa-idle").apply { isDaemon = true }
        }
        private val idleStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 绑定/启动空闲释放巡查：周期检查缓存中空闲超时的模型并释放。 */
        fun attachIdleReaper() {
            if (!idleStarted.compareAndSet(false, true)) return
            idleScheduler.scheduleWithFixedDelay({
                try {
                    val now = android.os.SystemClock.elapsedRealtime()
                    for ((key, m) in modelCache) {
                        if (!m.disposed && now - m.lastUsedAt > IDLE_RELEASE_MS) {
                            MttsLog.d("Sherpa", "idle ${(now - m.lastUsedAt) / 1000}s > ${IDLE_RELEASE_MS / 1000}s → release ${m.dir.name}")
                            modelCache.remove(key, m)
                            m.dispose()
                        }
                    }
                } catch (_: Throwable) {}
            }, 30, 30, java.util.concurrent.TimeUnit.SECONDS)
        }

        /** 释放某模型（移除缓存 + 关闭 ORT session）。用于退出/切换。 */
        private fun releaseModel(m: VitsModel) {
            modelCache.entries.removeIf { it.value === m }
            m.dispose()
        }

        /** 释放全部已加载模型。 */
        private fun releaseAll() {
            val all = modelCache.values.toList()
            modelCache.clear()
            for (m in all) m.dispose()
        }

        /** 供下载/其他模块在内存紧张时主动释放全部已加载模型，腾出堆空间（对标 OOM 防护）。
         *  下载大模型前调用，避免多模型常驻（Kokoro 108MB+voices 53MB+Melo 51MB+词典）与下载缓冲叠爆堆。 */
        @JvmStatic
        fun releaseAllModels() {
            try { releaseAll() } catch (_: Throwable) {}
            MttsLog.d("Sherpa", "all models released (on-demand, free heap before download)")
        }
    }

    private fun acquireModel(dir: File, onnx: File): VitsModel {
        modelCache[onnx.absolutePath]?.let { m ->
            if (!m.disposed) { m.lastUsedAt = android.os.SystemClock.elapsedRealtime(); return m }
            modelCache.remove(onnx.absolutePath, m)
        }
        synchronized(modelLock) {
            modelCache[onnx.absolutePath]?.let { m ->
                if (!m.disposed) { m.lastUsedAt = android.os.SystemClock.elapsedRealtime(); return m }
                modelCache.remove(onnx.absolutePath, m)
            }
            MttsLog.d("Sherpa", "loading model ${onnx.name} (${onnx.length() / 1024 / 1024}MB)")
            val t0 = System.currentTimeMillis()
            val m = createModel(dir, onnx)
            MttsLog.d("Sherpa", "model loaded in ${System.currentTimeMillis() - t0}ms kind=${m.kind} sr=${m.sampleRate}")
            modelCache[onnx.absolutePath] = m
            return m
        }
    }

    private fun createModel(dir: File, onnx: File): VitsModel {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(useThreads(onnx))
        val session = env.createSession(onnx.absolutePath, opts)
        try {
            val inputNames = session.inputNames.toList()
            val hasTones = inputNames.any { it.equals("tones", true) }
            val isKokoro = inputNames.any { it.equals("style", true) || it.equals("tokens", true) }
            val isMatcha = !hasTones && !isKokoro &&
                inputNames.any { it.contains("x_length", true) }
            var sampleRate = if (hasTones) 44100 else 22050
            var speakerId = 0
            var addBlank = hasTones
            // 固定规则：MeloTTS(含tones) sr=44100 speaker_id=1 addBlank=true；Piper sr=22050 speaker_id=0 addBlank=false
            // （与 Linux 验证一致；onnxruntime Java metadata Optional 类型推断不可靠，不读）
            if (hasTones) {
                sampleRate = 44100; speakerId = 1; addBlank = true
            } else {
                sampleRate = 22050; speakerId = 0; addBlank = false
            }
            MttsLog.d("Sherpa", "meta sr=$sampleRate speaker_id=$speakerId add_blank=$addBlank inputs=$inputNames")

            if (isKokoro) {
                return createKokoroModel(dir, onnx, env, session, inputNames)
            }
            if (isMatcha) {
                return createMatchaModel(dir, onnx, env, session, inputNames)
            }

            val token2id = HashMap<String, Int>()
            tokensFile(dir)?.let { f ->
                f.forEachLine { line ->
                    val p = line.trim().split(Regex("\\s+"))
                    if (p.size >= 2) p[1].toIntOrNull()?.let { token2id[p[0]] = it }
                }
            }
            token2id.putIfAbsent(" ", token2id["_"] ?: 0)
            // 中英文标点互通（对齐 sherpa MeloTtsLexicon::InitTokens）：
            // ，。！？ ↔ , . ! ? 双向映射；、→，
            val punctPairs = listOf("," to "，", "." to "。", "!" to "！", "?" to "？")
            for ((en, zh) in punctPairs) {
                val enId = token2id[en]
                val zhId = token2id[zh]
                if (enId != null && zhId == null) token2id[zh] = enId
                if (zhId != null && enId == null) token2id[en] = zhId
            }
            if (!token2id.containsKey("、") && token2id["，"] != null) token2id["、"] = token2id["，"]!!
            val blankId = token2id["_"] ?: 0
            return VitsModel(dir, if (hasTones) ModelKind.MELO else ModelKind.PIPER,
                env, session, sampleRate, speakerId, addBlank, token2id, blankId)
        } catch (t: Throwable) {
            session.close()
            throw t
        }
    }

    /** Kokoro：输入 tokens/style/speed，输出 audio 波形。读 metadata(style_dim/n_speakers/sr)+voices.bin+lexicons。 */
    private fun createKokoroModel(
        dir: File, onnx: File, env: OrtEnvironment, session: OrtSession,
        inputNames: List<String>
    ): VitsModel {
        var sampleRate = 24000
        var nSpeakers = 103
        var styleDim0 = 510
        var styleDim2 = 256
        try {
            val md = session.metadata
            val custom = md.customMetadata
            custom["sample_rate"]?.toIntOrNull()?.let { sampleRate = it }
            custom["n_speakers"]?.toIntOrNull()?.let { nSpeakers = it }
            val sd = custom["style_dim"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            if (sd != null && sd.size >= 3) { styleDim0 = sd[0]; styleDim2 = sd[2] }
            MttsLog.d("Sherpa", "kokoro meta sr=$sampleRate n_speakers=$nSpeakers style_dim=[$styleDim0,1,$styleDim2]")
        } catch (t: Throwable) {
            MttsLog.w("Sherpa", "kokoro read metadata failed: ${t.message}, use defaults")
        }

        // voices.bin → styles [nSpeakers * styleDim0 * styleDim2] float
        var styles: FloatArray? = loadVoicesBin(dir, nSpeakers, styleDim0, styleDim2)

        // lexicons（中文 + 英文）→ 音素串
        val lexZh = loadLexicon(dir, "lexicon-zh.txt", exact = "lexicon-zh.txt")
        val lexEn = loadLexicon(dir, "lexicon-us-en.txt", exact = "lexicon-us-en.txt")

        // tokens.txt → token2id
        val token2id = HashMap<String, Int>()
        tokensFile(dir)?.let { f ->
            f.forEachLine { line ->
                val p = line.trim().split(Regex("\\s+"))
                if (p.size >= 2) p[1].toIntOrNull()?.let { token2id[p[0]] = it }
                else if (p.isNotEmpty()) token2id[" "] = p[0].toInt()
            }
        }
        if (!token2id.containsKey(" ") && token2id["_"] != null) token2id[" "] = token2id["_"]!!

        val blankId = token2id[" "] ?: 0
        return VitsModel(dir, ModelKind.KOKORO, env, session, sampleRate, 0, false,
            token2id, blankId,
            styles = styles, styleDim0 = styleDim0, styleDim2 = styleDim2,
            nSpeakers = nSpeakers, lexiconZh = lexZh, lexiconEn = lexEn)
    }

    /** 读取 voices.bin 复型为 [nSpeakers*styleDim0*styleDim2] float（小端）。流式分块读取，避免 53MB 一次性整体读的峰值内存。 */
    private fun loadVoicesBin(dir: File, n: Int, d0: Int, d2: Int): FloatArray? {
        val f = dir.listFiles { it -> it.name.startsWith("voices") && it.extension == "bin" }
            ?.firstOrNull() ?: return null
        return try {
            val expected = n * d0 * d2 * 4
            val fileLen = f.length()
            if (fileLen != expected.toLong()) {
                MttsLog.w("Sherpa", "voices.bin size $fileLen != expected $expected")
                // 试试不校验（可能 metadata 不同）
            }
            val floatCount = (fileLen / 4).toInt()
            val out = FloatArray(floatCount)
            val chunkFloats = 64 * 1024             // 每 64K float 块（256KB）、接着写
            var fi = 0
            java.io.BufferedInputStream(java.io.FileInputStream(f), 1 shl 16).use { `is` ->
                val buf4 = ByteArray(4)
                val chunk = FloatArray(chunkFloats)
                var idx = 0
                while (fi < floatCount) {
                    if (`is`.read(buf4, 0, 4) != 4) break
                    val b = buf4[0].toInt() and 0xFF
                    val b1 = buf4[1].toInt() and 0xFF
                    val b2 = buf4[2].toInt() and 0xFF
                    val b3 = buf4[3].toInt() and 0xFF
                    val bits = b or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                    chunk[idx++] = Float.fromBits(bits)
                    if (idx == chunkFloats) {
                        System.arraycopy(chunk, 0, out, fi, idx); fi += idx; idx = 0
                    }
                }
                if (idx > 0) { System.arraycopy(chunk, 0, out, fi, idx); fi += idx }
            }
            MttsLog.d("Sherpa", "voices.bin loaded ${out.size} floats")
            out
        } catch (t: Throwable) {
            MttsLog.w("Sherpa", "voices.bin load failed: ${t.message}")
            null
        }
    }

    /** 加载词表（汉字→音素 或 英文词→音素）。 */
    private fun loadLexicon(dir: File, pattern: String, exact: String): Map<String, List<String>> {
        val f = File(dir, exact)
        if (!f.isFile) return emptyMap()
        val map = HashMap<String, List<String>>()
        try {
            f.forEachLine { line ->
                val p = line.trim().split(Regex("\\s+"))
                if (p.size >= 2) map[p[0]] = p.subList(1, p.size)
            }
            MttsLog.d("Sherpa", "lexicon $exact loaded ${map.size} entries")
        } catch (t: Throwable) {
            MttsLog.w("Sherpa", "lexicon $exact load failed: ${t.message}")
        }
        return map
    }

    /** Matcha：mel 谱模型，输出 mel，需级联 Vocos 声码器（ISTFT）出波形。
     *  前端仍是中文 lexicon 音素 → token；pad_id 从 metadata 读取。 */
    private fun createMatchaModel(
        dir: File, onnx: File, env: OrtEnvironment, session: OrtSession,
        inputNames: List<String>
    ): VitsModel {
        var sampleRate = 22050
        var padId = 1
        var nFft = 1024; var hopLen = 256; var winLen = 1024
        // 主模型 metadata
        try {
            val md = session.metadata
            val custom = md.customMetadata
            custom["sample_rate"]?.toIntOrNull()?.let { sampleRate = it }
            custom["pad_id"]?.toIntOrNull()?.let { padId = it }
        } catch (t: Throwable) {
            MttsLog.w("Sherpa", "matcha read metadata failed: ${t.message}")
        }

        // vocos 声码器：同目录下找 vocos*.onnx
        var vocosSession: OrtSession? = null
        val vocosFile = dir.listFiles { f -> f.extension.equals("onnx", true) &&
            f.name.contains("vocos", true) }?.firstOrNull()
        if (vocosFile != null) {
            try {
                val vopts = OrtSession.SessionOptions()
                vopts.setIntraOpNumThreads(useThreads(vocosFile))
                vocosSession = env.createSession(vocosFile.absolutePath, vopts)
                val vmd = vocosSession!!.metadata
                val custom = vmd.customMetadata
                custom["n_fft"]?.toIntOrNull()?.let { nFft = it }
                custom["hop_length"]?.toIntOrNull()?.let { hopLen = it }
                custom["win_length"]?.toIntOrNull()?.let { winLen = it }
                custom["sample_rate"]?.toIntOrNull()?.let { if (it != 0) sampleRate = it }
                MttsLog.d("Sherpa", "matcha vocos loaded n_fft=$nFft hop=$hopLen win=$winLen sr=$sampleRate")
            } catch (t: Throwable) {
                MttsLog.w("Sherpa", "matcha vocos load failed: ${t.message}")
                try { vocosSession?.close() } catch (_: Throwable) {}
                vocosSession = null
            }
        } else {
            MttsLog.w("Sherpa", "matcha vocoder 未找到（需 vocos*.onnx）")
        }

        // tokens.txt → token2id
        val token2id = HashMap<String, Int>()
        tokensFile(dir)?.let { f ->
            f.forEachLine { line ->
                val p = line.trim().split(Regex("\\s+"))
                if (p.size >= 2) p[1].toIntOrNull()?.let { token2id[p[0]] = it }
                else if (p.isNotEmpty()) token2id[" "] = p[0].toInt()
            }
        }
        // lexicon（matcha-zh 用 lexicon.txt）
        val lex = loadLexicon(dir, "lexicon", exact = "lexicon.txt")

        return VitsModel(dir, ModelKind.MATCHA, env, session, sampleRate, 0, true,
            token2id, padId,
            lexiconZh = if (lex.isNotEmpty()) lex else null,
            vocosSession = vocosSession,
            nFft = nFft, hopLength = hopLen, winLength = winLen, padId = padId)
    }

    // ============ 前端：Matcha（lexicon → 音素 → token + add_blank）============
    /** 文本 → Matcha tokens（含 add_blank：每 token 前插 pad 并收尾 pad）。
     *  matcha-zh-baker 是单字 lexicon，逐字查；英文走英文词表（本项目中文模型），OOV 跳过。 */
    private fun matchaTextToIds(text: String, model: VitsModel): List<Int> {
        val lex = model.lexiconZh ?: emptyMap()
        val tok2id = model.token2id
        val pad = model.padId
        val ids = ArrayList<Int>()
        // 标点归一（对齐 sherpa ReplacePunctuations）
        val norm = kotlinNormalizePunct(text)
        var i = 0
        val n = norm.length
        while (i < n) {
            val c = norm[i]
            // 空格：静默跳过（中文模型无词间空格概念，注入空格 token 会孤立发声产生怪音）
            if (c == ' ') { i++; continue }
            // 标点直接映射
            if (c in ",.!?;:") {
                tok2id[c.toString()]?.let { ids.add(it) }
                i++; continue
            }
            if (isCjk(c)) {
                // 最长匹配中文词（lexicon 可能有双字/多字词）
                var found = false
                var ln = minOf(8, n - i)
                while (ln >= 1 && !found) {
                    val w = norm.substring(i, i + ln)
                    val phones = lex[w]
                    if (phones != null) {
                        for (p in phones) tok2id[p]?.let { ids.add(it) }
                        i += ln; found = true
                    } else ln--
                }
                if (!found) i++
                continue
            }
            // 非 CJK（字母/数字）——中文模型跳过 OOV
            i++
        }
        // add_blank：垫 pad 在前后及 token 之间
        val x = ArrayList<Int>()
        x.add(pad)
        for (t in ids) {
            x.add(t); x.add(pad)
        }
        return x
    }

    /** Kotlin 版标点归一（对齐 sherpa ReplacePunctuations）。 */
    private fun kotlinNormalizePunct(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '，','、','：' -> sb.append(if (ch == '：') ':' else ',')
                '。' -> sb.append('.')
                '！' -> sb.append('!')
                '？' -> sb.append('?')
                '；' -> sb.append(';')
                '…' -> sb.append("...")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Matcha 推理：x → mel → vocos → istft → 波形。 */
    private fun runMatcha(model: VitsModel, ids: List<Int>, speed: Float): FloatArray {
        val vocos = model.vocosSession
            ?: throw RuntimeException("Matcha 模型缺 Vocos 声码器")
        val env = model.env
        val xArr = ids.map { it.toLong() }.toLongArray()
        val len = xArr.size.toLong()
        // length_scale = 1/speed
        val lengthScale = (1f / speed).coerceIn(0.1f, 5f)
        var xT: OnnxTensor? = null; var xlT: OnnxTensor? = null
        var nsT: OnnxTensor? = null; var lsT: OnnxTensor? = null
        var melResult: OrtSession.Result? = null
        var melsT: OnnxTensor? = null
        var vocosResult: OrtSession.Result? = null
        try {
            // —— Matcha acoustic → mel ——
            xT = OnnxTensor.createTensor(env, LongBuffer.wrap(xArr), longArrayOf(1, len))
            xlT = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(len)), longArrayOf(1))
            nsT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.667f)), longArrayOf(1))
            lsT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(lengthScale)), longArrayOf(1))
            val mInputs = LinkedHashMap<String, OnnxTensor>()
            val mNames = model.session.inputNames.toList()
            val xName = mNames.firstOrNull { it.equals("x") } ?: mNames[0]
            val xlName = mNames.firstOrNull { it.contains("x_length", true) } ?: mNames[1]
            val nsName = mNames.firstOrNull { it.contains("noise", true) } ?: mNames.firstOrNull { it != xName && it != xlName } ?: mNames.last()
            val lsName = mNames.firstOrNull { it.contains("length_scale", true) } ?: mNames.firstOrNull { it != xName && it != xlName && it != nsName } ?: mNames.last()
            mInputs[xName] = xT; mInputs[xlName] = xlT
            mInputs[nsName] = nsT; mInputs[lsName] = lsT
            melResult = model.session.run(mInputs)
            // mel tensor [1, 80, L]
            val melTensor = melResult.get(0) as ai.onnxruntime.OnnxTensor
            val melShape = melTensor.info.shape
            val melSize = melShape.fold(1L) { a, b -> a * b }.toInt()
            val melBuf = FloatArray(melSize)
            val fb = melTensor.floatBuffer; fb.rewind(); fb.get(melBuf)
            melResult.close(); melResult = null
            // 关闭已用输入
            xT?.close(); xlT?.close(); nsT?.close(); lsT?.close()
            xT = null; xlT = null; nsT = null; lsT = null

            // —— Vocos：mel → mag/x/y ——
            val nCh = melShape[1].toInt()       // 80
            val nFrames = melSize / nCh
            melsT = OnnxTensor.createTensor(env, FloatBuffer.wrap(melBuf),
                longArrayOf(1, nCh.toLong(), nFrames.toLong()))
            val vNames = vocos.inputNames.toList()
            val melName = vNames.firstOrNull { it.contains("mel", true) } ?: vNames[0]
            vocosResult = vocos.run(mapOf(melName to melsT))
            melsT?.close(); melsT = null

            // —— ISTFT：mag/x/y → 波形 ——
            val magT = vocosResult.get(0) as ai.onnxruntime.OnnxTensor
            val xT2 = vocosResult.get(1) as ai.onnxruntime.OnnxTensor
            val yT2 = vocosResult.get(2) as ai.onnxruntime.OnnxTensor
            val bins = ((model.nFft / 2) + 1)  // 513
            val total = magT.info.shape.fold(1L) { a, b -> a * b }.toInt()
            val mag = FloatArray(total)
            val xs = FloatArray(total)
            val ys = FloatArray(total)
            val fbM = magT.floatBuffer; fbM.rewind(); fbM.get(mag)
            val fbX = xT2.floatBuffer; fbX.rewind(); fbX.get(xs)
            val fbY = yT2.floatBuffer; fbY.rewind(); fbY.get(ys)
            val wave = KokoroIstft.istft(mag, xs, ys, model.nFft, model.hopLength, model.winLength)
            return wave
        } finally {
            melResult?.close()
            vocosResult?.close()
            xT?.close(); xlT?.close(); nsT?.close(); lsT?.close()
            melsT?.close()
        }
    }

    // ============ 前端：MeloTTS（sherpa MeloTtsLexicon）============
    private class MeloDict(
        val word2ids: Map<String, Pair<List<String>, List<Int>>>
    )

    @Volatile private var meloDictCache: MutableMap<String, MeloDict> = HashMap()

    private fun meloDict(dir: File): MeloDict? {
        val lex = lexiconFile(dir) ?: return null
        meloDictCache[dir.absolutePath]?.let { return it }
        val word2ids = HashMap<String, Pair<List<String>, List<Int>>>()
        try {
            lex.forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 3) return@forEachLine
                val word = parts[0].lowercase()
                val rest = parts.drop(1)
                if (rest.size % 2 != 0) return@forEachLine
                val n = rest.size / 2
                val phones = rest.subList(0, n)
                val tones = rest.subList(n, rest.size).mapNotNull { it.toIntOrNull() }
                if (phones.size == tones.size && phones.isNotEmpty()) word2ids[word] = phones to tones
            }
        } catch (t: Throwable) {
            MttsLog.w("Sherpa", "melo lexicon load failed: ${t.message}")
            return null
        }
        if (word2ids.isEmpty()) return null
        val d = MeloDict(word2ids)
        meloDictCache[dir.absolutePath] = d
        return d
    }

    private fun splitUtf8(s: String): List<String> = s.map { it.toString() }

    private fun isAlphaOrPunct(ch: String): Boolean {
        val c = ch[0]
        return c.isLetter() ||
            (!c.isWhitespace() && !c.isLetterOrDigit() && (c.code < 0x4e00 || c.code > 0x9fff) && !c.isDigit())
    }

    /** PhraseMatcher 最长匹配（maxLen=10，字母/标点开头不匹配） */
    private fun phraseMatch(chars: List<String>, lexicon: Set<String>, maxLen: Int): List<String> {
        val phrases = ArrayList<String>()
        val n = chars.size
        var i = 0
        while (i < n) {
            var w = ""
            if (!isAlphaOrPunct(chars[i])) {
                var end = minOf(i + maxLen - 1, n - 1)
                while (end > i) {
                    val cand = chars.subList(i, end + 1).joinToString("")
                    if (isAlphaOrPunct(cand.last().toString())) { end--; continue }
                    if (cand in lexicon) { w = cand; i = end + 1; break }
                    end--
                }
            }
            if (w.isEmpty()) { w = chars[i]; i++ }
            phrases.add(w)
        }
        return phrases
    }

    private fun convertWordToIds(
        w: String, dict: MeloDict, token2id: Map<String, Int>
    ): Pair<List<String>, List<Int>> {
        dict.word2ids[w]?.let { return it }
        val tid = token2id[w]
        if (tid != null) return listOf(w) to listOf(0)
        val p = ArrayList<String>()
        val t = ArrayList<Int>()
        for (ch in splitUtf8(w)) {
            val e = dict.word2ids[ch]
            if (e != null) { p.addAll(e.first); t.addAll(e.second) }
        }
        return p to t
    }

    /** 文本 → MeloTTS (ids, tones)（未加 blank；标点走 token2id） */
    private fun meloTextToIds(text: String, dict: MeloDict, token2id: Map<String, Int>): Pair<List<Int>, List<Int>> {
        val chars = splitUtf8(text.lowercase())
        val phrases = phraseMatch(chars, dict.word2ids.keys, maxLen = 10)
        val ids = ArrayList<Int>()
        val tones = ArrayList<Int>()
        for (w in phrases) {
            val (p, t) = convertWordToIds(w, dict, token2id)
            for (i in p.indices) {
                val pid = token2id[p[i]]
                if (pid != null) { ids.add(pid); tones.add(t.getOrElse(i) { 0 }) }
            }
        }
        return ids to tones
    }

    // ============ 前端：Piper（逐字查 lexicon）============
    private class PiperDict(
        val charLexicon: Map<String, List<String>>
    )


    /** 英文字母 → 中文标准读音（按国内通用读法，可多字，逐字拆分查词典）。
     *  例如 M→"艾姆"、W→"达不溜"、R→"阿尔"、F→"艾弗"、L→"艾勒"、X→"艾克斯"、H→"艾尺"。
     *  所需汉字均已在 piper-chaowen/xiaoya 词典中确认存在。 */
    private val LETTER_TO_CN: Map<Char, String> = mapOf(
        'a' to "诶", 'b' to "比", 'c' to "西", 'd' to "迪", 'e' to "一", 'f' to "艾弗",
        'g' to "吉", 'h' to "艾尺", 'i' to "爱", 'j' to "杰", 'k' to "开", 'l' to "艾勒",
        'm' to "艾姆", 'n' to "恩", 'o' to "欧", 'p' to "皮", 'q' to "丘", 'r' to "阿尔",
        's' to "艾斯", 't' to "提", 'u' to "优", 'v' to "维", 'w' to "达不溜", 'x' to "艾克斯",
        'y' to "歪", 'z' to "贼"
    )

    @Volatile private var piperDictCache: MutableMap<String, PiperDict> = HashMap()

    private fun piperDict(dir: File): PiperDict? {
        val lex = lexiconFile(dir) ?: return null
        piperDictCache[dir.absolutePath]?.let { return it }
        val m = HashMap<String, List<String>>()
        try {
            lex.forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[0].length <= 2) m[parts[0].lowercase()] = parts.drop(1)
            }
        } catch (t: Throwable) {
            MttsLog.w("Sherpa", "piper lexicon load failed: ${t.message}")
            return null
        }
        if (m.isEmpty()) return null
        val d = PiperDict(m)
        piperDictCache[dir.absolutePath] = d
        return d
    }

    /** 文本 → Piper ids（^ + 音素 + $）。
     *  piper-chaowen 为纯中文模型（lexicon 只有汉字词条）：
     *   - 英文单词整体跳过（对齐 sherpa 的 Ignore OOV，避免逐字母当拼音念出怪音）
     *   - 数字串转中文读法（123 → 一二三）
     *   - 汉字查词典，标点直接映射 token */
    private fun piperTextToIds(text: String, dict: PiperDict, token2id: Map<String, Int>): List<Int> {
        val ids = ArrayList<Int>()
        token2id["^"]?.let { ids.add(it) }
        val lower = text.lowercase()
        var i = 0
        while (i < lower.length) {
            val ch = lower[i]
            val c = ch.toString()
            if (ch.isDigit()) {
                // 数字连读 → 中文数字逐位读
                val sb = StringBuilder()
                while (i < lower.length && lower[i].isDigit()) { sb.append(lower[i]); i++ }
                for (cc in numToChinese(sb.toString())) {
                    val ph = dict.charLexicon[cc.toString()]
                    if (ph != null) for (p in ph) token2id[p]?.let { ids.add(it) }
                }
                continue
            }
            if (ch.code < 128 && ch.isLetter()) {
                // 英文单词 → 逐字母转中文音近字，按中文逐字念出（TTS 标准做法："OK"→欧开、"TTS"→提提艾斯）
                // ★ 仅 ASCII 字母走这里；中文（U+4E00..）不能进此分支，
                //   Kotlin 的 Char.isLetter() 对汉字也返回 true，否则中文会被当英文逐字母丢弃而“只有英文”。
                val word = StringBuilder()
                while (i < lower.length && lower[i].code < 128 && lower[i].isLetter()) { word.append(lower[i]); i++ }
                for (cc in word.toString()) {
                    // 标准读音串（可多字，如 M→"艾姆"）：逐字拆开查词典，拼出标准字母读音
                    val cn = LETTER_TO_CN[cc] ?: continue
                    for (ch2 in cn) {
                        val ph = dict.charLexicon[ch2.toString()]
                        if (ph != null) for (p in ph) token2id[p]?.let { ids.add(it) }
                    }
                }
                continue
            }
            val phonemes = dict.charLexicon[c]
            if (phonemes != null) {
                for (ph in phonemes) token2id[ph]?.let { ids.add(it) }
            } else if (!ch.isWhitespace()) {
                // 标点直接映射（tokens 含 , . ! ? — 等）
                token2id[c]?.let { ids.add(it) }
            }
            i++
        }
        token2id["$"]?.let { ids.add(it) }
        return ids
    }

    /** 数字串 → 中文数字（123 → 一二三；0-9 逐位） */
    private fun numToChinese(s: String): String {
        val map = "0123456789".mapIndexed { idx, ch -> ch to "零一二三四五六七八九"[idx] }.toMap()
        return s.map { map[it] ?: it }.joinToString("")
    }

    // ============ 推理 ============
    private fun runOnnx(model: VitsModel, text: String): FloatArray {
        when (model.kind) {
            ModelKind.MELO -> {
                val dict = meloDict(model.dir) ?: throw RuntimeException("MeloTTS 缺 lexicon.txt: ${model.dir.path}")
                val (ids, tones) = meloTextToIds(text, dict, model.token2id)
                if (ids.isEmpty()) throw RuntimeException("MeloTTS 未产出 tokens")
                val x = addBlank(ids, model.blankId)
                val t = addBlank(tones, 0)
                MttsLog.d("Sherpa", "melo ids=${ids.size} afterBlank=${x.size}")
                return runMelo(model, x, t)
            }
            ModelKind.PIPER -> {
                val dict = piperDict(model.dir) ?: throw RuntimeException("Piper 缺 lexicon.txt: ${model.dir.path}")
                val ids = piperTextToIds(text, dict, model.token2id)
                if (ids.size <= 2) throw RuntimeException("Piper 未产出 tokens")
                MttsLog.d("Sherpa", "piper ids=${ids.size}")
                return runPiper(model, ids)
            }
            ModelKind.KOKORO -> {
                // 中文夹英文：优先整句跑（跨语言更好），失败才逐字兜底
                val ids = kokoroTextToIds(text, model)
                if (ids.isEmpty()) throw RuntimeException("Kokoro 未产出 tokens: $text")
                MttsLog.d("Sherpa", "kokoro ids=${ids.size} (含首尾 PAD)")
                return runKokoro(model, ids, 1.0f)
            }
            ModelKind.MATCHA -> {
                val ids = matchaTextToIds(text, model)
                if (ids.size <= 1) throw RuntimeException("Matcha 未产出 tokens: $text")
                MttsLog.d("Sherpa", "matcha ids=${ids.size} (含 blank)")
                return runMatcha(model, ids, 1.0f)
            }
        }
    }

    private fun addBlank(seq: List<Int>, blank: Int): IntArray {
        val buf = IntArray(seq.size * 2 + 1) { blank }
        var j = 1
        for (k in seq) { buf[j] = k; j += 2 }
        return buf
    }

    // ============ 前端：Kokoro（lexicon-zh/en → 音素 → token）============
    /** 中英文标点归一化为英文（对齐 sherpa ReplacePunctuations）。 */
    private fun kokoroNorm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '，','、','：' -> { sb.append(if (ch==':') ':' else ','); }
                '。' -> sb.append('.')
                '！' -> sb.append('!')
                '？' -> sb.append('?')
                '；' -> sb.append(';')
                '…' -> sb.append("...")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun isCjk(c: Char): Boolean {
        val o = c.code
        return (o in 0x4E00..0x9FFF) || (o in 0x3400..0x4DBF) || (o in 0x20000..0x2A6DF)
    }

    /** 切词：CJK 单字、字母/数字成词、空格、标点独立 token（对齐 sherpa TokenizeFromLexiconStr）。 */
    private fun kokoroSplitWords(s: String): List<String> {
        val words = ArrayList<String>()
        val buf = StringBuilder()
        val asciiPunct = ",.!?;:"
        fun flush() { if (buf.isNotEmpty()) { words.add(buf.toString()); buf.setLength(0) } }
        for (c in s) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { flush(); words.add(" ") }
            else if (c == '，' || c == '、' || c == '。' || c == '！' || c == '？' || c == '；' || c == '：' || c == '…') {
                flush()
                words.add(c.toString())
            } else if (asciiPunct.indexOf(c) >= 0 || isCjk(c)) { flush(); words.add(c.toString()) }
            else { buf.append(c) }
        }
        flush()
        return words
    }

    /** 构建最长匹配的关键词（CJK 连写无空格，英文词用空格拼）。 */
    private fun kokoroPhraseKey(words: List<String>, start: Int, end: Int): String {
        val sb = StringBuilder()
        for (j in start until end) {
            val w = words[j]
            if (j > start) {
                val prevCjk = sb.isNotEmpty() && isCjk(sb.last())
                val curCjk = w.isNotEmpty() && isCjk(w[0])
                if (!(prevCjk && curCjk)) sb.append(' ')
            }
            sb.append(w)
        }
        return sb.toString()
    }

    /** 文本 → Kokoro tokens（含首尾 PAD=0）。复刻 sherpa TokenizeFromLexiconStr。 */
    private fun kokoroTextToIds(text: String, model: VitsModel): List<Int> {
        val lexZh = model.lexiconZh ?: emptyMap()
        val lexEn = model.lexiconEn ?: emptyMap()
        val tok2id = model.token2id
        val norm = kokoroNorm(text)
        val words = kokoroSplitWords(norm)
        val ids = ArrayList<Int>()
        val asciiPunct = ",.!?;:"
        var i = 0
        val n = words.size
        // 中文标点已归一化，剩下统一处理
        while (i < n) {
            val w = words[i]
            if (w == " ") {
                tok2id[" "]?.let { ids.add(it) }
                i++; continue
            }
            if (w.length == 1 && asciiPunct.indexOf(w[0]) >= 0) {
                tok2id[w]?.let { ids.add(it) }
                i++; continue
            }
            // 最长匹配 lex_zh / lex_en
            var found = false
            val maxLen = minOf(10, n - i)
            var ln = maxLen
            while (ln >= 1 && !found) {
                val key = kokoroPhraseKey(words, i, i + ln)
                val phones = lexZh[key] ?: lexEn[key]
                if (phones != null) {
                    for (p in phones) tok2id[p]?.let { ids.add(it) }
                    i += ln; found = true
                }
                ln--
            }
            if (!found) {
                // CJK 单字兜底；英文 OOV 跳过（对齐 sherpa Ignore 行为）
                if (w.length == 1 && isCjk(w[0])) {
                    val phones = lexZh[w]
                    if (phones != null) for (p in phones) tok2id[p]?.let { ids.add(it) }
                }
                i++
            }
        }
        if (ids.isEmpty()) return emptyList()
        return listOf(0) + ids + listOf(0)  // PAD wrap
    }

    /** Kokoro 推理：tokens + style + speed → audio 波形。
     *  sid 取 model.speakerId（解析音色时设置），style 从 styles 表按 token 长取行。 */
    private fun runKokoro(model: VitsModel, ids: List<Int>, speed: Float): FloatArray {
        val env = model.env
        val styles = model.styles ?: throw RuntimeException("Kokoro 缺 voices.bin")
        val xArr = ids.map { it.toLong() }.toLongArray()
        val len = xArr.size.toLong()
        val tokLen = xArr.size - 2  // 去首尾 PAD
        val sid = model.speakerId.coerceIn(0, (model.nSpeakers - 1).coerceAtLeast(0))
        // style 表索引 = sid*styleDim0*styleDim2 + len*styleDim2
        val tokLenClamp = tokLen.coerceIn(0, model.styleDim0 - 1)
        val styleBase = sid * model.styleDim0 * model.styleDim2 + tokLenClamp * model.styleDim2
        val styleArr = FloatArray(model.styleDim2)
        try {
            System.arraycopy(styles, styleBase, styleArr, 0, model.styleDim2)
        } catch (t: Throwable) {
            throw RuntimeException("Kokoro style 索引越界 sid=$sid tokLen=$tokLenClamp base=$styleBase")
        }
        var xT: OnnxTensor? = null
        var styleT: OnnxTensor? = null
        var spdT: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            xT = OnnxTensor.createTensor(env, LongBuffer.wrap(xArr), longArrayOf(1, len))
            styleT = OnnxTensor.createTensor(env, FloatBuffer.wrap(styleArr), longArrayOf(1, model.styleDim2.toLong()))
            spdT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1))
            val inputs = LinkedHashMap<String, OnnxTensor>()
            val inNames = model.session.inputNames.toList()
            val xName = inNames.firstOrNull { it.contains("token", true) } ?: inNames[0]
            val styleName = inNames.firstOrNull { it.contains("style", true) } ?: inNames[1]
            val speedName = inNames.firstOrNull { it.contains("speed", true) } ?: inNames[2]
            inputs[xName] = xT; inputs[styleName] = styleT; inputs[speedName] = spdT
            result = model.session.run(inputs)
            // 第一个输出 = audio 波形
            val tensor = result.get(0) as ai.onnxruntime.OnnxTensor
            val buf = FloatArray(tensor.info.shape.fold(1L) { a, b -> a * b }.toInt())
            val fb = tensor.floatBuffer; fb.rewind(); fb.get(buf)
            return buf
        } finally {
            result?.close()
            xT?.close(); styleT?.close(); spdT?.close()
        }
    }

    private fun runMelo(model: VitsModel, x: IntArray, tones: IntArray): FloatArray {
        val env = model.env
        val len = x.size.toLong()
        var xT: OnnxTensor? = null; var lenT: OnnxTensor? = null; var tonesT: OnnxTensor? = null
        var sidT: OnnxTensor? = null; var nT: OnnxTensor? = null; var lT: OnnxTensor? = null; var nwT: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            xT = OnnxTensor.createTensor(env, LongBuffer.wrap(x.map { it.toLong() }.toLongArray()), longArrayOf(1, len))
            lenT = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(len)), longArrayOf(1))
            tonesT = OnnxTensor.createTensor(env, LongBuffer.wrap(tones.map { it.toLong() }.toLongArray()), longArrayOf(1, tones.size.toLong()))
            sidT = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(model.speakerId.toLong())), longArrayOf(1))
            nT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.667f)), longArrayOf(1))
            lT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1.0f)), longArrayOf(1))
            nwT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.8f)), longArrayOf(1))
            val inputs = LinkedHashMap<String, OnnxTensor>()
            inputs["x"] = xT; inputs["x_lengths"] = lenT; inputs["tones"] = tonesT
            inputs["sid"] = sidT; inputs["noise_scale"] = nT
            inputs["length_scale"] = lT; inputs["noise_scale_w"] = nwT
            result = model.session.run(inputs)
            return tensorToFloat(result.get(0))
        } finally {
            result?.close()
            xT?.close(); lenT?.close(); tonesT?.close()
            sidT?.close(); nT?.close(); lT?.close(); nwT?.close()
        }
    }

    private fun runPiper(model: VitsModel, ids: List<Int>): FloatArray {
        val env = model.env
        val xArr = ids.map { it.toLong() }.toLongArray()
        val len = xArr.size.toLong()
        var xT: OnnxTensor? = null; var lenT: OnnxTensor? = null; var scalesT: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            xT = OnnxTensor.createTensor(env, LongBuffer.wrap(xArr), longArrayOf(1, len))
            lenT = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(len)), longArrayOf(1))
            scalesT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.667f, 1.0f, 0.8f)), longArrayOf(3))
            val names = model.session.inputNames.toList()
            val xName = names.firstOrNull { it.contains("input", true) } ?: names.first()
            val lenName = names.firstOrNull { it.contains("length", true) } ?: names.firstOrNull { it != xName } ?: xName
            val scaleName = names.firstOrNull { it.contains("scale", true) } ?: names.firstOrNull { it != xName && it != lenName } ?: xName
            val inputs = LinkedHashMap<String, OnnxTensor>()
            inputs[xName] = xT; inputs[lenName] = lenT; inputs[scaleName] = scalesT
            result = model.session.run(inputs)
            return tensorToFloat(result.get(0))
        } finally {
            result?.close()
            xT?.close(); lenT?.close(); scalesT?.close()
        }
    }

    private fun tensorToFloat(t: ai.onnxruntime.OnnxValue): FloatArray {
        val tensor = t as ai.onnxruntime.OnnxTensor
        val size = tensor.info.shape.fold(1L) { a, b -> a * b }.toInt()
        val buf = FloatArray(size)
        val fb = tensor.floatBuffer
        fb.rewind()
        fb.get(buf)
        return buf
    }

    // ============ 音色列表 ============
    private fun parseVoices(dir: File, dirName: String): List<Voice> {
        val list = ArrayList<Voice>()
        val outRate = 24000
        val sj = speakersJson(dir)
        if (sj != null) {
            try {
                val json = org.json.JSONObject(sj.readText())
                val speakers = json.optJSONArray("speakers")
                if (speakers != null && speakers.length() > 0) {
                    for (i in 0 until speakers.length()) {
                        val name = speakers.optString(i)
                        list.add(Voice(
                            shortName = "$dirName::sid$i",
                            name = name.ifBlank { "说话人${i + 1}" },
                            locale = "zh-CN",
                            gender = guessGender(name),
                            sampleRate = outRate,
                            desc = "sherpa1.30 $dirName sid$i"
                        ))
                    }
                }
            } catch (t: Throwable) {
                MttsLog.w("Sherpa", "parse speakers json failed: ${t.message}")
            }
        }
        if (list.isEmpty()) {
            list.add(Voice(
                shortName = dirName,
                name = dirName,
                locale = "zh-CN",
                gender = "Female",
                sampleRate = outRate,
                desc = "sherpa1.30 VITS 单说话人"
            ))
        }
        return list
    }

    override suspend fun listVoices(): List<Voice> {
        val out = ArrayList<Voice>()
        for (dir in modelDirs()) {
            if (mainModel(dir) != null) out += parseVoices(dir, dir.name)
        }
        return out
    }

    // ============ 合成 ============
    override suspend fun synthesize(
        text: String, voice: String,
        speed: Float, volume: Float, pitch: Float
    ): ByteArray {
        MttsLog.d("Sherpa", "synthesize", mapOf("text" to text.take(20), "voice" to voice))
        attachIdleReaper()   // ★ 首次使用时启动空闲释放巡查
        val (dir, sid) = resolveVoice(voice)
        val onnx = mainModel(dir) ?: throw RuntimeException("sherpa 模型目录无 .onnx 文件: ${dir.path}")
        val model = acquireModel(dir, onnx)
        model.lastUsedAt = android.os.SystemClock.elapsedRealtime()
        // ★ 音色对应的 speaker id：仅 Kokoro 用 sid 选风格（voice 形如 xxx::sidN）；
        //   Melo/Piper 保留模型默认 speaker_id（Melo=1、Piper=0），
        //   否则 resolveVoice 对无 sid 前缀的 voice 返回 0，会把 Melo 默认 speaker_id=1 覆盖成 0 → 合成高频噪声/近静音。
        if (model.kind == ModelKind.KOKORO) model.speakerId = sid
        val sampleRate = model.sampleRate
        val spd = speed.coerceIn(0.1f, 5.0f)
        val unifiedRate = 24000
        val segments = splitSegments(text)
        val allPieces = ArrayList<ByteArray>(segments.size)
        for (seg in segments) {
            if (seg.isBlank()) continue
            try {
                val samples = if (spd != 1f) runOnnxWithSpeed(model, seg, spd) else runOnnx(model, seg)
                if (samples.isEmpty()) continue
                val pcm = floatToPcm16(samples)
                val pcm24k = if (sampleRate != unifiedRate)
                    com.mtts.app.util.PcmResampler.resample16BitMono(pcm, sampleRate, unifiedRate)
                else pcm
                if (pcm24k.isNotEmpty()) allPieces.add(pcm24k)
            } catch (t: Throwable) {
                if (segments.size == 1) throw RuntimeException("sherpa 合成失败: ${t.message}")
                MttsLog.w("Sherpa", "segment synth failed, skip: ${t.message}")
            }
        }
        if (allPieces.isEmpty()) throw RuntimeException("sherpa 合成未产出音频")
        val total = allPieces.sumOf { it.size }
        val buf = java.io.ByteArrayOutputStream(total)
        for (p in allPieces) buf.write(p)
        var pcm24kAll = buf.toByteArray()
        MttsLog.d("Sherpa", "synthesize ok", mapOf(
            "segs" to segments.size, "pcmBytes" to pcm24kAll.size,
            "srcRate" to sampleRate, "outRate" to unifiedRate))
        if (pitch != 0f) {
            val ratio = (1f + pitch).coerceIn(0.5f, 2.0f)
            if (ratio != 1f) {
                pcm24kAll = com.mtts.app.util.PcmResampler.pitchShift16BitMono(pcm24kAll, unifiedRate, ratio)
                MttsLog.d("Sherpa", "pitch shift applied x$ratio (24kHz domain)")
            }
        }
        val finalPcm = if (volume > 0f && volume != 1.0f)
            com.mtts.app.util.PcmResampler.gain16BitMono(pcm24kAll, volume)
        else pcm24kAll
        return if (finalPcm.isNotEmpty()) finalPcm else pcm24kAll
    }

    /** 变速合成：VITS length_scale = 1/speed */
    private fun runOnnxWithSpeed(model: VitsModel, text: String, speed: Float): FloatArray {
        if (model.kind == ModelKind.KOKORO) {
            // Kokoro 的 speed 作为模型输入（非 length_scale 倒数）
            val ids = kokoroTextToIds(text, model)
            if (ids.isEmpty()) throw RuntimeException("Kokoro 未产出 tokens")
            return runKokoro(model, ids, speed.coerceAtLeast(0.1f))
        }
        if (model.kind == ModelKind.MATCHA) {
            val ids = matchaTextToIds(text, model)
            if (ids.size <= 1) throw RuntimeException("Matcha 未产出 tokens")
            return runMatcha(model, ids, speed.coerceAtLeast(0.1f))
        }
        if (model.kind == ModelKind.MELO) {
            val dict = meloDict(model.dir) ?: throw RuntimeException("缺 lexicon")
            val (ids, tones) = meloTextToIds(text, dict, model.token2id)
            if (ids.isEmpty()) throw RuntimeException("未产出 tokens")
            val x = addBlank(ids, model.blankId)
            val t = addBlank(tones, 0)
            return runMeloScaled(model, x, t, 1f / speed)
        }
        val dict = piperDict(model.dir) ?: throw RuntimeException("缺 lexicon")
        val ids = piperTextToIds(text, dict, model.token2id)
        if (ids.size <= 2) throw RuntimeException("未产出 tokens")
        return runPiperScaled(model, ids, 1f / speed)
    }

    private fun runMeloScaled(model: VitsModel, x: IntArray, tones: IntArray, lengthScale: Float): FloatArray {
        val env = model.env
        val len = x.size.toLong()
        var xT: OnnxTensor? = null; var lenT: OnnxTensor? = null; var tonesT: OnnxTensor? = null
        var sidT: OnnxTensor? = null; var nT: OnnxTensor? = null; var lT: OnnxTensor? = null; var nwT: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            xT = OnnxTensor.createTensor(env, LongBuffer.wrap(x.map { it.toLong() }.toLongArray()), longArrayOf(1, len))
            lenT = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(len)), longArrayOf(1))
            tonesT = OnnxTensor.createTensor(env, LongBuffer.wrap(tones.map { it.toLong() }.toLongArray()), longArrayOf(1, tones.size.toLong()))
            sidT = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(model.speakerId.toLong())), longArrayOf(1))
            nT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.667f)), longArrayOf(1))
            lT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(lengthScale)), longArrayOf(1))
            nwT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.8f)), longArrayOf(1))
            val inputs = LinkedHashMap<String, OnnxTensor>()
            inputs["x"] = xT; inputs["x_lengths"] = lenT; inputs["tones"] = tonesT
            inputs["sid"] = sidT; inputs["noise_scale"] = nT
            inputs["length_scale"] = lT; inputs["noise_scale_w"] = nwT
            result = model.session.run(inputs)
            return tensorToFloat(result.get(0))
        } finally {
            result?.close()
            xT?.close(); lenT?.close(); tonesT?.close()
            sidT?.close(); nT?.close(); lT?.close(); nwT?.close()
        }
    }

    private fun runPiperScaled(model: VitsModel, ids: List<Int>, lengthScale: Float): FloatArray {
        val env = model.env
        val xArr = ids.map { it.toLong() }.toLongArray()
        val len = xArr.size.toLong()
        var xT: OnnxTensor? = null; var lenT: OnnxTensor? = null; var scalesT: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            xT = OnnxTensor.createTensor(env, LongBuffer.wrap(xArr), longArrayOf(1, len))
            lenT = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(len)), longArrayOf(1))
            scalesT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.667f, lengthScale, 0.8f)), longArrayOf(3))
            val names = model.session.inputNames.toList()
            val xName = names.firstOrNull { it.contains("input", true) } ?: names.first()
            val lenName = names.firstOrNull { it.contains("length", true) } ?: names.firstOrNull { it != xName } ?: xName
            val scaleName = names.firstOrNull { it.contains("scale", true) } ?: names.firstOrNull { it != xName && it != lenName } ?: xName
            val inputs = LinkedHashMap<String, OnnxTensor>()
            inputs[xName] = xT; inputs[lenName] = lenT; inputs[scaleName] = scalesT
            result = model.session.run(inputs)
            return tensorToFloat(result.get(0))
        } finally {
            result?.close()
            xT?.close(); lenT?.close(); scalesT?.close()
        }
    }

    /** 按句末标点/60字切分 */
    private fun splitSegments(text: String): List<String> {
        if (text.length <= 60) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder(80)
        for (ch in text) {
            sb.append(ch)
            if (sb.length >= 40 && (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n')) {
                out.add(sb.toString()); sb.setLength(0)
            } else if (sb.length >= 60) {
                out.add(sb.toString()); sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** 应用启动预热：提前加载模型 */
    fun warmUp() {
        val dirs = modelDirs()
        for (dir in dirs) {
            val onnx = mainModel(dir) ?: continue
            try {
                val m = acquireModel(dir, onnx)
                MttsLog.d("Sherpa", "warmUp done", mapOf(
                    "provider" to (if (useNnapi) "nnapi" else "cpu"),
                    "model" to dir.name,
                    "sampleRate" to m.sampleRate))
            } catch (t: Throwable) {
                MttsLog.w("Sherpa", "warmUp failed for ${dir.name}: ${t.message}")
            }
        }
    }

    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            out[i++] = (v and 0xFF).toByte()
            out[i++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun guessGender(name: String): String = when {
        name.contains("男") || name.contains("男声") || name.contains("Male") -> "Male"
        name.contains("女") || name.contains("女声") || name.contains("Female") -> "Female"
        else -> "Female"
    }

    override fun synthesizeStream(text: String, voice: String): Flow<AudioEvent> = flow {
        try {
            val data = synthesize(text, voice)
            if (data.isNotEmpty()) emit(AudioEvent.Chunk(data))
            emit(AudioEvent.TurnEnd)
        } catch (e: Exception) {
            emit(AudioEvent.Error(e.message ?: "合成失败"))
        }
    }.flowOn(Dispatchers.IO)
}
