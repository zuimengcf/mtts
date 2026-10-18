package com.mtts.app.engine

import com.baidu.tts.jni.ETtsError
import com.baidu.tts.jni.EmbeddedSynthesizerEngine
import com.baidu.tts.jni.EmbeddedSynthesizerEnginf
import com.baidu.tts.jni.EmbeddedSynthesizerEnginm
import com.baidu.tts.jni.IBaiduTtsEngine
import com.baidu.tts.jni.OnNewDataListener
import com.mtts.app.loader.NativeSoLoader
import com.mtts.app.util.MttsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 百度离线 TTS 引擎 —— 动态加载，不内置 .so。
 *
 * 引擎资源来自外部导入目录（Android/data/com.mtts.app/files/voice/bdetts）：
 *   lib/arm64-v8a/ 下的引擎库（libbd_etts/libbd_ettf/libbd_ettm + libBDSpeechDecoder_V1 + libc++_shared）
 *   6082724/ 90013/ 9294715/   模型目录（text.dat + licence.dat + acoustic 模型）
 *
 * 合成协议（已从 MultiTTS smali 逆向）：
 *  ① 按音色 param 首段选引擎变体：6082724→Engin(bd_etts)、90013→Enginm(bd_ettm)、9294715→Enginf(bd_ettf)
 *  ② 加载 .so → bdTTSSetNativeLogLevel → bdTTSEngineInit(appid, 模型路径C字节, ...) 获取句柄
 *  ③ 注册 OnNewDataListener 音频回调 → bdTTSSetParam/Float/String 设置参数
 *  ④ bdTTSSynthesis(GBK 文本) → native 调 newAudioDataCallback 产出 PCM
 *  ⑤ bdTTSEngineUninit 释放
 * 输出采样率：16000Hz / 16bit / 单声道。
 */
class OfflineBaiduEngine : TtsEngine {
    override val code = "bdetts"
    override val name = "百度TTS"
    override val category = EngineCategory.OFFLINE
    override val status = EngineStatus.READY

    /** 引擎资源目录（外部导入的 bdetts 目录：voice/bdetts） */
    private val engineRoot: File
        get() = VoicePackageManager.externalEngineDir("bdetts")
            ?: VoicePackageManager.engineDir("bdetts")
            ?: File("/sdcard/TTSVoice/bdetts")

    /** 已加载的库 key（防止重复 System.load） */
    private val loadedLibs = mutableSetOf<String>()

    private fun voices(): List<Voice> {
        val root = VoicePackageManager.root
        val ownCfg = root?.let { File(it, "bdetts/config.yaml") }
        val cfg = when {
            ownCfg != null && ownCfg.isFile -> ownCfg
            root != null -> File(root, "config.yaml")
            else -> return emptyList()
        }
        return VoicePackageConfig.parseSpeakers(cfg, "bdetts")
    }

    override suspend fun listVoices(): List<Voice> {
        val vs = voices()
        MttsLog.d("BdTTS", "listVoices", mapOf("count" to vs.size))
        return vs
    }

    override suspend fun synthesize(
        text: String, voice: String,
        speed: Float, volume: Float, pitch: Float
    ): ByteArray {
        MttsLog.d("BdTTS", "synthesize", mapOf("text" to text.take(20), "voice" to voice))
        val dir = engineRoot
        if (!dir.isDirectory) throw RuntimeException("外部百度语音包缺失: $dir (请导入)")

        val speaker = voices().firstOrNull { it.shortName == voice }
            ?: throw RuntimeException("百度音色不存在: $voice")

        // 解析 param（逗号分隔）：[0]=modelCode, [1]=appid, [2]=acoustic名, [3]=型号/speakerId, [4]=text变体(text_mand|text_cant)
        val parts = (speaker.param ?: "").split(",").map { it.trim() }
        val modelCode = parts.getOrNull(0)?.ifBlank { null } ?: "90013"
        val appId = parts.getOrNull(1)?.ifBlank { null }
        val acousticName = parts.getOrNull(2)?.ifBlank { null } ?: voice
        val speakerId = parts.getOrNull(3)?.ifBlank { null }
        val textVariant = parts.getOrNull(4)?.ifBlank { null }
        MttsLog.d("BdTTS", "speaker=$voice modelCode=$modelCode appid=$appId acoustic=$acousticName speakerId=$speakerId textVariant=$textVariant")

        // 选引擎变体
        val engine: IBaiduTtsEngine = when (modelCode) {
            "9294715" -> EmbeddedSynthesizerEnginf()
            "90013" -> EmbeddedSynthesizerEnginm()
            else -> EmbeddedSynthesizerEngine()
        }

        // 加载 .so（复制到内部 filesDir/lib 再 load，外部路径不可 dlopen）
        loadBaiduLibs(dir)

        // 模型目录与文件：text.dat 按 param[4] 变体选择（subgan_f13→text_cant.dat，其余默认）
        val modelDir = File(dir, modelCode)
        if (!modelDir.isDirectory) throw RuntimeException("百度模型目录缺失: $modelDir")
        val textDatFile = when (textVariant) {
            "text_mand" -> File(modelDir, "text_mand.dat")
            "text_cant" -> File(modelDir, "text_cant.dat")
            else -> File(modelDir, "text.dat")
        }
        val textDat = if (textDatFile.isFile) textDatFile else File(modelDir, "text.dat")
        if (!textDat.isFile) throw RuntimeException("百度 text.dat 缺失: $textDat")

        // acoustic：按 modelCode 分支匹配主声学/扩展/域文件
        val acousticDir = File(modelDir, "acoustic")
        var speechDat: File
        var speechExtDat: File?
        var domainDat: File?
        when (modelCode) {
            // 6082724 系（tacotron 双模型：tac 声学 + voc 声码器）：
            //   逆向 Q1/C.b()：tacotron 分支 D.r(speech)=B.f(tac声学)、D.s(speechExt)=B.e(subgan/voc).
            //   speech=tac 声学 <acousticName|voice>.ext.dat，speechExt=voc 声码器 <voice>.voc.dat。无 domain。
            "6082724" -> {
                val tacCands = listOf(
                    File(acousticDir, "$acousticName.ext.dat"),
                    File(acousticDir, "$voice.ext.dat"),
                    File(acousticDir, "tac_$voice.ext.dat"),
                ).firstOrNull { it.isFile }
                val vocCands = listOf(
                    File(acousticDir, "$voice.voc.dat"),
                    File(acousticDir, "$voice.dat"),
                ).firstOrNull { it.isFile }
                speechDat = tacCands ?: vocCands
                    ?: throw RuntimeException("百度 acoustic 缺失: $acousticDir (期望 $acousticName.ext.dat)")
                speechExtDat = if (tacCands != null) vocCands else null
                domainDat = null
            }
            // 90013/9294715 系：speech=<voice>.voc.dat, ext=第二候选(如有), domain=<voice>.res.dat
            else -> {
                val cands = listOf(
                    File(acousticDir, "$voice.voc.dat"),
                    File(acousticDir, "$voice.dat"),
                    File(acousticDir, "$voice.ext.dat"),
                    File(acousticDir, "tac_$voice.ext.dat"),
                ).filter { it.isFile }
                if (cands.isEmpty()) throw RuntimeException("百度 acoustic 缺失: $acousticDir 下找不到 $voice (可用: " +
                    (acousticDir.listFiles()?.map { it.name }?.take(10) ?: emptyList()).joinToString(", ") + ")")
                speechDat = cands[0]
                speechExtDat = cands.getOrNull(1)
                domainDat = listOf(
                    File(acousticDir, "$voice.res.dat"),
                    File(acousticDir, "$voice.domain.dat"),
                ).firstOrNull { it.isFile }
            }
        }
        MttsLog.d("BdTTS", "models: text=$textDat speech=${speechDat.name} ext=${speechExtDat?.name} domain=${domainDat?.name}")

        // appid：param[1] 或按 modelCode 取默认（O1/a 解密：90013→com.baidu.BaiduMap）；authorize 由 native bdTTSGetDatParam(模型路径) 返回的 JSON 解析
        val useAppId = appId ?: when (modelCode) {
            "6082724" -> "com.baidu.searchbox.tts.plugin"
            else -> "com.baidu.BaiduMap"   // 90013/9294715 默认
        }
        MttsLog.d("BdTTS", "resolve: modelDir=$modelDir text=$textDat speech=$speechDat ext=$speechExtDat appid=$useAppId")
        val authorize = readAuthorizeFromEngine(engine, textDat, speechDat)
        // 采样率：config 音色自带（90013=24000、6082724=16000）；缺省用引擎默认
        val sampleRate = speaker.sampleRate.takeIf { it > 0 } ?: 16000
        MttsLog.d("BdTTS", "sampleRate=$sampleRate")

        return synth(engine, modelDir, textDat, speechDat, speechExtDat, domainDat, acousticName, useAppId, modelCode, speakerId, authorize, sampleRate, text, speed, volume, pitch, speaker.speed, speaker.volume)
    }

    /** 执行一次完整合成，返回 PCM —— 严格对齐 Q1/C.a() 的 bdTTSEngineInit 参数 */
    private fun synth(
        engine: IBaiduTtsEngine,
        modelDir: File, textDat: File, speechDat: File, speechExtDat: File?, domainDat: File?,
        speakerName: String,
        appId: String, modelCode: String, speakerId: String?, authorize: String,
        sampleRate: Int, text: String, speed: Float, volume: Float, pitch: Float,
        speakerSpeed: Float, speakerVolume: Float
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val error = AtomicReference<String?>(null)
        val started = AtomicReference(false)

        engine.bdTTSSetNativeLogLevel(3)

        // 0) License 验证（复刻 Q1/y.a()：bdTTSVerifyLicense 后才能 bdTTSEngineInit）
        //   bdTTSVerifyLicense(appid, type, modelCode, appKey, appSecret, licensePath, outBuf)
        //   9294715/37111357 → type=2+内置密钥；default(6082724/90013) → type=1+key=cuid(随机UUID)+secret=""
        // License 文件：原程序 license 路径为 filesDir/baidu_tts_license，内容应来自模型目录各 modelCode 自带的 licence.dat
        // （模型 licence.dat 是 512B 的加密授权文件，verify 需读它；不能用空文件）
        val licenseFile = NativeSoLoader.privateLibDir?.parentFile?.let { File(it, "baidu_tts_license") }
        if (licenseFile != null) {
            runCatching {
                // 优先从模型目录复制对应 modelCode 的 licence.dat（modelDir 已是该 modelCode 的目录）
                val srcLic = File(modelDir, "licence.dat")
                if (srcLic.isFile && srcLic.length() > 0) {
                    srcLic.copyTo(licenseFile, overwrite = true)
                } else if (!licenseFile.exists()) {
                    licenseFile.createNewFile()
                }
            }
        }
        val licensePath = licenseFile?.absolutePath ?: ""
        val type: Int
        val appKey: String
        val appSecret: String
        when (modelCode) {
            "9294715", "37111357" -> {
                type = 2
                appKey = com.mtts.app.util.AppPrefs.getBdAppKey(modelCode)
                appSecret = com.mtts.app.util.AppPrefs.getBdAppSecret(modelCode)
                if (appKey.isBlank() || appSecret.isBlank()) {
                    throw RuntimeException("百度模型 $modelCode 需要在引擎设置中填写 appKey/appSecret")
                }
            }
            else -> { type = 1; appKey = java.util.UUID.randomUUID().toString(); appSecret = "" }  // cond_6: cuid
        }
        val outBuf = ByteArray(32)
        MttsLog.d("BdTTS", "verify args: appid=$appId type=$type modelCode=$modelCode key=$appKey secret=$appSecret license=$licensePath")
        runCatching {
            val li = engine.bdTTSVerifyLicense(appId, type, modelCode, appKey, appSecret, licensePath, outBuf)
            MttsLog.d("BdTTS", "verifyLicense ret=${li?.ret} time=${li?.time} pkg=${li?.packageName} appId=${li?.appId}")
        }.onFailure { MttsLog.w("BdTTS", "verifyLicense failed: ${it.message}") }

        // 音频回调
        engine.setOnNewDataListener(OnNewDataListener { data, len ->
            // newAudioDataCallback(data, len)：data 携带完整 PCM 块（data.size 为实际音频字节），len 为标记/帧数
            if (data != null && data.isNotEmpty()) {
                synchronized(out) { out.write(data, 0, data.size) }
            }
            0
        })

        var handle = -1L
        try {
            // 注：bdTTSBindCore(l.a=模型根目录) 在原程序 HandlerThread 启动时调用；l.a 为空时不 bind。
            //     实测 bdTTSBindCore(modelDir) ret=-1，说明该路径不是 bind 目标，跳过（非引擎初始化前置条件）。

            // 1) 初始化引擎，获取句柄
            // 复刻 Q1/C.a(false)：bdTTSEngineInit(appid, l(p)=text, l(r)=speech, l(s)=speechExt, l(q)=extra, authorize, handle)
            //   smali: v11=l(D.p)=text.dat, v12=l(D.r)=speech, v13=l(D.s)=speechExt(可空), v14=l(D.q)=extra
            val handleArr = LongArray(1)
            val textC = pathBytes(textDat.absolutePath)          // D.p → param2
            val speechC = pathBytes(speechDat.absolutePath)      // D.r → param3
            // D.s 为空时 smali 传 null（v14=v8=null），而非 byteArrayOf(0)
            val speechExtC: ByteArray? = if (speechExtDat != null) pathBytes(speechExtDat.absolutePath) else null  // D.s → param4
            val qC = byteArrayOf(0)   // D.q 空 → l("") = "\0"

            val e: ETtsError? = engine.bdTTSEngineInit(
                appId, textC, speechC, speechExtC, qC, authorize, handleArr)
            handle = handleArr[0]
            MttsLog.d("BdTTS", "engineInit ret=${e?.getRet()} msg=${e?.getMessage()} handle=$handle")
            if (e == null || e.getRet() != 0) {
                throw RuntimeException("百度引擎初始化失败 ret=${e?.getRet()} msg=${e?.getMessage()}")
            }
            if (handle == -1L) throw RuntimeException("百度引擎句柄无效")
            // 2.6) 设置 speaker/style（复刻 Q1/C.a() cond_4/cond_5：tacotron 需 model_speaker_id=param9、style=param 0xa）——
            //      必须在 PggInit 之前，否则 native 认为 speaker/style 未设置（tac_am_engine.cpp:279 FATAL）。
            //      config param[3] 即每个 6082724 音色的 speaker_id（如 sub_wjy→45）；style_id 用 0（默认）。
            if (modelCode == "6082724") {
                var sid = 0L
                if (speakerId != null && speakerId.isNotBlank()) {
                    sid = speakerId.toLongOrNull() ?: 0L
                }
                runCatching { engine.bdTTSSetParam(handle, 9, sid) }
                MttsLog.d("BdTTS", "setParam speaker(9)=$sid")
                runCatching { engine.bdTTSSetParam(handle, 0xa, 0L) }   // style_id（默认 0）
                MttsLog.d("BdTTS", "setParam style(0xa)=0")
            }
            // 2.7) PGG/GAN 声码器初始化（复刻 O1/b.a() cond_11）——
            //      tacotron 6082724 系：engineInit + setParam(speaker/style) 之后、synthesis 前必须调用 bdTTSEnginePggInit（否则 ret=8）。
            //      参数：JNI 只用到 byte[]（subgan 路径 C 字节）+ handle，第一参 appid 被 native 忽略。
            //      subgan 即 GAN 声码器（speechExtDat，如 sub_wjy.voc.dat）。
            if (modelCode == "6082724" && speechExtDat != null) {
                val pggRet = runCatching {
                    engine.bdTTSEnginePggInit(appId, pathBytes(speechExtDat.absolutePath), handle)
                }.getOrNull()
                MttsLog.d("BdTTS", "pggInit(appid=$appId subgan=${speechExtDat.name}) ret=$pggRet")
            }

            // 2.5) 加载域数据（复刻 O1/b：engineInit 成功后 bdTTSDomainDataInit(域文件内容, handle)；
            //      <speaker>.res.dat 是 BDETTSDOMAIN 域文件，必须加载后才能合成）
            if (domainDat != null && domainDat.isFile) {
                val domRet = runCatching {
                    // O1/b: bdTTSDomainDataInit(l(路径)=路径字节, handle)
                    engine.bdTTSDomainDataInit(pathBytes(domainDat.absolutePath), handle)
                }.getOrNull()
                MttsLog.d("BdTTS", "domainDataInit(${domainDat.name}) ret=$domRet")
            }

            // 2) 设置参数（复刻 Q1/A.a()：速度/音量/风格等）
            //   Q1/k 默认 a(速度)=b(音高)=c(音量)=5.0(f)（0-10 范围）；MultiTTS 对各厂商引擎用"5 居中"比例。
            //   6082724/90013 引擎实测 param=5.0 可正常合成；传入 config 比例(如 volume=1.0)会越界(ret=8)。
            //   现按语音包/用户设置的相对值映射：base(5.0) × rel，夹在 [0,10]。
            //   rel 来源优先级：合成时传入的 speed/volume/pitch → 未传(默认1.0/0.0)则用 config 音色自带值。
            //
            // ★ 50-50-25 基准统一：
            //   - 语速/音量：内部浮点（1.0=UI50 基准）→ param = 5.0 × rel（rel=1.0 → 5 中间值）
            //     用户设 speed=1.5（UI75）→ 7.5；speed=0.5（UI25）→ 2.5
            //   - 音调：内部浮点（0=UI25 基准）→ param = 5.0 + pitch×5.0（pitch=0 → 5 中间值）
            //     用户设 pitch=1.0（UI50）→ 10（最高）；pitch=-1.0（UI1）→ 0（最低）
            val relSpeed = if (speed > 0f && speed != 1.0f) speed else speakerSpeed
            // ★ 音量修复（HTTP 实测）：百度 SDK bdTTSSetParamFloat(handle,1,volF=5.0×rel) 完美线性
            //   （rel 0.4→volF2.0/peak 8711、1.8→volF9.0/peak 22402，单调递增无死区）。
            //   原代码 `volume != 1.0f ? volume : speakerVolume` 在用户未自定义音量（默认 1.0）时
            //   错误继承 config 音色自带 volume（如 sub_wjy 自带 2.0）→ 用户调 50%=1.0 实际听到 2.0 档，
            //   这才是"音量不按设置生效"的根因。现改为直接透传调用方 volume（effectiveVoiceParams
            //   已按 per-voice→全局→config 回退链给出最终值），彻底消除错误继承。
            val relVolume = if (volume > 0f) volume else 1.0f
            val relPitch = pitch
            fun baiduParam(rel: Float): Float = (5.0f * rel).coerceIn(0f, 10f)
            val volF = baiduParam(relVolume)
            val spdF = baiduParam(relSpeed)
            val pitF = ((5.0f + relPitch * 5.0f)).coerceIn(0f, 10f)
            MttsLog.d("BdTTS", "params speed=$relSpeed vol=$relVolume pitch=$relPitch -> spd=$spdF vol=$volF pit=$pitF")

            runCatching { engine.bdTTSSetParamFloat(handle, 1, volF) }   // c: 音量（0-10）
            runCatching { engine.bdTTSSetParamFloat(handle, 2, spdF) }   // a: 速度（0-10）
            runCatching { engine.bdTTSSetParamFloat(handle, 3, pitF) }   // b: 音高（0-10）
            runCatching { engine.bdTTSSetParamFloat(handle, 4, 5.0f) }   // D.z 默认音量
            runCatching { engine.bdTTSSetParam(handle, 0, 0L) }            // 复刻 A.a()：param 0
            runCatching { engine.bdTTSSetParam(handle, 5, sampleRate.toLong()) }  // param 5 = k.e = 样本率
            runCatching { engine.bdTTSSetParamString(handle, "emo", "normal") }
            runCatching { engine.bdTTSSetParamString(handle, "punc_silence_enum", "default") }
            runCatching { engine.bdTTSSetParamString(handle, "tts_off_resource_path", speechDat.parentFile?.parentFile?.absolutePath ?: "") }
            // param 7 / 0x14：对齐 MultiTTS 原版 Q1/D 构造函数默认值（A="1"→param7=1、B="0"→param0x14=0），
            //   所有引擎统一，不按 modelCode 区分。原版仅当 config TtsParams 显式覆盖时才改，
            //   而现有百度语音包 config 均未覆盖（param:null 或无对应字段），故统一用默认值。
            //   之前 90013 系设 param7=24000/param0x14=1 偏离原版 → 洛天依等 90013 voc 音色出现杂音。
            runCatching { engine.bdTTSSetParam(handle, 7, 1L) }
            runCatching { engine.bdTTSSetParam(handle, 0x14, 0L) }

            // 3) 文本转 GBK 字节 → 合成（bdTTSSynthesis(handle, gbkBytes, len, len)）—— 复刻 A.a()：无 NUL
            val textBytes: ByteArray = try {
                text.toByteArray(charset("GBK"))
            } catch (t: Throwable) {
                text.toByteArray(Charsets.UTF_8)
            }
            started.set(true)
            val synthErr = engine.bdTTSSynthesis(handle, textBytes, textBytes.size, 0)   // 复刻 A.a()：bdTTSSynthesis(J,[B,len,0)
            MttsLog.d("BdTTS", "synthesis ret=${synthErr?.getRet()} msg=${synthErr?.getMessage()}")
            if (synthErr != null && synthErr.getRet() != 0) {
                throw RuntimeException("百度合成失败 ret=${synthErr.getRet()} msg=${synthErr.getMessage()}")
            }

            // 4) bdTTSSynthesis 为阻塞式：返回即表示合成完成，音频回调已全部到达（复刻 Q1/A.a() 在 worker 线程同步调用）。
            //    仅等待回调数据落盘完成（通常 <100ms），最多 3s 兜底；不再无谓等满 20s。
            val bytesBefore = synchronized(out) { out.size() }
            awaitAudioQuiesce(out, bytesBefore, 3000L)
        } catch (e: Exception) {
            throw e
        } finally {
            runCatching { engine.bdTTSEngineUninit(handle) }
        }

        val audio = out.toByteArray()
        MttsLog.d("BdTTS", "synthesize ok bytes=${audio.size}")
        if (audio.isEmpty()) throw RuntimeException("百度合成未产出音频")
        return audio
    }

    /** 字符串 → 路径 C 字节（末尾 NUL），复刻 Q1/l->l(String) */
    private fun pathBytes(path: String): ByteArray {
        val bytes = path.toByteArray(Charsets.UTF_8)
        val ret = ByteArray(bytes.size + 1)
        System.arraycopy(bytes, 0, ret, 0, bytes.size)
        return ret
    }

    /**
     * 等待音频回调数据落盘完成：监听 out 缓冲大小不再增长即认为合成数据已收齐。
     * 适用于同步阻塞式 bdTTSSynthesis 已返回的场景（回调同步 flush），通常 <100ms。
     * 最多等待 maxMs 作为兜底，避免极端情况下卡死。
     */
    private fun awaitAudioQuiesce(out: ByteArrayOutputStream, bytesBefore: Int, maxMs: Long) {
        val deadline = System.currentTimeMillis() + maxMs
        var lastSize = bytesBefore
        while (System.currentTimeMillis() < deadline) {
            val cur = synchronized(out) { out.size() }
            if (cur == lastSize && cur > 0) {
                // 缓冲已停止增长：若无新增则视为完成（首次 cur>0/停顿即返回）
                MttsLog.d("BdTTS", "audio quiesce bytes=$cur")
                return
            }
            lastSize = cur
            try { Thread.sleep(20) } catch (t: InterruptedException) { return }
        }
        MttsLog.d("BdTTS", "audio quiesce timeout bytes=$lastSize")
    }

    /** 通过 native bdTTSGetDatParam(模型路径) 获取模型参数 JSON，解析 authorize 字段 */
    private fun readAuthorizeFromEngine(engine: IBaiduTtsEngine, textDat: File, speechDat: File): String {
        // MultiTTS 用 speech 路径调用 _bdTTSGetDatParam；text.dat 路径为备选
        for (f in listOf(speechDat, textDat)) {
            try {
                val raw = engine.bdTTSGetDatParam(f.absolutePath)
                MttsLog.d("BdTTS", "getDatParam(${f.name}) -> $raw")
                if (!raw.isNullOrBlank()) {
                    val auth = org.json.JSONObject(raw).optString("authorize", "")
                    if (auth.isNotBlank()) return auth
                }
            } catch (t: Throwable) {
                MttsLog.w("BdTTS", "getDatParam(${f.name}) failed: ${t.message}")
            }
        }
        return ""
    }

    /** 复制 .so 到 app 内部 filesDir/lib 并按依赖顺序加载 */
    private fun loadBaiduLibs(dir: File) {
        val libDir = NativeSoLoader.privateLibDir ?: throw RuntimeException("私有库目录不可用")
        libDir.mkdirs()
        val srcDir = File(dir, "lib/arm64-v8a")
        if (!srcDir.isDirectory) throw RuntimeException("百度引擎库目录缺失: $srcDir")
        val all = listOf(
            "libc++_shared.so", "libBDSpeechDecoder_V1.so",
            "libbd_etts.so", "libbd_ettm.so", "libbd_ettf.so")
        for (name in all) {
            val src = File(srcDir, name)
            if (src.isFile) src.copyTo(File(libDir, name), overwrite = true)
        }
        for (name in all) {
            val key = name.removePrefix("lib").removeSuffix(".so")
            synchronized(loadedLibs) { if (loadedLibs.contains(key)) return@synchronized }
            val f = File(libDir, name)
            if (!f.isFile) { MttsLog.w("BdTTS", "missing $name, skip"); continue }
            try {
                System.load(f.absolutePath)
                synchronized(loadedLibs) { loadedLibs.add(key) }
                MttsLog.d("BdTTS", "loaded $name")
            } catch (t: Throwable) {
                MttsLog.w("BdTTS", "load $name failed: ${t.message}")
            }
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
}