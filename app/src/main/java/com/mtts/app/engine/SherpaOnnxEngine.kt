package com.mtts.app.engine

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.mtts.app.util.MttsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * sherpa-onnx 离线 TTS 引擎（Next-gen Kaldi + onnxruntime，Apache-2.0）。
 *
 * 中文 VITS 模型（MeloTTS zh_en / fanchen-C / Piper 等），支持 NPU 加速：
 *  - provider="nnapi"：Android 通用 NPU 路径，SM8550 等骁龙设备走高通 HTP（QNN NNAPI driver），
 *    不支持的算子自动回退 CPU（NNAPI 子图分区），不会崩。
 *  - provider="cpu"：纯 CPU 推理（XNNPACK/默认），离线无网络。
 *
 * ★ 多模型目录（2026-10 改造）：voice/sherpa/<modelDirName>/ 每个子目录 = 一个独立模型。
 *   voice/sherpa/
 *     ├── piper-chaowen/       ← 目录名即音色短名（voice shortName）
 *     │     ├── *.onnx           模型（优先 int8 量化版，NNAPI 支持更好）
 *     │     ├── lexicon.txt      发音词典
 *     │     ├── tokens.txt       音素表
 *     │     ├── date.fst,number.fst...  （可选，ruleFsts 提升数字/日期朗读）
 *     │     └── G_*.json         说话人列表（多说话人模型，可选）
 *     └── piper-xiaoya/
 *
 * Voice 命名规则：
 *  - 单说话人模型（无 G_*.json）：1 个 voice，shortName = 目录名（如 "piper-chaowen"）
 *  - 多说话人模型（有 G_*.json）：N 个 voice，shortName = "<目录名>::sid<i>"（如 "fanchen-C::sid0"）
 *    —— 目录名加 :: 前缀避免与其他模型目录的 voice 冲突；纯 "sidN" 不再作为全局短名。
 *
 * 合成路由：synthesize(voice) 按 shortName 解析出模型目录 + sid。
 * 兼容旧数据：若 voice 无 "::" 分隔，视为 "第一个模型目录 + sidN"。
 */
class SherpaOnnxEngine : TtsEngine {

    override val code = "sherpa"
    override val name = "Sherpa离线"
    override val category = EngineCategory.OFFLINE
    override val status = EngineStatus.READY

    /**
     * 是否启用 NPU 加速（NNAPI）。
     * A/B 实测结论（fanchen-C 90 字长文本）：CPU 7.88s vs NNAPI 9.09s ——
     * VITS 生成式模型（LSTM/注意力/转置卷积）NNAPI 几乎全回退 CPU，叠加调度开销反而更慢。
     * 故默认 CPU（XNNPACK/onnxruntime 原生），NNAPI 保留可手动开启（部分算子仍可上 NPU）。
     */
    @Volatile
    var useNnapi: Boolean = false

    /** 引擎根目录：voice/sherpa（与外部导入/私有解压一致） */
    private val engineRoot: File?
        get() = VoicePackageManager.externalEngineDir("sherpa")
            ?: VoicePackageManager.engineDir("sherpa")

    /** 所有含 .onnx 的模型子目录（voice/sherpa/<modelName>/），按目录名排序（稳定顺序） */
    private fun modelDirs(): List<File> {
        val root = engineRoot ?: return emptyList()
        return root.listFiles { f ->
            f.isDirectory && f.listFiles { it.extension.equals("onnx", true) }?.isNotEmpty() == true
        }?.sortedBy { it.name } ?: emptyList()
    }

    /** 兼容旧引用：默认第一个模型目录（多说话人优先） */
    private val modelDir: File?
        get() {
            val dirs = modelDirs()
            if (dirs.isEmpty()) return null
            // 优先多说话人（G_*.json），否则第一个
            return dirs.firstOrNull { d ->
                d.listFiles { f -> f.name.startsWith("G_") && f.extension.equals("json", true) }?.isNotEmpty() == true
            } ?: dirs.first()
        }

    /** 解析 voice shortName → (模型目录, sid)。
     *  格式："<目录名>::sid<i>" 精确路由；无 "::" 视为默认第一个目录 + sidN（兼容旧数据）。 */
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
        // 兼容旧格式：纯 "sidN" → 默认第一个目录
        val dir = modelDir ?: throw RuntimeException("sherpa 模型目录缺失")
        val sid = voice.removePrefix("sid").toIntOrNull() ?: 0
        return dir to sid
    }

    /** 主模型（声学）选择：
     *  - Matcha：优先 model-steps-*.onnx 声学模型，**排除声码器 vocos-*.onnx**
     *  - Kokoro：model*.onnx（配合 voices.bin）
     *  - VITS：单 onnx，优先 int8 / fp16 量化版（NNAPI 支持更好） */
    private fun mainModel(dir: File): File? {
        val all = dir.listFiles { f -> f.extension.equals("onnx", true) }
            ?.sortedBy { it.name } ?: return null
        if (all.isEmpty()) return null
        if (modelType(dir) == "matcha") {
            // 声学在前、声码器在后：排除 vocos 后再排序
            val acoustic = all.filter { !it.name.contains("vocos", true) }
            if (acoustic.isNotEmpty()) return acoustic.first()
        }
        // VITS / Kokoro：优先量化版
        return all.sortedWith(compareBy(
            { !it.name.contains("int8", true) && !it.name.contains("fp16", true) },
            { it.name }
        )).first()
    }

    /** 发音词典：优先 lexicon.txt；否则按语言标记精确匹配（Kokoro 有 lexicon-zh/us-en/gb-en 多份，
     *  必须选与 lang 一致的，否则中文用英文词典会乱码）。preferLang 为空时按名排序取第一份。 */
    private fun lexiconFile(dir: File, preferLang: String? = null): File? {
        val exact = File(dir, "lexicon.txt")
        if (exact.isFile) return exact
        val cands = dir.listFiles { f ->
            f.isFile && f.name.startsWith("lexicon") && f.extension.equals("txt", true)
        } ?: return null
        if (cands.isEmpty()) return null
        if (preferLang != null) {
            // 精确匹配：lexicon-zh.txt / lexicon-us-en.txt
            cands.firstOrNull { it.name.contains("-$preferLang", true) }?.let { return it }
        }
        return cands.sortedBy { it.name }.first()
    }

    /** 说话人 json（G_*.json），解析多说话人音色 */
    private fun speakersJson(dir: File): File? =
        dir.listFiles { f -> f.name.startsWith("G_") && f.extension.equals("json", true) }?.firstOrNull()

    /** 多模型合成器缓存：模型未变则复用（OfflineTts 初始化较慢，多目录并存时各自常驻） */
    @Volatile private var ttsCache: MutableMap<String, OfflineTts> = HashMap()
    @Volatile private var ttsProvider: String = ""
    private val ttsInitLock = Any()

    // ==================== 音色列表 ====================

    /** 从 G_*.json 读说话人列表；无则单说话人。
     *  @param dirName 模型目录名，作为 voice shortName 前缀（多目录共存避免冲突） */
    private fun parseVoices(dir: File, onnx: File, dirName: String): List<Voice> {
        val list = ArrayList<Voice>()
        val sj = speakersJson(dir)
        // ★ 引擎输出统一为 24000Hz（synthesize 内已从模型真实采样率重采样到 24k），
        //   报告 24000 与播放端（AudioTrack/TtsService）一致，避免被当未知引擎按 24k 处理出错。
        val outRate = 24000
        if (sj != null) {
            try {
                val json = org.json.JSONObject(sj.readText())
                val speakers = json.optJSONArray("speakers")
                if (speakers != null && speakers.length() > 0) {
                    val mType = modelType(dir)
                    val fam = when (mType) {
                        "matcha" -> "Matcha"
                        "kokoro" -> "Kokoro"
                        else -> "VITS"
                    }
                    for (i in 0 until speakers.length()) {
                        val name = speakers.optString(i)
                        list.add(Voice(
                            shortName = "$dirName::sid$i",
                            name = name.ifBlank { "说话人${i + 1}" },
                            locale = "zh-CN",
                            gender = guessGender(name),
                            sampleRate = outRate,
                            desc = "sherpa-onnx $fam · $dirName · sid=$i"
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
                desc = "sherpa-onnx VITS · 单说话人"
            ))
        }
        return list
    }

    override suspend fun listVoices(): List<Voice> {
        val dirs = modelDirs()
        val out = ArrayList<Voice>()
        for (dir in dirs) {
            val onnx = mainModel(dir) ?: continue
            out += parseVoices(dir, onnx, dir.name)
        }
        return out
    }

    // ==================== 合成 ====================

    override suspend fun synthesize(
        text: String, voice: String,
        speed: Float, volume: Float, pitch: Float
    ): ByteArray {
        MttsLog.d("Sherpa", "synthesize", mapOf("text" to text.take(20), "voice" to voice))
        val (dir, sidRaw) = resolveVoice(voice)
        val onnx = mainModel(dir) ?: throw RuntimeException("sherpa 模型目录无 .onnx 文件: ${dir.path}")

        val synth = acquireTts(dir, onnx)
        val sampleRate = synth.getSampleRate()
        // voice → sid（无匹配默认 0）
        val sid = sidRaw
        val spd = speed.coerceIn(0.1f, 5.0f)
        // ★ 统一输出采样率：播放端（AudioTrack/TtsService/HTTP）基准 24000
        val unifiedRate = 24000

        // 长文本分片（按句子/标点，最多 60 字/片），缩短单次推理时延、避免长句卡顿
        val segments = splitSegments(text)
        val allPieces = ArrayList<ByteArray>(segments.size)
        val firstRate = sampleRate
        for (seg in segments) {
            if (seg.isBlank()) continue
            val audio = try {
                synth.generate(seg, sid, spd)
            } catch (t: Throwable) {
                if (segments.size == 1) throw RuntimeException("sherpa 合成失败: ${t.message}")
                MttsLog.w("Sherpa", "segment synth failed, skip: ${t.message}")
                continue
            }
            if (audio == null) continue
            val samples = audio.getSamples() ?: continue
            val pcm = floatToPcm16(samples)
            // ★ 采样率统一：模型真实采样率（如 MeloTTS 44100）→ 统一 24000（播放端基准）
            val pcm24k = if (sampleRate in 1..unifiedRate - 1 || sampleRate > unifiedRate)
                com.mtts.app.util.PcmResampler.resample16BitMono(pcm, sampleRate, unifiedRate)
            else pcm
            if (pcm24k.isNotEmpty()) allPieces.add(pcm24k)
        }
        if (allPieces.isEmpty()) throw RuntimeException("sherpa 合成未产出音频")

        // 拼接
        val total = allPieces.sumOf { it.size }
        val buf = java.io.ByteArrayOutputStream(total)
        for (p in allPieces) buf.write(p)
        var pcm24kAll = buf.toByteArray()
        MttsLog.d("Sherpa", "synthesize ok", mapOf(
            "segs" to segments.size, "pcmBytes" to pcm24kAll.size,
            "srcRate" to sampleRate, "outRate" to unifiedRate))

        // ★ 音高后处理（与搜狗引擎同方案）：sherpa-onnx JNI 无 pitch 参数，
        //   VITS/Matcha/Kokoro 均只支持 speed。pitch 偏移 -1..1 → 音高倍率 1+pitch
        //   （UI 25 基准=0 → ×1.0；UI 50 → ×1.5；UI 1 → ×0.5），在 24kHz 域做
        //   「不变速变调」，避免原始采样率域二次重采样的连锁失真。
        if (pitch != 0f) {
            val ratio = (1f + pitch).coerceIn(0.5f, 2.0f)
            if (ratio != 1f) {
                pcm24kAll = com.mtts.app.util.PcmResampler.pitchShift16BitMono(pcm24kAll, unifiedRate, ratio)
                MttsLog.d("Sherpa", "pitch shift applied x$ratio (24kHz domain)")
            }
        }

        // 音量调整（volume 通过增益补偿）
        val finalPcm = if (volume > 0f && volume != 1.0f)
            com.mtts.app.util.PcmResampler.gain16BitMono(pcm24kAll, volume)
        else pcm24kAll
        return if (finalPcm.isNotEmpty()) finalPcm else pcm24kAll
    }

    /** 按句末标点/60字限制切分，避免超长文本一次性推理卡顿 */
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

    /** 应用启动预热：提前加载所有模型并创建 OfflineTts（52MB onnx 初始化 5~15s，避免首次点击卡顿） */
    fun warmUp() {
        val dirs = modelDirs()
        for (dir in dirs) {
            val onnx = mainModel(dir) ?: continue
            try {
                val synth = acquireTts(dir, onnx)
                MttsLog.d("Sherpa", "warmUp done", mapOf(
                    "provider" to (if (useNnapi) "nnapi" else "cpu"),
                    "model" to dir.name,
                    "sampleRate" to synth.getSampleRate(),
                    "numSpeakers" to synth.getNumSpeakers()))
            } catch (t: Throwable) {
                MttsLog.w("Sherpa", "warmUp failed for ${dir.name}: ${t.message}")
            }
        }
    }

    /** 获取（或创建）合成器 —— 按模型路径缓存，多目录并存时各自常驻（切换零重建） */
    private fun acquireTts(dir: File, onnx: File): OfflineTts {
        val provider = if (useNnapi) "nnapi" else "cpu"
        val key = "${onnx.absolutePath}|$provider"
        val cached = ttsCache[key]
        if (cached != null) return cached
        synchronized(ttsInitLock) {
            ttsCache[key]?.let { return it }
            val t = createTts(dir, onnx, provider)
            ttsCache[key] = t
            ttsProvider = provider
            MttsLog.d("Sherpa", "tts created", mapOf(
                "provider" to provider,
                "model" to onnx.name,
                "sampleRate" to t.getSampleRate(),
                "numSpeakers" to t.getNumSpeakers()))
            return t
        }
    }

    /** 构建 OfflineTts 实例 —— 按目录内容自动识别模型族（Matcha / Kokoro / VITS）。
     *  识别依据（无额外 manifest，靠文件特征，避免导入时需手动配置）：
     *   - Matcha：目录内 ≥2 个 onnx（声学 model-steps-*.onnx + 声码器 vocos-*.onnx）
     *   - Kokoro：存在 voices.bin（说话人 embedding，VITS/Matcha 均无此文件）
     *   - 其余一律按 VITS（Piper / MeloTTS / fanchen-C）
     *  @param dir  模型目录
     *  @param acoustic 主模型（VITS 主 onnx 或 Matcha 声学模型），由调用方选定 */
    private fun createTts(dir: File, acoustic: File, provider: String): OfflineTts {
        require(acoustic.isFile) { "onnx 模型缺失: ${acoustic.path}" }
        val mType = modelType(dir)
        // Kokoro 有多份 lexicon（zh/us-en/gb-en），先定语言再选词典，避免误用英文词典读中文
        val lang = if (mType == "kokoro") kokoroLang(dir) else null
        // 词典路径统一转字符串，缺失传空串（sherpa 允许为空时按纯 token 处理）
        val lexiconPath = lexiconFile(dir, lang)?.takeIf { it.isFile }?.absolutePath ?: ""
        val tokens = File(dir, "tokens.txt")
        val dictDir = File(dir, "dict").takeIf { it.isDirectory }?.absolutePath ?: ""
        val tokensPath = if (tokens.isFile) tokens.absolutePath else ""
        var sub: Any
        if (mType == "matcha") {
            // Matcha = 声学（flow matching）+ 声码器（Vocos），两者都必填
            val vocoder = dir.listFiles { f ->
                f.extension.equals("onnx", true) && f.name.contains("vocos", true)
            }?.firstOrNull() ?: throw RuntimeException(
                "matcha 模型缺声码器 vocos-*.onnx: ${dir.path}")
            // data_dir：C++ 校验要求 data_dir 下必须存在 espeak-ng 四件套
            // （phontab/phonindex/phondata/intonations）。zh-baker 是纯拼音路线
            // （lexicon + jieba dict，无 espeak），绝不能传目录根；只有带
            // espeak-ng-data/ 子目录的模型（如 zh-en）才传，且指向该子目录。
            val espeakData = File(dir, "espeak-ng-data").takeIf {
                it.isDirectory && File(it, "phontab").isFile && File(it, "phonindex").isFile
            }
            sub = OfflineTtsMatchaModelConfig.builder()
                .setAcousticModel(acoustic.absolutePath)
                .setVocoder(vocoder.absolutePath)
                .setTokens(tokensPath)
                .setLexicon(lexiconPath)
                .setDataDir(espeakData?.absolutePath ?: "")
                .setDictDir(dictDir)
                .build()
        } else if (mType == "kokoro") {
            // Kokoro 需要 voices.bin（多说话人 embedding）+ 语言标记
            val voices = dir.listFiles { f ->
                f.name.equals("voices.bin", ignoreCase = true)
            }?.firstOrNull() ?: throw RuntimeException(
                "kokoro 模型缺 voices.bin: ${dir.path}")
            // data_dir 必填，C++ 校验要求其下含 espeak-ng 四件套（phontab/phonindex/phondata/intonations），
            // 必须指向 espeak-ng-data/ 子目录（官方 C++ 示例同款用法）
            val espeakData = File(dir, "espeak-ng-data").takeIf {
                it.isDirectory && File(it, "phontab").isFile && File(it, "phonindex").isFile
            } ?: throw RuntimeException("kokoro 模型缺 espeak-ng-data/: ${dir.path}")
            sub = OfflineTtsKokoroModelConfig.builder()
                .setModel(acoustic.absolutePath)
                .setVoices(voices.absolutePath)
                .setTokens(tokensPath)
                .setLexicon(kokoroLexicons(dir))
                .setLang(lang)
                .setDataDir(espeakData.absolutePath)
                .setDictDir(dictDir)
                .build()
        } else {
            sub = OfflineTtsVitsModelConfig.builder()
                .setModel(acoustic.absolutePath)
                .setTokens(tokensPath)
                .setLexicon(lexiconPath)
                .build()
        }

        val modelBuilder = OfflineTtsModelConfig.builder()
            .setNumThreads(6)
            .setDebug(false)
            .setProvider(provider)
        when (mType) {
            "matcha" -> modelBuilder.setMatcha(sub as OfflineTtsMatchaModelConfig)
            "kokoro" -> modelBuilder.setKokoro(sub as OfflineTtsKokoroModelConfig)
            else -> modelBuilder.setVits(sub as OfflineTtsVitsModelConfig)
        }

        val cfgBuilder = OfflineTtsConfig.builder()
            .setModel(modelBuilder.build())
            .setMaxNumSentences(1)
        // ruleFsts：date/number/phone 等（逗号分隔多文件）
        val fsts = dir.listFiles { f -> f.extension == "fst" }
            ?.sortedBy { it.name }?.joinToString(",") { it.absolutePath }.orEmpty()
        if (fsts.isNotEmpty()) cfgBuilder.setRuleFsts(fsts)
        return OfflineTts(cfgBuilder.build())
    }

    /** 模型族识别："matcha" / "kokoro" / "vits"。
     *  注意：不能用「onnx 数量 ≥2」判定 matcha —— 同模型的 int8+fp32 双版本也会是 2 个 onnx。
     *  必须要求存在声码器 vocos-*.onnx（Matcha 的强制特征，VITS/Kokoro 均无）。 */
    private fun modelType(dir: File): String {
        val files = dir.listFiles()?.filter { it.isFile } ?: return "vits"
        if (files.any { it.name.equals("voices.bin", ignoreCase = true) }) return "kokoro"
        if (files.any { it.extension.equals("onnx", true) && it.name.contains("vocos", true) })
            return "matcha"
        return "vits"
    }

    /** Kokoro 语言标记：目录内存在 lexicon-zh*.txt 视为中文，否则默认 en */
    private fun kokoroLang(dir: File): String =
        if (dir.listFiles { f -> f.name.matches(Regex("lexicon-zh.*\\.txt", RegexOption.IGNORE_CASE)) }?.isNotEmpty() == true)
            "zh" else "en"

    /** Kokoro 词典：目录下所有 lexicon-*.txt 逗号拼接（中英混读需多份，顺序无要求），
     *  如 lexicon-zh.txt,lexicon-us-en.txt,lexicon-gb-en.txt；无则空串 */
    private fun kokoroLexicons(dir: File): String {
        val list = dir.listFiles { f ->
            f.isFile && f.name.startsWith("lexicon") && f.extension.equals("txt", true)
        }?.sortedBy { it.name } ?: return ""
        return list.joinToString(",") { it.absolutePath }
    }

    /** FloatArray(-1..1) → 16bit 单声道 LE PCM */
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

    /** 从说话人名字猜性别（粗粒度，用于分组）：
     *  - Kokoro 前缀：zf_/af_/bf_/ef_/ff_/jf_/hf_/pf_/if_ = 女；zm_/am_/bm_/em_/jm_/hm_/pm_/im_ = 男
     *  - 其余按中文/英文关键词 */
    private fun guessGender(name: String): String = when {
        name.startsWith("zf_") || name.startsWith("af_") || name.startsWith("bf_") ||
            name.startsWith("ef_") || name.startsWith("ff_") || name.startsWith("jf_") ||
            name.startsWith("hf_") || name.startsWith("pf_") || name.startsWith("if_") -> "Female"
        name.startsWith("zm_") || name.startsWith("am_") || name.startsWith("bm_") ||
            name.startsWith("em_") || name.startsWith("jm_") || name.startsWith("hm_") ||
            name.startsWith("pm_") || name.startsWith("im_") -> "Male"
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