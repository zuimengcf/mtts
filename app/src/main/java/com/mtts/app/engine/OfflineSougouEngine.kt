package com.mtts.app.engine

import com.mtts.app.util.MttsLog
import com.sogou.speech.proxy.NativeProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 搜狗离线引擎 —— 双服务流水线（动态加载，不内置 .so）。
 *
 * 引擎资源来自外部导入目录（默认 /sdcard/TTSVoice/sougou）：
 *   lib/arm64-v8a 目录下的 .so 库          搜狗 SDK（libsgtts/libsgsplit/libsgproxy 等）
 *   dict_zh.dat / engdict.dat  前端词典
 *   acoustic/<voice>.bin       声学模型
 *
 * 合成协议（o3/b.smali 逆向，双服务）：
 *  ① 切分器 libsgsplit.so：write(整段文本) → read 切分文本片段
 *  ② 合成器 libsgtts.so：对每片段 write({config,text}) → read PCM 音频 → 拼接
 */
class OfflineSougouEngine : TtsEngine {
    override val code = "sougou"
    override val name = "搜狗离线"
    override val category = EngineCategory.OFFLINE
    override val status = EngineStatus.READY
    private val LANGUAGE = "zh-cmn-Hans-CN"

    /** 引擎资源目录（外部导入的 sougou 目录：voice/sougou） */
    private val engineRoot: File
        get() = VoicePackageManager.externalEngineDir("sougou")
            ?: VoicePackageManager.engineDir("sougou")
            ?: File("/sdcard/TTSVoice/sougou")

    /** 兜底音色（config.yaml 缺失/解析失败时使用） */
    private val fallbackVoices = listOf(
        Voice("lvxiaobing", "小冰", "zh-CN", "Female", "acoustic/lvxiaobing.bin"),
        Voice("xiyue", "夕月", "zh-CN", "Female", "acoustic/xiyue.bin"),
        Voice("yaxinpro", "雅馨", "zh-CN", "Female", "acoustic/yaxinpro.bin"),
        Voice("xm2", "熙木", "zh-CN", "Male", "acoustic/xm2.bin"),
        Voice("qingfeng", "清风", "zh-CN", "Male", "acoustic/qingfeng.bin"),
        Voice("amupro", "阿木", "zh-CN", "Male", "acoustic/amupro.bin"),
        Voice("lvxiaoran", "小冉", "zh-CN", "Male", "acoustic/lvxiaoran.bin"),
        Voice("guishu", "龟叔", "zh-CN", "Male", "acoustic/guishu.bin"),
        Voice("timo", "提莫", "zh-CN", "Male", "acoustic/timo.bin"),
        Voice("taotao", "淘淘", "zh-CN", "Male", "acoustic/taotao.bin"),
        Voice("yaxin", "雅欣", "zh-CN", "Female", "acoustic/yaxin.bin"),
        Voice("lvxiaobingx", "小冰x", "zh-CN", "Female", "acoustic/lvxiaobingx.bin"),
        Voice("gongziyanpro", "公子衍", "zh-CN", "Female", "acoustic/gongziyanpro.bin"),
        Voice("yefeiyepro", "叶非夜", "zh-CN", "Female", "acoustic/yefeiyepro.bin")
    )

    /**
     * 语音列表：优先从本引擎 config.yaml 动态解析；缺失时回退到内置兜底。
     *   voice/sougou/config.yaml ← 本引擎 config（导入时归位）
     *   兼容回退：voice/config.yaml 中 sougou 段
     */
    private fun voices(): List<Voice> {
        val root = VoicePackageManager.root
        val ownCfg = root?.let { File(it, "sougou/config.yaml") }
        val cfg = when {
            ownCfg != null && ownCfg.isFile -> ownCfg
            root != null -> File(root, "config.yaml")
            else -> return fallbackVoices
        }
        val parsed = VoicePackageConfig.parseSpeakers(cfg, "sougou")
        return if (parsed.isNotEmpty()) parsed else fallbackVoices
    }

    override suspend fun listVoices(): List<Voice> {
        val vs = voices()
        MttsLog.d("Sougou", "listVoices", mapOf("count" to vs.size))
        return vs
    }

    override suspend fun synthesize(
        text: String, voice: String,
        speed: Float, volume: Float, pitch: Float
    ): ByteArray {
        MttsLog.d("Sougou", "synthesize", mapOf("text" to text.take(30), "voice" to voice))
        // 从外部目录动态加载搜狗 SDK（不内置 .so）
        NativeProxy.loadLibraries()
        val dir = engineRoot
        if (!dir.isDirectory) throw RuntimeException("外部搜狗语音包缺失: $dir (请导入到 /sdcard/TTSVoice/sougou)")

        // 资源检查
        val dictZh = File(dir, "dict_zh.dat")
        val engDict = File(dir, "engdict.dat")
        val acoustic = File(dir, "acoustic/${voice}.bin")
        if (!dictZh.exists() || !engDict.exists() || !acoustic.exists()) {
            throw RuntimeException("搜狗语音包资源缺失: dict_zh=${dictZh.exists()}, engdict=${engDict.exists()}, acoustic/${voice}.bin=${acoustic.exists()}")
        }

        // 服务名：使用外部导入目录下已暂存到私有 nativeLibraryDir 的 .so 路径
        val libDir = com.mtts.app.loader.NativeSoLoader.privateLibDir
            ?: throw RuntimeException("私有库目录不可用")
        val splitterService = File(libDir, "libsgsplit.so").absolutePath
        val synthService = File(libDir, "libsgtts.so").absolutePath
        val frontModel = File(dir, "dict_zh.dat").absolutePath
        val backendModel = File(dir, "acoustic/${voice}.bin").absolutePath

        val splitter = NativeProxy()
        val synth = NativeProxy()
        var splitterHandle = 0L
        var synthHandle = 0L
        try {
            // ① 切分器
            val splitInit = JSONObject().put("enable_log", true).toString()
            MttsLog.d("Sougou", "splitter searchService", mapOf("serviceName" to splitterService))
            splitterHandle = splitter.searchService(splitterService, splitInit)
            if (splitterHandle == 0L) throw RuntimeException("切分器 searchService 失败")
            val splitConn = JSONObject().put("enable_log", false).put("frontModel", frontModel).toString().replace("\\", "")
            if (splitter.connect(splitConn) != 0) throw RuntimeException("切分器 connect 失败")
            val splitWrite = JSONObject().put("language", LANGUAGE).put("text", text).put("is_quote_mode", false).toString()
            if (splitter.write(splitWrite) != 0) throw RuntimeException("切分器 write 失败")
            val segments = ArrayList<String>()
            val outLen = IntArray(1)
            while (true) {
                val chunk = splitter.read(outLen)
                if (chunk == null || outLen[0] <= 0) break
                segments.add(String(chunk, Charsets.UTF_8))
                if (outLen[0] <= 0) break
            }
            MttsLog.d("Sougou", "splitter segments", mapOf("count" to segments.size))

            // ② 合成器
            val synthInit = JSONObject().put("enable_log", true).toString()
            MttsLog.d("Sougou", "synth searchService", mapOf("serviceName" to synthService))
            synthHandle = synth.searchService(synthService, synthInit)
            if (synthHandle == 0L) throw RuntimeException("合成器 searchService 失败")
            val synthConn = buildSynthConnect(frontModel, backendModel, engDict)
            if (synth.connect(synthConn) != 0) throw RuntimeException("合成器 connect 失败")

            val out = ByteArrayOutputStream()
            for (seg in segments) {
                val writeJson = buildSynthWrite(seg, speed, volume, pitch)
                if (synth.write(writeJson) != 0) continue
                val segOut = IntArray(1)
                while (true) {
                    val chunk = synth.read(segOut)
                    if (chunk == null || segOut[0] <= 0 || chunk.size <= 0) break
                    out.write(chunk, 0, chunk.size)
                    if (segOut[0] <= 0) break
                }
            }
            val audio = out.toByteArray()
            MttsLog.d("Sougou", "synthesize ok", mapOf("audioBytes" to audio.size))
            if (audio.isEmpty()) throw RuntimeException("合成器未产出音频")
            // ★ 音高修复（HTTP 实测 + F0 基频分析）：
            //   搜狗 SDK 合成 config 只支持 speaking_rate/volume（decoded_strings 证实无 pitch 字段），
            //   自造的 pitch_rate 被 SDK 忽略（pitch=-0.5/0/0.5/1.0 输出 F0 全为 280.7Hz 完全相同）。
            //   MultiTTS 原版搜狗同样无合成层 pitch（靠播放器 PlaybackParameters 处理）。
            //   现用 PCM 后处理「不变速变调」补偿：pitch 偏移 -1..1 → 音高倍率 1+pitch
            //   （UI 25 基准=0 → ×1.0；UI 50 → ×1.5；UI 1 → ×0.5）
            if (pitch != 0f) {
                val ratio = (1f + pitch).coerceIn(0.5f, 2.0f)
                val shifted = com.mtts.app.util.PcmResampler.pitchShift16BitMono(audio, 16000, ratio)
                MttsLog.d("Sougou", "pitch shift applied x${ratio} (${audio.size} -> ${shifted.size})")
                return shifted
            }
            return audio
        } finally {
            if (splitterHandle != 0L) { try { splitter.destroy(splitterService) } catch (_: Throwable) {} }
            if (synthHandle != 0L) { try { synth.destroy(synthService) } catch (_: Throwable) {} }
        }
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

    private fun buildSynthConnect(frontModel: String, backendModel: String, engDict: File): String {
        val json = JSONObject()
            .put("language", LANGUAGE)
            .put("frontModel", frontModel)
            .put("backendModel", backendModel)
            .put("vocoderModel", JSONObject.NULL)
        if (engDict.exists()) json.put("engDict", engDict.absolutePath)
        json.put("voiceSaveDirStr", JSONObject.NULL)
        return json.toString().replace("\\", "")
    }
    private fun buildSynthWrite(segment: String, speed: Float, volume: Float, pitch: Float): String {
        // ★ 50-50-25 基准对齐：内部浮点（speed=1.0/volume=1.0 = UI 50 基准）转成 MultiTTS UI 语义，
        //   再套 MultiTTS 原公式（ui 为 0-100，默认 50）：
        //     uiSpeed = speed×50 = 50 → speaking_rate = 50/66.7+0.5 ≈ 1.25（正常语速）
        //     uiVolume = volume×50 = 50 → vol = 50/50 = 1.0（正常音量）
        //   这样 UI 50=1.0x 基准下，搜狗语速/音量与 MultiTTS 完全一致。
        //   pitch 为内部浮点偏移（0=基准，-1..1，UI 25=基准）→ 映射为 SDK 的 pitch_rate：
        //     搜狗 SDK config 支持 speaking_rate/volume；pitch_rate 为近似音高控制，
        //     基准 pitch=0 → 0；±0.5 → ±25%（对齐微软 SSML pitch 百分比语义）
        val uiSpeed = speed * 50f
        val uiVolume = volume * 50f
        val speakingRate = (uiSpeed.toDouble() / 66.7) + 0.5
        // ★ 音量死区修复（HTTP 实测）：搜狗 SDK volume 有效区间为 [0.5, 2.0]，
        //   <0.5 会被 SDK 钳制回基线 1.0（UI 音量调到 25 以下完全无效果）。
        //   现把 <0.5 的值钳到 0.5（半音量档），保证低音量区间至少有效。
        val vol = (uiVolume / 50f).coerceAtLeast(0.5f).toDouble()
        val pitchRate = (pitch * 100).toInt().coerceIn(-100, 200)  // 百分数（0=基准）
        val config = JSONObject()
            .put("speaking_rate", speakingRate)
            .put("volume", vol)
            .put("pitch_rate", pitchRate)
            .put("do_split", false)
            .put("backend_thread_num", 0)
        return JSONObject().put("config", config).put("text", segment).toString()
    }
}
