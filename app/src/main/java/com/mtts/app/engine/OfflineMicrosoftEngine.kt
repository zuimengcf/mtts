package com.mtts.app.engine

import com.microsoft.cognitiveservices.speech.EmbeddedSpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import com.mtts.app.util.MttsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * 微软嵌入式语音引擎（Embedded Speech）—— 内置托底离线引擎。
 *
 * 基于官方 Speech SDK 的 EmbeddedSpeechConfig 驱动本地离线模型合成，
 * 完全离线、无需网络。复刻 MultiTTS `h3/a` 的嵌入式语音逻辑。
 *
 * 资源目录约定（导入到应用私有目录 Android/data/com.mtts.app/files/voice）：
 *   <voiceRoot>/microsoft/<voiceShortName>/   嵌入式语音模型（MSTTSLocZhCN.dat、am_v5_*.bin、device_vocoder_v6_streaming.bin 等）
 *
 * 合成：
 *   EmbeddedSpeechConfig.fromPath(voiceRoot/microsoft)  ← 父目录，SDK 扫描子目录模型
 *   setSpeechSynthesisVoice(shortName, license)         ← 音色 + license
 *   setSpeechSynthesisOutputFormat(Raw24Khz16BitMonoPcm)
 *   SpeechSynthesizer(config, null).SpeakSsmlAsync(ssml) → getAudioData() 读 PCM
 */
class OfflineMicrosoftEngine : TtsEngine {
    override val code = "microsoft"
    override val name = "微软嵌入式"
    override val category = EngineCategory.OFFLINE
    override val status = EngineStatus.READY

    /** 模型目录（fromPath 指向父目录，SDK 扫描其下每个音色子目录） */
    private val modelDir: File?
        get() = VoicePackageManager.root?.let { File(it, "microsoft") }

    /** 嵌入式语音 license（MultiTTS 嵌入式扩展签名，引擎运行必需；与控制引擎代码/音色列表硬编码无关） */
    private val embeddedLicense = "ZCjZ7nHDSLvf4gpELteM4AnzaWUjTpn7UkV7D@vvksl0w1SNgon6d1905WANbktDc9S39oaA4r29HJNayXvTq8fJsq"

    /** 合成器缓存：对齐 MultiTTS h3/a 字段 f —— 每音色一个 SpeechSynthesizer，创建一次、反复复用 */
    private val synthLock = Any()
    private var synthCache: SpeechSynthesizer? = null
    private var synthCacheVoice: String = ""

    /** 中文每段最长字符数（MultiTTS X4/e 分片：中文 60 字/段，其他 100 字/段） */
    private val SEGMENT_LIMIT_ZH = 60
    private val SEGMENT_LIMIT_OTHER = 100

    /**
     * 语音列表：从本引擎目录 config.yaml 动态解析（不硬编码）。
     * 语音包结构（多引擎各持一份 config）：
     *   voice/microsoft/config.yaml   ← 本引擎 config（导入时归位）
     *   兼容回退：voice/config.yaml 中 microsoft 段（旧单引擎布局）
     */
    private fun voices(): List<Voice> {
        val root = VoicePackageManager.root ?: return emptyList()
        val ownCfg = File(root, "microsoft/config.yaml")
        val cfg = if (ownCfg.isFile) ownCfg else File(root, "config.yaml")
        return VoicePackageConfig.parseSpeakers(cfg, "microsoft")
            // 只保留实际有模型目录的音色（导入的模型中存在的子目录）
            .filter { modelDir?.let { d -> File(d, it.shortName).isDirectory } ?: false }
    }

    override suspend fun listVoices(): List<Voice> {
        val vs = voices()
        MttsLog.d("MsEmbed", "listVoices", mapOf("count" to vs.size))
        return vs
    }

    override suspend fun synthesize(
        text: String, voice: String,
        speed: Float, volume: Float, pitch: Float
    ): ByteArray {
        MttsLog.d("MsEmbed", "synthesize", mapOf("text" to text.take(20), "voice" to voice))
        val voiceParam = resolveVoiceParam(voice)

        // 1) 长文本分片（对齐 MultiTTS X4/e）：中文 60 字/段、其他 100 字/段，
        //    逐段合成后拼接，避免超长文本合成失败/截断。
        val segments = splitSegments(text)
        val out = java.io.ByteArrayOutputStream()
        for (seg in segments) {
            val pcm = synthesizeOne(seg, voice, voiceParam, speed, volume, pitch)
            if (pcm.isNotEmpty()) out.write(pcm)
        }
        if (out.size() == 0) throw RuntimeException("微软嵌入式合成未产出音频")
        val audio = out.toByteArray()
        MttsLog.d("MsEmbed", "synthesize result total", mapOf(
            "segments" to segments.size, "audioBytes" to audio.size))
        return audio
    }

    /** 单段合成：复用缓存合成器（对齐 MultiTTS h3/a 字段 f） */
    private fun synthesizeOne(
        text: String, voice: String, voiceParam: String,
        speed: Float, volume: Float, pitch: Float
    ): ByteArray = synchronized(synthLock) {
        synthesizeOneLocked(text, voice, voiceParam, speed, volume, pitch)
    }

    /** 必须在 synthLock 内执行：覆盖合成器切换、native 调用、结果读取和缓存清理的完整生命周期。 */
    private fun synthesizeOneLocked(
        text: String, voice: String, voiceParam: String,
        speed: Float, volume: Float, pitch: Float
    ): ByteArray {
        var synth: SpeechSynthesizer? = null
        var resultToClose: com.microsoft.cognitiveservices.speech.SpeechSynthesisResult? = null
        try {
            // 复用合成器：音色一致则直接用缓存的，否则重建
            val cached = synthCache
            if (cached == null || synthCacheVoice != voice) {
                // 先摘除缓存再 close；若新实例构造失败，不能把已关闭实例留在缓存中。
                val old = synthCache
                synthCache = null
                synthCacheVoice = ""
                old?.let { try { it.close() } catch (_: Throwable) {} }
                val config = buildConfig(voice, voiceParam)
                synth = SpeechSynthesizer(config, null)
                synthCache = synth
                synthCacheVoice = voice
                MttsLog.d("MsEmbed", "synth created(cached)", mapOf("voiceParam" to voiceParam))
            } else {
                synth = cached
            }

            val ssml = buildSsml(voice, voiceParam, text, speed, volume, pitch)
            MttsLog.d("MsEmbed", "ssml", mapOf("len" to ssml.length))

            // ★ 对齐 MultiTTS h3/a：SpeakSsmlAsync().get() 后
            //   用 AudioDataStream.fromResult + readData 分批读完整 PCM。
            //   （直接 result.getAudioData() 在 embedded SDK 下只返回约 1/10 数据，
            //    audioDuration=3.06s 却只有 0.3s 对应字节 → 短台词几乎无声。）
            val result = synth.SpeakSsmlAsync(ssml).get()
            resultToClose = result
            if (result.reason == com.microsoft.cognitiveservices.speech.ResultReason.Canceled) {
                try {
                    val json = result.properties.getProperty(
                        com.microsoft.cognitiveservices.speech.PropertyId.SpeechServiceResponse_JsonResult)
                    MttsLog.d("MsEmbed", "synthesize canceled", mapOf("jsonResult" to json))
                } catch (_: Throwable) {}
            }

            val collected = java.io.ByteArrayOutputStream()
            var stream: com.microsoft.cognitiveservices.speech.AudioDataStream? = null
            try {
                stream = com.microsoft.cognitiveservices.speech.AudioDataStream.fromResult(result)
                stream!!.setPosition(0)
                val buf = ByteArray(0x12c0)   // MultiTTS 同款 4800 字节缓冲
                while (true) {
                    val n = stream!!.readData(buf)
                    if (n <= 0) break
                    collected.write(buf, 0, n.toInt())
                }
            } catch (e: Exception) {
                MttsLog.w("MsEmbed", "AudioDataStream read failed, fallback getAudioData: ${e.message}")
                collected.reset()
                collected.write(result.audioData)
            } finally {
                try { stream?.close() } catch (_: Throwable) {}
            }
            // 结果对象关闭后不能再读取其属性；先完整快照，再 close。
            val resultAudioLength = result.audioLength
            val resultAudioDuration = result.audioDuration
            val resultReason = result.reason?.toString()
            try { result.close() } catch (_: Throwable) {}
            resultToClose = null

            val audio = collected.toByteArray()
            // ★ 音量死区修复：SSML volume 只响应 ≤50%，>50% 的放大用 PCM 增益补偿
            //   （volume=1.0 默认 → SSML 50% + 增益 x2 达到原 100% 听感）
            val gain = if (volume > 0.5f) volume / 0.5f else 1.0f
            val finalAudio = if (gain > 1.0f && audio.isNotEmpty())
                com.mtts.app.util.PcmResampler.gain16BitMono(audio, gain)
            else audio
            // ★ 诊断：统计 PCM 振幅（峰值/非静音比例），判断合成数据是否含有效声音
            val ampDiag = if (finalAudio.size >= 2) {
                var peak = 0
                var nonZero = 0
                var total = 0
                for (i in 0 until finalAudio.size - 1 step 2) {
                    val s = (finalAudio[i].toInt() and 0xFF) or (finalAudio[i + 1].toInt() shl 8)
                    val a = if (s < 0) -s else s
                    if (a > peak) peak = a
                    if (a > 400) nonZero++   // >0.6% 满幅视为有效发声
                    total++
                }
                mapOf("peak" to peak, "targetMs" to (finalAudio.size * 1000L / 48000), "voicedRatio1000" to (nonZero * 1000L / total))
            } else null
            MttsLog.d("MsEmbed", "synthesize result", mapOf(
                "audioBytes" to finalAudio.size,
                "audioLength" to resultAudioLength,
                "audioDuration" to resultAudioDuration,
                "reason" to resultReason
            ) + (ampDiag ?: emptyMap()))
            return finalAudio
        } catch (e: Exception) {
            MttsLog.e("MsEmbed", "synthesize failed", e)
            try { resultToClose?.close() } catch (_: Throwable) {}
            // 合成失败时若用的是缓存合成器，丢弃缓存下次重建（可能缓存已损坏）
            if (synth == synthCache) {
                try { synthCache?.close() } catch (_: Throwable) {}
                synthCache = null
                synthCacheVoice = ""
            }
            throw RuntimeException("微软嵌入式合成失败: ${e.message}")
        }
    }

    /** 文本分片：中文按 60 字、其他按 100 字切段（对齐 MultiTTS X4/e） */
    private fun splitSegments(text: String): List<String> {
        if (text.length <= SEGMENT_LIMIT_ZH) return listOf(text)
        val limit = if (containsCjk(text)) SEGMENT_LIMIT_ZH else SEGMENT_LIMIT_OTHER
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + limit, text.length)
            out.add(text.substring(start, end))
            start = end
        }
        return out
    }

    private fun containsCjk(s: String): Boolean =
        s.any { it in '\u4e00'..'\u9fff' }

    /** 解析音色并校验模型目录，返回 voice param */
    private fun resolveVoiceParam(voice: String): String {
        val modelDir = modelDir ?: throw RuntimeException("微软嵌入式语音模型目录不可用")
        if (!modelDir.isDirectory) {
            throw RuntimeException("微软嵌入式语音模型缺失: $modelDir (请导入语音包)")
        }
        val voices = voices()
        val voiceShort = voice.ifBlank { voices.firstOrNull()?.shortName ?: "" }
        val voiceModel = File(modelDir, voiceShort)
        if (!voiceModel.isDirectory) {
            throw RuntimeException("微软嵌入式语音模型不存在: $voiceModel (可选: ${voices.joinToString { it.shortName }})")
        }
        val target = voices.firstOrNull { it.shortName == voiceShort }
        return target?.param?.ifBlank { target.shortName }
            ?: throw RuntimeException("config.yaml 中无音色 ${voiceShort} 的 param 定义")
    }

    /** 构建嵌入式语音配置（fromPath 指向具体音色子目录 + license + 24k PCM 输出） */
    private fun buildConfig(voice: String, voiceParam: String): EmbeddedSpeechConfig {
        val modelDir = modelDir ?: throw RuntimeException("微软嵌入式语音模型目录不可用")
        val voiceShort = voice.ifBlank { voices().firstOrNull()?.shortName ?: "" }
        val voiceModel = File(modelDir, voiceShort)
        val config = EmbeddedSpeechConfig.fromPath(voiceModel.absolutePath)
        config.setSpeechSynthesisVoice(voiceParam, embeddedLicense)
        config.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm)
        return config
    }

    private fun buildSsml(voiceShort: String, voiceParam: String, text: String, speed: Float, volume: Float, pitch: Float): String {
        // ★ 对齐 MultiTTS h3/a SSML：rate/volume 基准都是 ×100%（UI 50=1.0x）
        //   speed=1.0 → rate=100%；volume=1.0 → volume=100%；pitch=0 → pitch=0%
        //   内部浮点参数（1.0=基准）直接 ×100 转百分比
        val ratePct = (speed * 100).toInt().coerceIn(0, 400)
        // ★ 音量死区修复（HTTP 实测）：微软嵌入式 SDK 的 SSML volume 在 50% 即饱和，
        //   实测 volume=0.5/1.0/2.0 输出字节级完全相同（>50% 全部当最大音量）。
        //   故 SSML 只传 ≤50%（避免削波/饱和），>50% 的放大交给返回后的 PCM 增益补偿。
        val volPct = (minOf(volume, 0.5f) * 100).toInt().coerceIn(0, 50)
        val pitchPct = (pitch * 100).toInt().coerceIn(-100, 200)
        // xml:lang 从发音人 code 提取（如 zh-CN-XiaoxiaoNeural → zh-CN；en-US-... → en-US）
        val lang = extractLang(voiceShort)
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return String.format(
            java.util.Locale.ROOT,
            "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"%s\"><voice name=\"%s\"><prosody rate=\"%d%%\" pitch=\"%d%%\" volume=\"%d%%\">%s</prosody></voice></speak>",
            lang, voiceShort, ratePct, pitchPct, volPct, escaped
        )
    }

    /** 从发音人 code 提取语言标签：找第二个 '-' 之前的部分（zh-CN-XiaoxiaoNeural → zh-CN） */
    private fun extractLang(voiceShort: String): String {
        val parts = voiceShort.split('-')
        return if (parts.size >= 2) "${parts[0]}-${parts[1]}" else "zh-CN"
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
