package com.mtts.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
// WifiLock 已移除（离线引擎不需要网络）
import android.os.Build
import android.os.PowerManager
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import androidx.core.app.ServiceCompat
import com.mtts.app.R
import com.mtts.app.engine.EngineRegistry
import com.mtts.app.engine.TtsEngine
import com.mtts.app.ui.MainActivity
import com.mtts.app.util.MttsLog
import com.mtts.app.util.PcmResampler
import com.mtts.app.util.TextPreprocessor
import com.mtts.app.util.AppPrefs
import com.mtts.app.util.SoundEffectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 系统级 TTS 输出服务 —— 把 mtts 注册为 Android 全局语音合成引擎。
 *
 * 参考 MultiTTS 的 TTSService：继承 [android.speech.tts.TextToSpeechService]，
 * 实现 onSynthesizeText 把文本交给内置微软嵌入式离线引擎合成 PCM，
 * 再经 SynthesisCallback 交给系统 TextToSpeech 框架播放。
 *
 * 于是任何 App（阅读器/导航/输入法等）只要调用
 * android.speech.tts.TextToSpeech，把默认引擎切到 "mtts"，
 * 就能离线调用微软音色，无需网络、无需各自授权。
 *
 * 输出格式：微软引擎固定 Raw24Khz16BitMonoPcm（24000Hz / 16bit / 单声道）。
 *
 * 系统可调用性关键点（对齐 MultiTTS TTSService）：
 *  - onIsValidVoiceName / onLoadVoice：系统 setVoice/绑定音色时校验，缺省会判定音色无效
 *  - startForeground：声明了 foregroundServiceType=mediaPlayback，startService 拉起须前台化
 */
class TtsService : TextToSpeechService() {

    private val TAG = "Tts"
    // 合成请求自增序号：每条请求一个 #N，贯穿其日志，便于按请求追踪生命周期
    private val reqCounter = java.util.concurrent.atomic.AtomicInteger(0)
    // 串行合成队列：所有合成请求依序执行，绝不并发/互相打断（修复连续请求导致的中断失败）
    // @Volatile 可变引用：onStop() 需要能安全替换为新建队列，丢弃积压任务
    @Volatile
    private var synthExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mtts-tts-synth").apply { isDaemon = true }
    }
    // 停止标记：外部 App 停止朗读/翻页（onStop）后置 true，当前任务在段边界快速退出
    @Volatile
    private var stoppedFlag = false

    // ★ 段合成共享线程池（原每段新建 SingleThreadExecutor 且不关闭 → 线程泄漏）
    private val synthPool = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "mtts-seg-synth").apply { isDaemon = true }
    }
    // ★ 代际号：onStop() 递增。每个任务捕获自己启动时的代际，写块/段边界校验代际未变，
    //   彻底封死"旧任务卡在段内合成、新请求已把 stoppedFlag 复位"的竞态 → 关闭后不再继续朗读。
    @Volatile
    private var generation = 0

    // ★ 发热修复：WakeLock 按需获取（合成开始 acquire、完成/停止/异常 release）。
    //   WifiLock 已移除：所有引擎均为离线引擎，合成不需要网络，WiFi 锁纯属白白阻止 WiFi 休眠。
    private var wakeLock: PowerManager.WakeLock? = null

    // ★ BGM：独立 MediaPlayer（与语音 PCM 管线解耦，系统混音器并行输出）。
    //   原逐句 PCM 混入方案会在首段混音时同步整首解码（44.1kHz 源可拖 18 秒+），
    //   直接卡死输出线程 → 外部调用第一句长时间无声。
    @Volatile
    private var bgmPlayer: android.media.MediaPlayer? = null

    /** 启动独立 BGM 播放（已在播则复用，不重启——外部朗读每句一次请求，跨句必须连续） */
    private fun startBgmPlayer() {
        if (!AppPrefs.isBgmEnabled()) { stopBgmPlayer(); return }
        val path = AppPrefs.getBgmPath()
        if (path.isBlank()) { stopBgmPlayer(); return }
        // 已在播放且文件未变 → 直接复用
        bgmPlayer?.let { return }
        try {
            val mp = android.media.MediaPlayer()
            mp.setDataSource(path)
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.isLooping = true
            val vol = AppPrefs.getBgmVolume()
            mp.setVolume(vol, vol)
            mp.prepare()
            mp.start()
            bgmPlayer = mp
            MttsLog.d(TAG, "bgm player start $path")
        } catch (t: Throwable) {
            MttsLog.e(TAG, "bgm player failed: ${t.message}")
            try { bgmPlayer?.release() } catch (_: Throwable) {}
            bgmPlayer = null
        }
    }

    /** 停止并释放独立 BGM 播放 */
    private fun stopBgmPlayer() {
        val mp = bgmPlayer ?: return
        bgmPlayer = null
        try { mp.stop() } catch (_: Throwable) {}
        try { mp.release() } catch (_: Throwable) {}
        MttsLog.d(TAG, "bgm player stop")
    }

    companion object {
        private const val CHANNEL_ID = "mtts_tts_service"
        private const val NOTIF_ID = 0x54445453 // 'TDTS'
        // 前台服务类型：媒体播放（合成输出被当作媒体播放）
        private const val FGS_TYPE_MEDIA_PLAYBACK =
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        // ★ WakeLock 超时 5 分钟（单次合成最多几十秒，5 分钟足够兜底；旧值 20 分钟过长）
        private const val WAKE_LOCK_TIMEOUT_MS = 300_000L
    }

    /** 当前已注册的全部引擎（微软内置恒在 + 已导入的外部语音包引擎） */
    private val engines: List<TtsEngine>
        get() = EngineRegistry.all()

    /**
     * 解析音色名 → (引擎, shortName)。
     * 支持两种 voiceName 格式：
     *  - "engineCode:shortName"（如 "sougou:yaxin"）—— 多引擎显式指定
     *  - "shortName"（如 "zh-CN-YunxiNeural"）—— 系统 TTS 常见无前缀格式，
     *    在所有引擎中按 shortName 匹配，命中唯一则用之，否则回退微软默认。
     */
    private fun resolveVoice(voiceName: String): Pair<TtsEngine?, String> {
        if (voiceName.isBlank()) return Pair(null, "")
        if (voiceName.contains(':')) {
            val code = voiceName.substringBefore(':')
            val short = voiceName.substringAfter(':')
            val eng = engines.firstOrNull { it.code == code }
            if (eng != null) return Pair(eng, short)
            // code 不匹配则当作纯 shortName 处理
        }
        // 无前缀：匹配所有引擎的 shortName（走音色缓存，避免每次全量枚举）
        val matches = engines.filter { e ->
            runCatching { com.mtts.app.util.TtsVoicesCache.get(e).any { it.shortName == voiceName } }.getOrDefault(false)
        }
        return Pair(matches.firstOrNull(), voiceName)
    }

    /** 生成指定毫秒的静音 PCM（24kHz / 16bit / 单声道，值为 0） */
    private fun silencePcm(ms: Int): ByteArray {
        if (ms <= 0) return ByteArray(0)
        val bytes = (24000 * 2 * ms / 1000).coerceAtLeast(0)
        return ByteArray(bytes) // 全 0 = 静音
    }

    /** PCM16 小端 ByteArray → ShortArray（混音用） */
    private fun ByteArrayToShorts(b: ByteArray): ShortArray {
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val s = ShortArray(b.size / 2)
        bb.asShortBuffer().get(s)
        return s
    }

    /** ShortArray → PCM16 小端 ByteArray */
    private fun ShortsToBytes(s: ShortArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(s.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(s)
        return bb.array()
    }

    /** 引擎默认合成采样率（微软 24k / 搜狗 16k / 其余按缓存首音色，缺省 24k）——兜底用 */
    private fun engineSampleRate(engine: TtsEngine?): Int {
        if (engine == null) return 24000
        return when (engine.code) {
            "sougou" -> 16000
            "microsoft" -> 24000
            else -> runCatching {
                com.mtts.app.util.TtsVoicesCache.get(engine).firstOrNull()?.sampleRate
                    .takeIf { it != null && it > 0 } ?: 24000
            }.getOrDefault(24000)
        }
    }

    /**
     * 按「具体音色」取合成采样率。
     *
     * ★ 修复百度杂音根因：百度同一引擎内不同音色采样率不同
     *   （90013 系=24kHz、6082724 系=16kHz），且每次合成用的音色不定。
     *   若用「引擎第一个音色」的采样率做重采样判断，实际播放的是另一支
     *   不同采样率的音色时，会误判 segRate==24000 而跳过重采样，
     *   把 16kHz 数据当晚 24kHz 直交系统 → 音调偏高/字节错位 → 听感杂音。
     *   这里精确匹配当前音色：命中音色的 sampleRate>0 用之，否则回退引擎默认。
     */
    private fun voiceSampleRate(engine: TtsEngine?, voiceName: String): Int {
        if (engine == null) return 24000
        val cached = com.mtts.app.util.TtsVoicesCache.get(engine)
        val hit = cached.firstOrNull { it.shortName == voiceName }?.sampleRate
        if (hit != null && hit > 0) return hit
        return engineSampleRate(engine)
    }

    // ---------------- 生命周期：前台服务 + 休眠锁（对齐 MultiTTS） ----------------

    override fun onCreate() {
        super.onCreate()
        MttsLog.d(TAG, "onCreate")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "MTTS 语音合成", NotificationManager.IMPORTANCE_LOW)
                )
            }
        } catch (t: Throwable) {
            MttsLog.w(TAG, "create channel failed: ${t.message}")
        }
        // ★ 发热修复：不在 onCreate 无条件获取锁，改为按需在合成时获取
        // acquireLocks()
        // 前台化（对齐 MultiTTS 在 onCreate 完成 startForeground，而非 onStartCommand）
        startInForeground()
    }

    /**
     * 系统 TTS 引擎由框架通过 bindService(ACTION_TTS_SERVICE) 调用；
     * 生命周期与前台化交由父类 TextToSpeechService 管理（官方标准实现）。
     * 这里不覆盖返回常量，保持框架默认行为，避免与应用启动竞争/卡顿。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MttsLog.d(TAG, "onStartCommand", mapOf("action" to (intent?.action ?: "null")))
        // 收到「退出」动作：完全退出（停 HTTP + 停保活 + 落盘 + 杀进程），不再转发给保活服务
        // （保活服务可能未在运行，靠 PendingIntent 临时拉起不可靠，这里就地处理）
        if (intent?.action == KeepAliveService.ACTION_EXIT) {
            MttsLog.i(TAG, "exit action received, full shutdown")
            try { com.mtts.app.server.LocalHttpServer.stop() } catch (_: Throwable) {}
            try { stopService(Intent(this, KeepAliveService::class.java)) } catch (_: Throwable) {}
            com.mtts.app.util.AppPrefs.setKeepAliveEnabledSync(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Thread {
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
                try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Throwable) {}
            }.apply {
                name = "mtts-tts-exit-killer"
                isDaemon = false
            }.start()
            return START_NOT_STICKY
        }
        // 若进程曾被粘性重建（框架对 TTS 引擎的标准行为），重新确保前台化与锁
        startInForeground()
        return super.onStartCommand(intent, flags, startId)
    }

    /** 获取 CPU 锁（PARTIAL_WAKE_LOCK，合成期间防止 CPU 休眠） */
    private fun acquireLocks() {
        try {
            // 已持有则先释放再获取（防重复 acquire 泄漏）
            wakeLock?.takeIf { it.isHeld }?.release()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mtts:tts").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (t: Throwable) {
            MttsLog.w(TAG, "wakeLock acquire failed: ${t.message}")
        }
    }

    /** 释放 CPU 锁 */
    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun startInForeground() {
        try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+：startForeground 需显式声明前台服务类型
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notif, FGS_TYPE_MEDIA_PLAYBACK
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29-33：支持带类型的前台化（媒体播放）
                startForeground(NOTIF_ID, notif, FGS_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            MttsLog.d(TAG, "foreground started")
        } catch (t: Throwable) {
            MttsLog.w(TAG, "startForeground failed: ${t.message}")
        }
    }

    override fun onDestroy() {
        MttsLog.d(TAG, "onDestroy")
        releaseLocks()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        // 释放串行合成队列（允许正在执行的任务自然完成，丢弃排队中的新任务）
        synthExecutor.shutdown()
        synthPool.shutdownNow()
        stopBgmPlayer()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder: Notification.Builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, CHANNEL_ID)
            else
                Notification.Builder(this)
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MTTS 语音合成")
            .setContentText("系统 TTS 引擎服务运行中")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "退出", exitIntent())
            .build()
    }

    /** 「退出」按钮：发给本服务处理（停 HTTP + 停保活 + 落盘 + 杀进程） */
    private fun exitIntent(): PendingIntent {
        val i = Intent(this, TtsService::class.java)
            .setAction(KeepAliveService.ACTION_EXIT)
        return PendingIntent.getService(this, 11, i, PendingIntent.FLAG_IMMUTABLE)
    }

    // ---------------- 系统 TTS 引擎回调 ----------------

    override fun onIsLanguageAvailable(lang: String, country: String, variant: String): Int =
        TextToSpeech.LANG_AVAILABLE

    override fun onLoadLanguage(lang: String, country: String, variant: String): Int =
        TextToSpeech.LANG_AVAILABLE

    override fun onGetLanguage(): Array<String> = arrayOf("zh", "CN", "")

    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String): String {
        // 默认音色：微软引擎第一个可用音色；缺省回退 zh-CN-YunxiNeural（走音色缓存）
        val microsoft = engines.firstOrNull { it.code == "microsoft" }
        val first = microsoft?.let { com.mtts.app.util.TtsVoicesCache.get(it).firstOrNull()?.shortName }
        return first ?: "zh-CN-YunxiNeural"
    }

    override fun onGetVoices(): MutableList<Voice> {
        val result = mutableListOf<Voice>()
        if (engines.isEmpty()) {
            MttsLog.w(TAG, "no engine registered")
            return result
        }
        for (engine in engines) {
            try {
                val voices = com.mtts.app.util.TtsVoicesCache.get(engine)
                voices.forEach { v ->
                    val locale = try {
                        Locale.forLanguageTag(v.locale.ifBlank { "zh-CN" })
                    } catch (_: Exception) {
                        Locale.CHINA
                    }
                    // 特征：离线本地合成（无需网络）
                    val features = setOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)
                    // 音色名带引擎前缀，便于系统 TTS 精确路由到对应引擎：
                    //   "engineCode:shortName"（如 "sougou:yaxin"）
                    // 也保留裸 shortName 作为无前缀回退（兼容外部 App 直接 setVoice("yaxin")）
                    result.add(
                        Voice(
                            "${engine.code}:${v.shortName}",
                            locale,
                            Voice.QUALITY_HIGH,
                            Voice.LATENCY_NORMAL,
                            false,               // 不需网络连接
                            features
                        )
                    )
                    // 仅当 bare shortName 未被占用时补充裸名，避免歧义
                    if (result.none { it.name == v.shortName }) {
                        result.add(
                            Voice(
                                v.shortName,
                                locale,
                                Voice.QUALITY_HIGH,
                                Voice.LATENCY_NORMAL,
                                false,
                                features
                            )
                        )
                    }
                }
                MttsLog.d(TAG, "onGetVoices engine=${engine.code} count=${voices.size}")
            } catch (e: Exception) {
                MttsLog.e(TAG, "onGetVoices failed engine=${engine.code}", e)
            }
        }
        MttsLog.d(TAG, "onGetVoices total=${result.size}")
        return result
    }

    override fun onGetFeaturesForLanguage(lang: String, country: String, variant: String): Set<String> =
        setOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)

    /** 校验音色名是否有效（对齐 MultiTTS）：系统 setVoice/绑定音色时调用，缺失返回 -1 致音色无效 */
    override fun onIsValidVoiceName(voiceName: String): Int {
        if (engines.isEmpty()) {
            MttsLog.w(TAG, "onIsValidVoiceName: no engine, name=$voiceName")
            return TextToSpeech.ERROR
        }
        val (engine, _) = resolveVoice(voiceName)
        val ok = engine != null
        MttsLog.d(TAG, "onIsValidVoiceName", mapOf("name" to voiceName, "engine" to (engine?.code ?: "none"), "ok" to ok))
        return if (ok) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    /** 加载音色（对齐 MultiTTS）：返回音色有效性 */
    override fun onLoadVoice(voiceName: String): Int = onIsValidVoiceName(voiceName)

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        if (engines.isEmpty()) {
            MttsLog.e(TAG, "synthesize: no engine registered")
            if (callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) callback.error()
            return
        }

        val rawText = request.charSequenceText?.toString() ?: ""
        if (rawText.isBlank()) {
            if (callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) callback.error()
            return
        }

        // ★ 轻量参数提取（纯内存操作，框架线程立即返回，不阻塞系统 TTS 调度）。
        //   耗时处理（音色路由 / 预处理 / 对话分段 / 合成）全部在 synthExecutor 工作线程执行，
        //   避免外部 App speak() 等待 onStart 回调超时 → 整套文本重发 → 队列积压 → 听感"循环读"。
        val rawVoiceName = request.voiceName?.takeIf { it.isNotBlank() } ?: ""
        val rate = request.speechRate
        val volumeRaw = request.params?.getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME) ?: 100f
        val pitchRaw = request.pitch
        val useExternal = AppPrefs.isExternalParamsEnabled()

        synthExecutor.execute {
            var started = false
            var bgmHadError = false
            // ★ 每个请求分配唯一序号 #N，贯穿本章内所有日志，便于按请求追踪生命周期
            val reqNo = reqCounter.incrementAndGet()
            // ★ 音效：同步最新配置到 SoundEffectManager（BGM/音效开关、音量、注册表）
            SoundEffectManager.bgmEnabled = AppPrefs.isBgmEnabled()
            SoundEffectManager.bgmPath = AppPrefs.getBgmPath()
            SoundEffectManager.bgmVolume = AppPrefs.getBgmVolume()
            SoundEffectManager.sfxEnabled = AppPrefs.isSfxEnabled()
            SoundEffectManager.sfxVolume = AppPrefs.getSfxVolume()
            SoundEffectManager.sfxMap = AppPrefs.loadSfxMap().toMap()
            // ★ 捕获当前代际：此代际内 stoppedFlag 复位/停都不影响本请求继续（除非代际被 onStop 递增）
            val myGen = generation
            // ★ 新请求入队执行：复位停止标记（onStop 后新队列的第一个任务必须是"可继续"状态）
            stoppedFlag = false
            // ★ 发热修复：按需获取 WakeLock，合成完即释放，不长期占锁
            acquireLocks()
            // ★ BGM：请求开始即启动独立播放器（prepare 在后台队列线程做，不卡框架）
            startBgmPlayer()
            try {
                // ★ 延时路由 + 预处理全部移到工作线程（框架线程已立即返回，不再阻塞）
                // 系统语速：仅当"使用外部参数"开关打开时才采用。此时按 ColorOS/OPPO 中间刻度归一：
                //   系统语速条"中间"底层约 292~300（非标，安卓标准 100=1x），我们以 300=1x 对齐，
                //   即系统 300 ↔ 我们 UI 中间刻度 50（1x）。若按 100=1x 会把 600 放大成 6x（外部 App 变快）。
                // 默认开关为 false，外部 App 调用走下方 appSpeed（app 全局语速，默认 1x），不受系统 rate 影响。
                val speed = (rate / 300f).coerceIn(0.25f, 4f)
                val volumeNorm = if (volumeRaw > 1.5f) volumeRaw / 100f else volumeRaw
                val volume = if (volumeNorm <= 0f) 1f else volumeNorm.coerceIn(0f, 2f)
                val pitch = ((pitchRaw - 100) / 100f).coerceIn(-1f, 1f)
                val appSpeed = AppPrefs.getGlobalSpeed() ?: 1f
                val appPitch = AppPrefs.getGlobalPitch() ?: 0f
                val appVolume = AppPrefs.getGlobalVolume() ?: 1f
                val finalSpeed = if (useExternal) speed else appSpeed.coerceIn(0.25f, 4f)
                val finalPitch = if (useExternal) pitch else appPitch.coerceIn(-1f, 1f)
                // ★ 补齐全局音量：与语速/音调对称，使用外部开关关闭时改用本 app 全局音量
                val finalVolume = if (useExternal) volume else appVolume.coerceIn(0f, 2f)

                // 多引擎路由（走音色缓存）
                val rawVoice = if (rawVoiceName.isNotBlank()) rawVoiceName
                    else com.mtts.app.util.TtsVoicesCache.get(
                        engines.firstOrNull { it.code == "microsoft" } ?: engines.first()
                    ).firstOrNull()?.shortName ?: "zh-CN-YunxiNeural"
                val (engine, voiceName) = resolveVoice(rawVoice)
                val targetEngine = engine ?: engines.firstOrNull { it.code == "microsoft" }
                    ?: engines.firstOrNull()
                if (targetEngine == null) {
                    MttsLog.w(TAG, "REQ #$reqNo ✗ 无可用引擎")
                    if (callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) callback.error()
                    return@execute
                }
                val finalVoice = voiceName.ifBlank {
                    com.mtts.app.util.TtsVoicesCache.get(targetEngine).firstOrNull()?.shortName
                        ?: "zh-CN-YunxiNeural"
                }
                val engineForJob = targetEngine

                // ★ 接受小说/任意文本：预处理（文字替换/多音字）+ 对话功能分段（全部走缓存音色）
                val txt = rawText
                val result = TextPreprocessor.process(txt)
                // ★ 音效：关键词触发 → 自动注入 ⟨sfx:名字⟩ 标记（在关键词处）
                val text0 = result.text
                val text = if (AppPrefs.isSfxEnabled()) {
                    var t = text0
                    val rnd = java.util.Random()
                    // 关键词 → 候选池（同名关键词可能对应多个音效），随机选一个，不锁死同一个声音
                    val pool = HashMap<String, MutableList<String>>()
                    for ((kw, name) in AppPrefs.loadSfxKeywords()) {
                        if (kw.isNotBlank() && SoundEffectManager.sfxMap.containsKey(name)) {
                            pool.getOrPut(kw) { mutableListOf() }.add(name)
                        }
                    }
                    for ((kw, cands) in pool) {
                        if (t.contains(kw)) {
                            val name = cands[rnd.nextInt(cands.size)]
                            t = t.replace(kw, kw + TextPreprocessor.SFX_PREFIX + name + TextPreprocessor.SFX_SUFFIX)
                        }
                    }
                    t
                } else text0
                val dialogSegs = com.mtts.app.util.DialogFeature.process(text, { ec ->
                    val eng = EngineRegistry.get(ec)
                    if (eng != null) com.mtts.app.util.TtsVoicesCache.get(eng) else emptyList()
                }, engineForJob.code, finalVoice)
                val dialogue: List<com.mtts.app.util.DialogSeg> = dialogSegs ?: listOf(
                    com.mtts.app.util.DialogSeg(text, true, "", engineForJob.code, finalVoice, finalSpeed, finalVolume, finalPitch))

                // -- 请求起始：一行概括本轮完整的输入/路由/输出参数 --
                MttsLog.i(TAG, "REQ #$reqNo start  text=\"$txt\"  voice=${engineForJob.code}:$finalVoice  " +
                        "segments=${dialogue.size}  speed=${"%.2f".format(finalSpeed)}  volume=${"%.2f".format(finalVolume)}  " +
                        "pitch=${"%.2f".format(finalPitch)}  external=$useExternal")

                // ★ 流式输出：每段合成完立即写 callback.audioAvailable，不再全部缓冲
                //   原实现在 outBuf 里拼接所有段 + 停顿，再 start + 分块 audioAvailable，
                //   导致进度显示严重滞后于实际合成速度。现在改为「合成一段→输出一段」，
                //   配合系统 TTS 的进度指示器，能实时反映播放位置。
                // 注意：callback.start 需在首次 audioAvailable 之前调用一次
                val block = 8192
                var totalBytes = 0L

                // 写 PCM 分块辅助（保证单块 <= 8192；返回 false 表示已停止/失败）
                fun writeChunk(data: ByteArray, off: Int, len: Int): Boolean {
                    if (len <= 0) return true
                    if (!started) {
                        if (callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                            MttsLog.w(TAG, "REQ #$reqNo ✗ callback.start 失败")
                            return false
                        }
                        started = true
                    }
                    var pos = off
                    val end = off + len
                    while (pos < end) {
                        // ★ 停止响应（块级）：外部 App 停止/翻页/中途改语速重发 speak() 时，onStop() 会递增代际；
                        //   → 正在写块的任务必须立即中断，把剩余音频全部丢弃，
                        //   否则当前这整段会完整交给框架 AudioTrack 播完 → 听感"关闭了还在朗读"，
                        //   且与新请求合成的音频串接叠加 → "朗读错位"。
                        //   用代际而非 stoppedFlag：即使新请求已把 stoppedFlag 复位，旧任务仍能感知被废弃。
                        if (generation != myGen) {
                            MttsLog.w(TAG, "REQ #$reqNo ⏹ 块写中断（代际已变，丢弃剩余 ${(end - pos) / 1024}KB）")
                            return false
                        }
                        val cur = minOf(block, end - pos)
                        val chunk = data.copyOfRange(pos, pos + cur)
                        if (callback.audioAvailable(chunk, 0, cur) != TextToSpeech.SUCCESS) {
                            MttsLog.w(TAG, "REQ #$reqNo ✗ 音频输出被中断 offset=$totalBytes")
                            callback.done()
                            return false
                        }
                        totalBytes += cur
                        pos += cur
                    }
                    return true
                }

                for ((idx, seg) in dialogue.withIndex()) {
                    // ★ 停止响应：代际已变（外部 App 停止/翻页/中途改语速重发触发 onStop 递增）→ 当前任务快速退出
                    //   （兼容旧停止标记；代际判断更稳健：即使新请求已复位 stoppedFlag，旧任务仍能感知自己已被废弃）
                    if (stoppedFlag || generation != myGen) {
                        MttsLog.w(TAG, "REQ #$reqNo ⏹ 停止中断（剩余 ${dialogue.size - idx} 段跳过）")
                        break
                    }
                    if (seg.text.isBlank()) continue
                    val pauses = TextPreprocessor.extractPauses(seg.text)
                    val volMark = TextPreprocessor.extractVol(seg.text)
                    val pitMark = TextPreprocessor.extractPitchAdjust(seg.text)
                    val cleanText = TextPreprocessor.stripAllMarkers(seg.text)
                    if (cleanText.isBlank()) {
                        // 纯停顿段：直接写静音
                        for (ms in pauses) {
                            val sil = silencePcm(ms)
                            if (!writeChunk(sil, 0, sil.size)) return@execute
                        }
                        continue
                    }


                    val segEngine = EngineRegistry.get(seg.engineCode) ?: engineForJob
                    val segVoice = seg.voice.ifBlank {
                        com.mtts.app.util.TtsVoicesCache.get(segEngine).firstOrNull()?.shortName ?: finalVoice
                    }
                    val segSpeed = (finalSpeed * seg.speed).coerceIn(0.25f, 4f)
                    val segVolume = (finalVolume * seg.volume * volMark).coerceIn(0f, 2f)
                    val segPitch = (finalPitch + seg.pitch + pitMark).coerceIn(-1f, 1f)

                    // ★ 首字提速：进入段循环即调用 callback.start()，让系统 TTS 框架的播放器
                    //   提前就绪，段音频一合成完立即开播（原来要等首段合成完才 start，
                    //   框架内部还要再缓冲，首字延迟 = 合成时间 + 框架启动时间）
                    if (!started) {
                        if (callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
                            started = true
                        }
                    }

                    MttsLog.d(TAG, "REQ #$reqNo seg[${idx + 1}/${dialogue.size}] ${if (seg.isSpeech) "对白" else "旁白"} " +
                            "speaker=${seg.speaker}  eng=${segEngine.code}:$segVoice  " +
                            "speed=${"%.2f".format(segSpeed)} vol=${"%.2f".format(segVolume)} pitch=${"%.2f".format(segPitch)}  " +
                            "text=\"${cleanText.take(24)}\"")

                    // ★ 停止秒断：合成用 Future 包裹，停止时不再干等当前段合成完（原 runBlocking 会阻塞到合成结束，
                    //   微软引擎一段要 1~2s，stop 后"还在念"的残留全来自这里）。
                    // ★ 复用共享线程池（原每段 newSingleThreadExecutor 不关闭 → 长时间朗读线程泄漏）
                    val synthFuture = java.util.concurrent.CompletableFuture.supplyAsync({
                        try {
                            // 合成在后台线程跑，外层主队列轮询停止标志；停止时不再干等本段完成
                            runBlocking { segEngine.synthesize(cleanText, segVoice, segSpeed, 1.0f, segPitch) }
                        } catch (ex: Exception) {
                            MttsLog.w(TAG, "REQ #$reqNo seg[${idx + 1}] ✗ 合成失败跳过: ${ex.message}")
                            ByteArray(0)
                        }
                    }, synthPool)
                    var segPcm: ByteArray
                    while (true) {
                        if (stoppedFlag || generation != myGen) {
                            // 已被停止：取消合成并立即退出本段（后台合成线程随后自然结束）
                            synthFuture.cancel(true)
                            MttsLog.w(TAG, "REQ #$reqNo ⏹ 合成等待中断（停止）")
                            return@execute
                        }
                        segPcm = try {
                            synthFuture.get(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        } catch (e: java.util.concurrent.TimeoutException) {
                            continue
                        } catch (e: Exception) {
                            ByteArray(0)
                        }
                        break
                    }
                    if (segPcm.isEmpty()) {
                        // 空段：跳过音频但仍追加停顿静音（保持语流节奏）
                        for (ms in pauses) {
                            val sil = silencePcm(ms)
                            if (!writeChunk(sil, 0, sil.size)) return@execute
                        }
                        continue
                    }
                    // 统一重采样到 24kHz（系统 TTS 框架固定 24kHz / 16bit / 单声道）
                    // ★ 取「当前音色」的采样率（百度不同音色采样率不同，见 voiceSampleRate 注释），
                    //   避免误判跳过重采样导致杂音。
                    val segRate = voiceSampleRate(segEngine, segVoice)
                    var unifiedSeg = if (segRate == 24000) segPcm
                                     else PcmResampler.resample16BitMono(segPcm, segRate, 24000)
                    // ★ 峰值归一化音量均衡（在统一采样率后做）
                    val balanced0 = PcmResampler.balanceVolume16BitMono(unifiedSeg, segVolume)

                                        // ★★ 音效混音（PCM 层）：BGM 已改为独立 MediaPlayer（见 startBgmPlayer），不再逐句 PCM 混入。
                    //   （原混入方案首段同步整首解码 44.1kHz BGM，卡输出线程 18 秒+ → 外部调用第一句"卡死"）
                    //   此处只保留段内 ⟨sfx:名⟩ 短音效混入。
                    val balanced = run {
                        var pcmBytes = balanced0
                        // 音效标记：段内 ⟨sfx:名⟩ → 在对应样本位置混入
                        if (AppPrefs.isSfxEnabled()) {
                            val sfxItems = TextPreprocessor.extractSfxAt(seg.text)
                            if (sfxItems.isNotEmpty()) {
                                var samples = ByteArrayToShorts(pcmBytes)
                                for (item in sfxItems) {
                                    val sfx = SoundEffectManager.getSfxPcm(this@TtsService, item.name)
                                    if (sfx != null) {
                                        // ★ 按标记在文本中的位置比例映射到 PCM（回退 0.2s 对齐开口瞬间）
                                        val at = ((samples.size - 1) * item.at - 4800).toInt()
                                            .coerceIn(0, samples.size - 1)
                                        // mixSfx 返回混合后的新数组，直接替换引用
                                        samples = SoundEffectManager.mixSfx(samples, at, sfx, SoundEffectManager.sfxVolume)
                                    }
                                }
                                pcmBytes = ShortsToBytes(samples)
                            }
                        }
                        pcmBytes
                    }
                    if (!writeChunk(balanced, 0, balanced.size)) return@execute
                    // 追加停顿静音
                    for (ms in pauses) {
                        val sil = silencePcm(ms)
                        if (!writeChunk(sil, 0, sil.size)) return@execute
                    }
                }
                if (!started) {
                    // 无任何内容输出
                    MttsLog.w(TAG, "REQ #$reqNo ✗ 未输出任何音频（空 PCM）")
                    if (callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
                        callback.error()
                    }
                    return@execute
                }
                MttsLog.i(TAG, "REQ #$reqNo done  bytes=$totalBytes  " +
                        "dur=${totalBytes * 1000L / (24000 * 2L)}ms  voice=${engineForJob.code}:$finalVoice")
                // ★ 请求正常完成：BGM 保持播放（外部朗读每句一次请求，跨句必须连续）
                callback.done()
            } catch (e: Exception) {
                MttsLog.e(TAG, "REQ #$reqNo ✗ 异常终止: ${e.message ?: "exception"} [$rawText]", e)
                bgmHadError = true
                try {
                    // ★ 已 start 过就不能再 start（会异常）；直接 done 收尾
                    if (started) {
                        callback.done()
                    } else if (callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
                        callback.error()
                    }
                } catch (_: Throwable) {}
            } finally {
                // ★ 发热修复：合成完成/异常/停止后立即释放锁，不长期占用
                // ★ finally 里的 BGM 停止只针对异常路径无法区分——改为：仅当异常时才停。
                //   正常完成后 BGM 继续播（跨句连续）。
                if (bgmHadError) stopBgmPlayer()
                releaseLocks()
            }
        }
    }

    override fun onStop() {
        MttsLog.i(TAG, "STOP 收到外部停止/翻页 → 丢弃排队任务")
        // ★ 修复循环读：
        //   外部 App 停止朗读/翻页时框架会回调 onStop()。此前为空实现，
        //   串行队列里积压的旧请求（因框架线程阻塞超时重发堆积）会继续一个个播完，
        //   听感上就是同一段文字被反复朗读。
        //   现在：
        //   1) shutdownNow() 丢弃排队中的任务；
        //   2) 正在执行的任务通过 stoppedFlag 在段边界快速退出（不打断正在写出的音频块，
        //      避免 AudioTrack 状态错乱）；
        //   3) 立刻替换为全新队列，保证后续新朗读请求正常入队执行。
        stoppedFlag = true
        // ★ 代际递增：令所有已入队/正在执行的旧任务（含卡在段内合成的）在段边界/块级感知被废弃，
        //   即使随后新请求复位 stoppedFlag，旧任务也能通过 generation != myGen 快速退出，不再继续朗读。
        generation++
        // ★ 外部停止：立即停 BGM
        stopBgmPlayer()
        // ★ 发热修复：外部停止时立即释放锁
        releaseLocks()
        val old = synthExecutor
        old.shutdownNow()
        synthExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mtts-tts-synth").apply { isDaemon = true }
        }
    }
}
