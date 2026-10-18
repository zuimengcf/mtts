package com.mtts.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.mtts.app.MttsApplication
import com.mtts.app.R
import com.mtts.app.engine.EngineCategory
import com.mtts.app.engine.EngineRegistry
import com.mtts.app.engine.TtsEngine
import com.mtts.app.engine.Voice
import com.mtts.app.util.AppPrefs
import com.mtts.app.util.MttsLog
import com.mtts.app.util.PcmResampler
import com.mtts.app.util.TextPreprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI 状态 */
data class EngineUiState(
    val currentEngine: TtsEngine? = null,
    val voices: List<Voice> = emptyList(),
    val loading: Boolean = false,
    val synthesizing: Boolean = false,
    val text: String = "",
    val selectedVoice: String = "",
    val audioBytes: ByteArray? = null,
    val audioReady: Boolean = false,
    val playing: Boolean = false,
    val error: String? = null,
    val bottomTab: BottomTab = BottomTab.TTS,
    val settingsDetail: SettingsItem? = null,
    val preprocessPage: PreprocessSubPage? = null,   // 文本预处理内部二级页（null=预处理主页）
    val dialogFeaturePage: DialogFeaturePage? = null  // 对话功能内部二级页（null=对话功能主页）
)

/** 底部导航 Tab */
enum class BottomTab(val label: String) {
    TTS("TTS"),
    DIALOG("对话"),
    SETTINGS("设置")
}

/** 设置列表项（顺序 = 展示顺序：模型/引擎类靠前，工具类居中，关于垫底） */
enum class SettingsItem(val label: String, val desc: String) {
    ENGINES("引擎设置", "管理已注册的 TTS 引擎"),
    VOICE_PACKS("语音包", "离线语音包资源管理"),
    MODEL_DOWNLOAD("Sherpa 模型下载", "下载 Matcha / Kokoro / MeloTTS 扩展模型"),
    DIALOG_FEATURE("对话功能", "旁白 / 角色 / 声音分组 / 情感"),
    PREPROCESS("文本预处理", "文字替换 / 多音字"),
    TEST_TEXT("测试文本", "修改内置测试的默认文本"),
    HTTP_SERVER("HTTP 网关", "端口号 / 开关，供外部调用本地引擎"),
    LOGS("日志", "查看运行日志"),
    SETTINGS_IO("全局设置导入导出", "除语音包外的全部设置一键导入 / 导出"),
    ABOUT("关于", "版本 / 项目信息")
}

/** 预处理子页（设置 → 文本预处理 → 二级页面） */
enum class PreprocessSubPage(val label: String) {
    REPLACE("文字替换"),
    POLYPHONIC("多音字"),
    EMOJI("表情符号"),
    SMART("智能识别")
}

/** 设置页数据 */
data class SettingsState(
    val engines: List<TtsEngine> = emptyList(),
    val voicePackageInfo: String = "",
    val loading: Boolean = false,
    val error: String? = null
)

/** 对话角色 —— 绑定引擎 + 音色，可任意多个（群聊群员：可禁言/踢出） */
data class DialogRole(
    val label: String = "角色1",
    val engineCode: String = "microsoft",
    val voice: String = "",
    val avatar: String = "",
    val muted: Boolean = false   // 禁言：该群员的台词不发声（合成时跳过）
) {
    /** 显示用：引擎名 + 音色 */
    fun display(registry: () -> List<TtsEngine>): String {
        val engine = registry().firstOrNull { it.code == engineCode }
        return (engine?.name ?: engineCode) + " / " + voice.ifBlank { "未选" }
    }
}

/** 对话状态 —— 多角色列表 + 脚本（持久化到 AppPrefs） */
data class DialogState(
    val roles: List<DialogRole> = emptyList(),
    val script: String = "",
    val playing: Boolean = false,
    val progressLine: Int = -1,   // 当前播放到的行号（0 起），-1 表示未播放
    val error: String? = null
)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(EngineUiState())
    val uiState: StateFlow<EngineUiState> = _uiState.asStateFlow()

    private val _settings = MutableStateFlow(SettingsState(engines = EngineRegistry.all()))
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    /** 对话状态（多引擎 A/B 轮流发言）—— 启动时从 AppPrefs 恢复，修改时自动落盘 */
    private val _dialog = MutableStateFlow(loadDialogState())
    val dialog: StateFlow<DialogState> = _dialog.asStateFlow()

    /** 从 AppPrefs 恢复对话状态（持久化：角色列表 + 脚本） */
    private fun loadDialogState(): DialogState {
        val savedRoles = AppPrefs.loadDialogRolesList()
        val savedScript = AppPrefs.loadDialogScript()
        val roles = if (savedRoles.isEmpty()) {
            listOf(
                DialogRole("角色1", "microsoft", ""),
                DialogRole("角色2", "bdetts", "")
            )
        } else savedRoles
        val script = if (savedScript.isBlank() && savedRoles.isEmpty()) {
            "角色1: 你好\n角色2: 吃了吗\n角色1: 没有\n角色2: 快吃啊"
        } else savedScript
        return DialogState(roles = roles, script = script)
    }

    /** 保存当前对话状态到 AppPrefs */
    private fun persistDialog() {
        AppPrefs.saveDialogRoles(_dialog.value.roles)
        AppPrefs.saveDialogScript(_dialog.value.script)
    }

    /** 引擎列表版本号（语音包导入/删除后递增，驱动侧边栏/设置页重组） */
    private val _enginesVersion = MutableStateFlow(0)
    val enginesVersion: StateFlow<Int> = _enginesVersion.asStateFlow()

    /** 当前播放用的 AudioTrack（微软引擎输出 24kHz 16bit 单声道 PCM） */
    @Volatile private var track: AudioTrack? = null

    /** 播放停止请求标志（stopDialog 置 true 打断逐行播放循环） */
    @Volatile private var stopRequested = false

    /** 从应用上下文读取字符串资源（ViewModel 层） */
    private fun str(id: Int): String =
        MttsApplication.ctx()?.getString(id) ?: ""

    private fun str(id: Int, vararg args: Any): String =
        MttsApplication.ctx()?.getString(id, *args) ?: ""

    /** 切换底部 Tab */
    fun selectTab(tab: BottomTab) {
        _uiState.value = _uiState.value.copy(
            bottomTab = tab,
            settingsDetail = null   // 切 tab 时回到设置列表首页
        )
    }

    /** 进入设置子项 */
    fun openSettingsItem(item: SettingsItem) {
        _uiState.value = _uiState.value.copy(
            bottomTab = BottomTab.SETTINGS,
            settingsDetail = item,
            preprocessPage = null,
            dialogFeaturePage = null
        )
    }

    /** 进入对话功能二级页（旁白 / 角色 / 声音分组 / 情感） */
    fun openDialogFeaturePage(page: DialogFeaturePage) {
        _uiState.value = _uiState.value.copy(
            settingsDetail = SettingsItem.DIALOG_FEATURE,
            dialogFeaturePage = page
        )
    }

    /** 返回对话功能主页 */
    fun backToDialogFeatureHome() {
        _uiState.value = _uiState.value.copy(dialogFeaturePage = null)
    }

    /** 进入预处理二级页（文字替换 / 多音字 / 对话识别） */
    fun openPreprocessPage(page: PreprocessSubPage) {
        _uiState.value = _uiState.value.copy(
            settingsDetail = SettingsItem.PREPROCESS,
            preprocessPage = page
        )
    }

    /** 返回预处理主页 */
    fun backToPreprocessHome() {
        _uiState.value = _uiState.value.copy(preprocessPage = null)
    }

    /** 返回设置列表首页 */
    fun backToSettingsList() {
        _uiState.value = _uiState.value.copy(settingsDetail = null, preprocessPage = null)
    }

    /** 按类别分组的引擎列表（供侧边栏） */
    val groupedEngines: Map<EngineCategory, List<TtsEngine>>
        get() = EngineRegistry.grouped()

    /** 全部引擎（供侧边栏标题排序） */
    val allEngines: List<TtsEngine> = EngineRegistry.all()

    /**
     * 刷新引擎注册（语音包导入/删除后调用）：
     * 重新扫描已导入的引擎目录动态注册，并递增版本号驱动 UI 重组。
     */
    fun refreshEngines() {
        EngineRegistry.refreshImported()
        // 若当前选中的引擎已被移除，回退到第一个可用引擎
        val cur = _uiState.value.currentEngine?.code
        if (cur != null && EngineRegistry.get(cur) == null) {
            selectEngine(EngineRegistry.all().firstOrNull()?.code ?: "")
        }
        _settings.value = _settings.value.copy(engines = EngineRegistry.all())
        _enginesVersion.value++
        MttsLog.d("Vm", "refreshEngines", mapOf("engines" to EngineRegistry.availableCodes()))
    }

    init {
        // ★ 重启后恢复上次选择的引擎（尊重用户选择；未保存过才回退第一个）
        val savedEngine = AppPrefs.getSelectedEngine()
        selectEngine(EngineRegistry.get(savedEngine ?: "")?.code
            ?: EngineRegistry.all().firstOrNull()?.code ?: "")
    }

    fun selectEngine(code: String) {
        val engine = EngineRegistry.get(code) ?: return
        MttsLog.d("Vm", "selectEngine", mapOf("code" to code))
        // ★ 持久化当前引擎（重启/退出后恢复）
        AppPrefs.setSelectedEngine(code)
        _uiState.value = EngineUiState(currentEngine = engine)
        loadVoices(engine)
    }

    private fun loadVoices(engine: TtsEngine) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            try {
                val vs = withContext(Dispatchers.IO) { engine.listVoices() }
                // ★ 恢复该引擎上次选择的发言人（重启/切引擎后保留，per-engine 维度）
                val saved = AppPrefs.getSelectedVoice(engine.code)
                _uiState.value = _uiState.value.copy(
                    voices = vs,
                    loading = false,
                    selectedVoice = vs.firstOrNull { it.shortName == saved }?.shortName
                        ?: vs.firstOrNull()?.shortName ?: "",
                    error = null
                )
            } catch (e: Exception) {
                MttsLog.e("Vm", "listVoices failed", e)
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = e.message ?: str(R.string.err_load_failed)   // 占位
                )
            }
        }
    }

    fun onTextChange(newText: String) {
        _uiState.value = _uiState.value.copy(text = newText)
    }

    /** 内置测试语句：填入已保存的测试文本（可在设置中修改），方便一键验证流式播放 */
    fun loadTestText() {
        val testText = AppPrefs.getTestText()
        _uiState.value = _uiState.value.copy(text = testText, error = null)
        playAudio()
    }

    /** 某音色是否被屏蔽（彻底关闭：不可选中/合成/试听） */
    private fun isVoiceMuted(engine: TtsEngine?, voice: String): Boolean {
        val ec = engine?.code ?: return false
        return AppPrefs.isVoiceMuted(ec, voice)
    }

    fun selectVoice(voice: String) {
        val engine = _uiState.value.currentEngine
        // 被屏蔽音色不可选中（彻底关闭）
        if (isVoiceMuted(engine, voice)) return
        _uiState.value = _uiState.value.copy(selectedVoice = voice)
        // ★ 持久化当前引擎选中的发言人（切引擎/重启后恢复）
        engine?.code?.let { AppPrefs.setSelectedVoice(it, voice) }
    }

    fun synthesize() {
        val state = _uiState.value
        val engine = state.currentEngine ?: return
        val text = state.text
        if (text.isBlank()) {
            _uiState.value = state.copy(error = str(R.string.err_need_input))
            return
        }
        val voice = state.selectedVoice
        // 被屏蔽音色不可合成（彻底关闭）
        if (isVoiceMuted(engine, voice)) {
            MttsLog.d("Vm", "synthesize blocked: muted voice=$voice")
            _uiState.value = state.copy(error = str(R.string.err_need_input))
            return
        }

        MttsLog.d("Vm", "synthesize", mapOf(
            "engine" to engine.code, "voice" to voice, "text" to text.take(20)))

        viewModelScope.launch {
            _uiState.value = state.copy(synthesizing = true, error = null, audioReady = false)
            try {
                val (spd, vol, pit) = effectiveVoiceParams(engine, voice)
                // ★ 标记处理（停顿/音量/音调）：与 TtsService 一致，
                //   剥离 ⟨pause:ms⟩/⟨vol:x⟩/⟨pit:x⟩ 后再合成，避免标记被引擎当普通文本朗读
                val pauses = TextPreprocessor.extractPauses(text)
                val volMark = TextPreprocessor.extractVol(text)
                val pitMark = TextPreprocessor.extractPitchAdjust(text)
                val clean = TextPreprocessor.stripAllMarkers(text)
                val effVol = (vol * volMark).coerceIn(0f, 2f)
                val effPit = (pit + pitMark).coerceIn(-1f, 1f)
                var audio = withContext(Dispatchers.IO) {
                    engine.synthesize(clean, voice, spd, 1.0f, effPit)
                }
                // ★ 峰值归一化音量均衡
                audio = PcmResampler.balanceVolume16BitMono(audio, effVol)
                // 追加停顿静音（按引擎真实采样率生成，与 PCM 同采样率）
                val silenceMs = pauses.sum()
                if (silenceMs > 0 && audio.isNotEmpty()) {
                    val rate = voiceSampleRate(voice, engine.code)
                    val silence = ByteArray(rate * 2 * silenceMs / 1000)
                    audio = audio + silence
                }
                MttsLog.d("Vm", "synthesize ok", mapOf("bytes" to audio.size))
                _uiState.value = _uiState.value.copy(
                    synthesizing = false,
                    audioBytes = audio,
                    audioReady = audio.isNotEmpty(),
                    error = null
                )
            } catch (e: Exception) {
                MttsLog.e("Vm", "synthesize failed", e)
                _uiState.value = _uiState.value.copy(
                    synthesizing = false,
                    error = e.message ?: str(R.string.err_synth_failed),
                    audioReady = false
                )
            }
        }
    }

    /**
     * 播放 —— 先一次性合成完整 PCM，再按块写入 MODE_STREAM AudioTrack 实时播放。
     *
     * 说明：微软 SDK 的 Synthesizing 事件 getAudioData() 返回累积缓冲（非增量块），
     * 逐块提交会导致重复/卡顿、只播出一段。正确做法（与 MultiTTS 一致）是
     * SpeakSsmlAsync().get().getAudioData() 一次性取完整 PCM，再在传输层分块流式写入。
     * 已用 Linux 脚本 verify_stream.py 验证：分块拼接 == 原始数据，无丢失/重复/乱序。
     */
    fun playAudio() {
        if (_uiState.value.playing) { stopPlayback(); return }
        val state = _uiState.value
        val engine = state.currentEngine
        val text = state.text
        if (text.isBlank()) { _uiState.value = state.copy(error = str(R.string.err_need_input)); return }
        if (engine == null) { _uiState.value = state.copy(error = str(R.string.err_no_engine)); return }
        val voice = state.selectedVoice
        // 被屏蔽音色不可播放（彻底关闭）
        if (isVoiceMuted(engine, voice)) {
            MttsLog.d("Vm", "playAudio blocked: muted voice=$voice")
            _uiState.value = state.copy(error = str(R.string.err_need_input))
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(playing = true, synthesizing = true, error = null, audioReady = false)
            try {
                withContext(Dispatchers.IO) {
                    // 1) 一次性合成完整 PCM（16bit / 单声道，引擎真实采样率：微软 24k / 搜狗百度 16k）
                    val (spd, vol, pit) = effectiveVoiceParams(engine, voice)
                    // ★ 标记处理（停顿/音量/音调）：与 TtsService 一致，
                    //   剥离 ⟨pause:ms⟩/⟨vol:x⟩/⟨pit:x⟩ 后再合成，避免标记被引擎当普通文本朗读
                    val pauses = TextPreprocessor.extractPauses(text)
                    val volMark = TextPreprocessor.extractVol(text)
                    val pitMark = TextPreprocessor.extractPitchAdjust(text)
                    val clean = TextPreprocessor.stripAllMarkers(text)
                    val effVol = (vol * volMark).coerceIn(0f, 2f)
                    val effPit = (pit + pitMark).coerceIn(-1f, 1f)
                    var pcm = engine.synthesize(clean, voice, spd, 1.0f, effPit)
                    if (pcm.isEmpty()) throw RuntimeException(str(R.string.err_no_audio))
                    // ★ 峰值归一化音量均衡（保证各引擎在相同 UI 音量下听感一致）
                    pcm = PcmResampler.balanceVolume16BitMono(pcm, effVol)
                    // 追加停顿静音（按引擎真实采样率生成，与 PCM 同采样率）
                    val silenceMs = pauses.sum()
                    if (silenceMs > 0) {
                        val rate = voiceSampleRate(voice, engine.code)
                        val silence = ByteArray(rate * 2 * silenceMs / 1000)
                        pcm = pcm + silence
                    }
                    MttsLog.d("Vm", "synthesize for play bytes=${pcm.size}")

                    // ★ 统一 24kHz 播放（playPcm 内部会把 16k 数据重采样到 24k）：
                    //   本设备直接建 16kHz AudioTrack 会无声（playbackHeadPosition 冻结、write 无效），
                    //   而 24kHz 一直正常。重采样后音调/语速不变、无杂音。
                    playPcm(pcm, voiceSampleRate(voice, engine.code))
                }
                _uiState.value = _uiState.value.copy(playing = false, synthesizing = false)
            } catch (e: Exception) {
                MttsLog.e("Vm", "play failed", e)
                stopPlayback()
                _uiState.value = _uiState.value.copy(
                    playing = false, synthesizing = false,
                    error = e.message ?: str(R.string.err_play_failed)
                )
            }
        }
    }

    /** 停止并释放当前播放 */
    fun stopPlayback() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try { track?.pause() } catch (_: Throwable) {}
                try { track?.flush() } catch (_: Throwable) {}
                try { track?.release() } catch (_: Throwable) {}
                track = null
            }
            _uiState.value = _uiState.value.copy(playing = false)
        }
    }

    /**
     * 计算指定音色的生效合成参数（speed/volume/pitch）。
     * 优先级（★ 全局配置回退链）：per-voice 单独配置 → 全局配置 → config.yaml 音色自带值 → 默认。
     * 使用已加载的当前引擎音色列表取 config 默认（避免 suspend 调用）。
     */
    fun effectiveVoiceParams(
        engine: TtsEngine, voice: String,
        voices: List<Voice> = _uiState.value.voices
    ): Triple<Float, Float, Float> {
        // ★ 默认参数从「指定引擎的音色列表」取（对话播放时传入目标引擎自己的 voices，
        //   避免从当前 TTS 页列表找不到该音色 → 默认参数全部错乱）
        val cfg = voices.firstOrNull { it.shortName == voice }
        val cfgSpeed = cfg?.speed ?: 1.0f
        val cfgVolume = cfg?.volume ?: 1.0f
        val cfgPitch = cfg?.pitch ?: 0.0f
        // 第一优先级：per-voice 单独配置
        val customSpeed = AppPrefs.getVoiceSpeed(voice)
        val customVolume = AppPrefs.getVoiceVolume(voice)
        val customPitch = AppPrefs.getVoicePitch(voice)
        // 第二优先级：全局配置（无单独配置时回退）
        val globalSpeed = AppPrefs.getGlobalSpeed()
        val globalVolume = AppPrefs.getGlobalVolume()
        val globalPitch = AppPrefs.getGlobalPitch()
        return Triple(
            customSpeed ?: globalSpeed ?: cfgSpeed,
            customVolume ?: globalVolume ?: cfgVolume,
            customPitch ?: globalPitch ?: cfgPitch
        )
    }

    /**
     * 音色的 config.yaml 自带默认合成参数（不含全局/单独配置覆盖）。
     * 用于参数对话框展示「未自定义时的基准值」（pitch UI 基准 = 25），
     * 避免被全局参数污染导致默认值显示异常（如 pitch 显示 83 而非 25）。
     * 合成实际生效仍走 effectiveVoiceParams（per-voice → 全局 → config）回退链。
     */
    fun configVoiceParams(voice: String): Triple<Float, Float, Float> {
        val cfg = _uiState.value.voices.firstOrNull { it.shortName == voice }
        return Triple(cfg?.speed ?: 1.0f, cfg?.volume ?: 1.0f, cfg?.pitch ?: 0.0f)
    }

    /** 清除某音色的单独配置（回退到全局配置） */
    fun clearVoiceParams(voice: String) {
        AppPrefs.clearVoiceParams(voice)
    }

    /** 试听指定音色：用当前文本框里的测试文本 + 该音色合成播放（不改动当前选中音色）。
     *  文本框为空时直接使用内置测试文本（不报错）。 */
    fun previewVoice(engine: TtsEngine, voice: String) {
        if (_uiState.value.playing) { stopPlayback(); return }
        // 被屏蔽音色不可试听（彻底关闭）
        if (isVoiceMuted(engine, voice)) {
            MttsLog.d("Vm", "previewVoice blocked: muted voice=$voice")
            _uiState.value = _uiState.value.copy(error = str(R.string.err_need_input))
            return
        }
        // ★ 文本框为空 → 兜底用内置测试文本
        val text = _uiState.value.text.ifBlank { AppPrefs.getTestText() }
        if (text.isBlank()) { _uiState.value = _uiState.value.copy(error = str(R.string.err_need_input)); return }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(playing = true, synthesizing = true, error = null, audioReady = false)
            try {
                withContext(Dispatchers.IO) {
                    val (spd, vol, pit) = effectiveVoiceParams(engine, voice)
                    // ★ 标记处理（停顿/音量/音调）：与 TtsService 一致，
                    //   剥离 ⟨pause:ms⟩/⟨vol:x⟩/⟨pit:x⟩ 后再合成，避免标记被引擎当普通文本朗读
                    val pauses = TextPreprocessor.extractPauses(text)
                    val volMark = TextPreprocessor.extractVol(text)
                    val pitMark = TextPreprocessor.extractPitchAdjust(text)
                    val clean = TextPreprocessor.stripAllMarkers(text)
                    val effVol = (vol * volMark).coerceIn(0f, 2f)
                    val effPit = (pit + pitMark).coerceIn(-1f, 1f)
                    // ★ 音量均衡：引擎传 volume=1.0 拿基准声，再按 effVol 峰值归一化
                    var pcm = engine.synthesize(clean, voice, spd, 1.0f, effPit)
                    if (pcm.isEmpty()) throw RuntimeException(str(R.string.err_no_audio))
                    // 峰值归一化音量均衡
                    pcm = PcmResampler.balanceVolume16BitMono(pcm, effVol)
                    // 追加停顿静音（按引擎真实采样率生成，与 PCM 同采样率）
                    val silenceMs = pauses.sum()
                    if (silenceMs > 0) {
                        val rate = voiceSampleRate(voice, engine.code)
                        val silence = ByteArray(rate * 2 * silenceMs / 1000)
                        pcm = pcm + silence
                    }
                    // ★ 用音色真实采样率播放（搜狗/百度 16kHz、微软 24kHz）
                    playPcm(pcm, voiceSampleRate(voice, engine.code))
                }
                _uiState.value = _uiState.value.copy(playing = false, synthesizing = false)
            } catch (e: Exception) {
                MttsLog.e("Vm", "preview failed", e)
                stopPlayback()
                _uiState.value = _uiState.value.copy(
                    playing = false, synthesizing = false,
                    error = e.message ?: str(R.string.err_preview_failed)
                )
            }
        }
    }

    /**
     * 将 PCM（16bit / 单声道，srcRateHz 为源采样率）播放。
     *
     * ★ 统一 24kHz 输出：本设备直接建 16kHz AudioTrack 会无声（playbackHeadPosition 冻结、
     *    write 无效），而 24kHz 一直正常。故 16k 数据一律重采样到 24k 再播放，
     *   与 playDialog/系统 TTS 一致——既有声音又不因采样率不匹配而变调/杂音。
     */
    private fun playPcm(pcm: ByteArray, srcRateHz: Int = 24000) {
        val playRate = 24000   // 统一输出采样率
        val raw = if (srcRateHz == playRate || srcRateHz <= 0) pcm
                  else PcmResampler.resample16BitMono(pcm, srcRateHz, playRate)
        if (raw.isEmpty()) return
        val minBuf = AudioTrack.getMinBufferSize(playRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

        // ================= 短音频：pad 到超过内部缓冲，强制走阻塞写入 =================
        // 日志实证（本设备华为 AudioTrack）：
        //   数据 > 内部实际缓冲（~minBuf，传入 bufferSize 仅建议值）→ write 阻塞 → 播放线程被喂 → 有声
        //   数据 < 内部实际缓冲 → 一次吞入 → 播放线程不启动 → 无声（writeMs≈1ms、head 冻结）
        //   MODE_STATIC 在本设备初始化失败（已实测回落），故用「尾部补静音」方案：
        //   pad 后大小 > minBuf → write(pad) 必定阻塞 → 播放线程启动出声（静音=0，无杂音）。
        val blockW = 32 * 1024
        val data = if (raw.size < minBuf) {
            // 补静音到 minBuf + 一个 write 块（保证首次 write 就阻塞），尾部全是 0 = 无声
            val pad = minBuf + blockW
            java.util.Arrays.copyOf(raw, maxOf(raw.size, pad))
        } else raw
        if (data.isEmpty()) return

        // ★ 强制最小缓冲作为请求值（设备内部仍可能抬升到 minBuf，但我们要的是 write 阻塞）
        var bufferSize = 4096
        val streamTrack = try {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(playRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            ).also {
                if (it.state != AudioTrack.STATE_INITIALIZED) {
                    try { it.release() } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) { null }
        val finalTrack = streamTrack?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } ?: run {
            bufferSize = minBuf
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(playRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        }
        track = finalTrack
        if (finalTrack.state != AudioTrack.STATE_INITIALIZED) {
            throw RuntimeException(str(R.string.err_play_failed) + " (AudioTrack init failed $playRate)")
        }
        MttsLog.d("Vm", "audioTrack minBuf=$minBuf buffer=$bufferSize raw=${raw.size} pad=${data.size}")
        finalTrack.play()
        MttsLog.d("Vm", "play start", mapOf("rate" to playRate, "srcRate" to srcRateHz, "bytes" to raw.size))

        // 分块阻塞写入（data >= minBuf+block → 首次 write 即阻塞，播放线程必然启动）
        var offset = 0
        val writeStart = System.currentTimeMillis()
        while (offset < data.size && track != null) {
            val len = minOf(blockW, data.size - offset)
            val written = finalTrack.write(data, offset, len)
            if (written <= 0) throw RuntimeException(str(R.string.err_play_failed) + " (write=$written)")
            offset += written
        }
        val writeMs = System.currentTimeMillis() - writeStart
        MttsLog.d("Vm", "play written bytes=$offset writeMs=$writeMs")

        // 等原始音频播完（pad 静音随音频一起播，等待按「原始时长」算，head 推进则提前退出）
        val totalSamples = raw.size / 2L
        val totalMs = raw.size * 1000L / (playRate * 2L)
        val remainMs = maxOf(0L, totalMs - writeMs) + 250
        val drainDeadline = System.currentTimeMillis() + remainMs
        while (System.currentTimeMillis() < drainDeadline && track != null) {
            val head = try { finalTrack.playbackHeadPosition.toLong() } catch (_: Throwable) { -1L }
            if (head >= totalSamples) break
            try { Thread.sleep(30) } catch (_: Throwable) {}
        }
        MttsLog.d("Vm", "drain done totalSamples=$totalSamples remainMs=$remainMs")
        try { finalTrack.stop() } catch (_: Throwable) {}
        try { finalTrack.flush() } catch (_: Throwable) {}
        try { finalTrack.release() } catch (_: Throwable) {}
        track = null
    }

    /** 播放任意文本（预处理测试用）：用当前引擎 + 当前音色合成播放，不改动 uiState.text */
    fun playText(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        if (_uiState.value.playing) { stopPlayback(); return }
        val state = _uiState.value
        val engine = state.currentEngine ?: return
        val voice = state.selectedVoice

        viewModelScope.launch {
            _uiState.value = state.copy(playing = true, synthesizing = true, error = null, audioReady = false)
            try {
                withContext(Dispatchers.IO) {
                    val (spd, vol, pit) = effectiveVoiceParams(engine, voice)
                    // ★ 标记处理（停顿/音量/音调）：剥离标记后再合成，
                    //   与 TtsService 一致，避免 ⟨pause:ms⟩ 等标记被引擎当普通文本朗读出来
                    val pauses = TextPreprocessor.extractPauses(t)
                    // 音量/音调标记（智能识别注入 ⟨vol:x⟩/⟨pit:x⟩）—— 试听链路也要应用，与 TtsService 一致
                    val volMark = TextPreprocessor.extractVol(t)
                    val pitMark = TextPreprocessor.extractPitchAdjust(t)
                    val clean = TextPreprocessor.stripAllMarkers(t)
                    val effVol = (vol * volMark).coerceIn(0f, 2f)
                    val effPit = (pit + pitMark).coerceIn(-1f, 1f)
                    val pcm = engine.synthesize(clean, voice, spd, 1.0f, effPit)
                    if (pcm.isEmpty()) throw RuntimeException(str(R.string.err_no_audio))
                    // ★ 峰值归一化音量均衡
                    val balanced = PcmResampler.balanceVolume16BitMono(pcm, effVol)
                    val rate = voiceSampleRate(voice, engine.code)
                    // 追加停顿静音（按引擎真实采样率生成，与 PCM 同采样率，
                    //   playPcm 统一重采样后时长不变；TtsService 是先重采样再追加 24kHz 静音，效果等价）
                    val silenceMs = pauses.sum()
                    val silence = if (silenceMs > 0) ByteArray(rate * 2 * silenceMs / 1000) else ByteArray(0)
                    val full = if (silence.isEmpty()) balanced
                               else balanced + silence
                    // ★ 用音色真实采样率播放（搜狗/百度 16kHz、微软 24kHz）
                    playPcm(full, rate)
                }
                _uiState.value = _uiState.value.copy(playing = false, synthesizing = false)
            } catch (e: Exception) {
                MttsLog.e("Vm", "playText failed", e)
                stopPlayback()
                _uiState.value = _uiState.value.copy(
                    playing = false, synthesizing = false,
                    error = e.message ?: str(R.string.err_play_failed)
                )
            }
        }
    }

    /** 更新某个音色的自定义参数（null 表示该项关闭单独设置，清除持久化值回退 config 默认） */
    fun updateVoiceParams(
        voice: String,
        speed: Float?, volume: Float?, pitch: Float?
    ) {
        if (speed != null) AppPrefs.setVoiceSpeed(voice, speed)
        else AppPrefs.clearVoiceSpeed(voice)
        if (volume != null) AppPrefs.setVoiceVolume(voice, volume)
        else AppPrefs.clearVoiceVolume(voice)
        if (pitch != null) AppPrefs.setVoicePitch(voice, pitch)
        else AppPrefs.clearVoicePitch(voice)
    }

    /** 清除某音色的自定义参数，回退到 config 默认 */
    fun resetVoiceParams(voice: String) {
        AppPrefs.clearVoiceParams(voice)
    }

    // ==================== 多引擎对话（多角色） ====================

    /** 更新对话脚本（多行，格式 "角色标签: 台词"，如 "角色1: 你好"） */
    fun onDialogScriptChange(newScript: String) {
        _dialog.value = _dialog.value.copy(script = newScript, error = null)
        persistDialog()
    }

    /** 更新指定索引角色的引擎 + 音色 + 头像 + 禁言 */
    fun updateRole(index: Int, engineCode: String?, voice: String?, avatar: String?, muted: Boolean? = null) {
        val roles = _dialog.value.roles.toMutableList()
        if (index !in roles.indices) return
        val cur = roles[index]
        roles[index] = cur.copy(
            engineCode = engineCode ?: cur.engineCode,
            voice = voice ?: cur.voice,
            avatar = avatar ?: cur.avatar,
            muted = muted ?: cur.muted
        )
        _dialog.value = _dialog.value.copy(roles = roles, error = null)
        persistDialog()
    }

    /** 切换禁言状态 */
    fun toggleMuteRole(index: Int) {
        val roles = _dialog.value.roles.toMutableList()
        if (index !in roles.indices) return
        roles[index] = roles[index].copy(muted = !roles[index].muted)
        _dialog.value = _dialog.value.copy(roles = roles, error = null)
        persistDialog()
    }

    /** 修改角色标签（群员昵称），同时同步脚本中的旧标签 */
    fun updateRoleLabel(index: Int, newLabel: String) {
        val roles = _dialog.value.roles.toMutableList()
        if (index !in roles.indices) return
        val oldLabel = roles[index].label
        roles[index] = roles[index].copy(label = newLabel)
        // 同步脚本中的角色标签
        val newScript = _dialog.value.script.lines().joinToString("\n") { line ->
            val idx = line.indexOfFirst { it == '：' || it == ':' }
            if (idx > 0 && line.substring(0, idx).trim() == oldLabel) {
                "$newLabel${line[idx]}${line.substring(idx + 1)}"
            } else line
        }
        _dialog.value = _dialog.value.copy(roles = roles, script = newScript, error = null)
        persistDialog()
    }

    /** 替换脚本中某一行（原行文本精确匹配）为新的 "角色：台词" */
    fun replaceDialogLine(oldRaw: String, newLine: String) {
        val newScript = _dialog.value.script.lines().joinToString("\n") { line ->
            if (line.trim() == oldRaw) newLine else line
        }
        _dialog.value = _dialog.value.copy(script = newScript, error = null)
        persistDialog()
    }

    /** 从脚本中删除某一行（原行文本精确匹配） */
    fun removeDialogLine(oldRaw: String) {
        val newScript = _dialog.value.script.lines()
            .filter { it.trim() != oldRaw }
            .joinToString("\n")
        _dialog.value = _dialog.value.copy(script = newScript, error = null)
        persistDialog()
    }

    /** 新增一个角色 */
    fun addRole() {
        val roles = _dialog.value.roles.toMutableList()
        val label = "角色${roles.size + 1}"
        val defaultEngine = EngineRegistry.all().getOrNull(roles.size % EngineRegistry.all().size)?.code ?: "microsoft"
        roles.add(DialogRole(label, defaultEngine, ""))
        _dialog.value = _dialog.value.copy(roles = roles, error = null)
        persistDialog()
    }

    /** 删除指定角色（至少保留一个） */
    fun removeRole(index: Int) {
        val roles = _dialog.value.roles.toMutableList()
        if (roles.size <= 1) return
        if (index in roles.indices) {
            roles.removeAt(index)
            _dialog.value = _dialog.value.copy(roles = roles, error = null)
            persistDialog()
        }
    }

    /**
     * 播放对话 —— 逐行解析 "角色标签: 台词"，按标签精确匹配角色配置，
     * 用对应引擎合成 PCM，统一重采样到 24kHz 后拼接为连续 PCM。
     */
    fun playDialog() {
        if (_dialog.value.playing) { stopDialog(); return }
        val state = _dialog.value
        val lines = state.script.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            _dialog.value = state.copy(error = str(R.string.dialog_need_input))
            return
        }
        val roles = state.roles
        if (roles.isEmpty()) {
            _dialog.value = state.copy(error = str(R.string.dialog_need_roles))
            return
        }

        // 角色标签 → 角色映射（支持 "角色1"/"角色2" 等任意标签）
        val roleMap = roles.associateBy { it.label.trim() }

        // 解析每行为 (角色标签, 台词)；分隔符支持英文 ":" 与中文 "："（如 角色3：床前明月光）
        data class Line(val roleLabel: String, val text: String)
        val parsed = ArrayList<Line>()
        for (line in lines) {
            val idx = line.indexOfAny(charArrayOf(':', '：'))
            if (idx <= 0 || idx >= line.length - 1) {
                _dialog.value = state.copy(error = str(R.string.dialog_bad_format, line))
                return
            }
            val role = line.substring(0, idx).trim()
            val text = line.substring(idx + 1).trim()
            if (!roleMap.containsKey(role)) {
                _dialog.value = state.copy(error = str(R.string.dialog_role_missing, role, roleMap.keys.joinToString(", ")))
                return
            }
            parsed.add(Line(role, text))
        }

        MttsLog.d("Dialog", "play start", mapOf("lines" to parsed.size, "roles" to roles.size))
        _dialog.value = state.copy(playing = true, error = null, progressLine = -1)

        // ★ 对话句间隔：本句单独间隔 > 角色默认间隔 > 全局默认（毫秒）
        val defaultIntervalMs = AppPrefs.getDialogIntervalMs().coerceIn(0, 10000)

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // 逐行合成并立即播放，进度行与实际发声严格同步。
                    stopRequested = false
                    for (i in parsed.indices) {
                        val line = parsed[i]
                        val role = roleMap[line.roleLabel] ?: continue
                        // 禁言群员：跳过合成（不发声）
                        if (role.muted) {
                            _dialog.value = _dialog.value.copy(progressLine = i)
                            continue
                        }
                        val engine = EngineRegistry.get(role.engineCode)
                            ?: throw RuntimeException(str(R.string.dialog_engine_missing, role.engineCode))
                        val voice = role.voice.ifBlank {
                            engine.listVoices().firstOrNull()?.shortName
                                ?: throw RuntimeException(str(R.string.dialog_role_no_voice, role.label))
                        }
                        MttsLog.d("Dialog", "line[$i] synth", mapOf(
                            "role" to role.label, "engine" to engine.code, "voice" to voice, "text" to line.text.take(16)))
                        // ★ 对话播放也应用音色参数（speed/volume/pitch）+ 用音色真实采样率直接播放（搜狗/百度 16kHz、微软 24kHz），不做有损重采样
                        //   ★ 从目标引擎自己的音色列表取默认（当前 TTS 页可能不在该引擎，找不到音色会参数错乱）
                        val engineVoices = engine.listVoices()
                        val (spd, vol, pit) = effectiveVoiceParams(engine, voice, engineVoices)
                        val playRate = voiceSampleRate(voice, engine.code, engineVoices)
                        // ★ 标记处理（停顿/音量/音调）：剥离标记后再合成，
                        //   与 TtsService 一致，避免 ⟨pause:ms⟩ 等标记被引擎当普通文本朗读出来
                        val pauses = TextPreprocessor.extractPauses(line.text)
                        val volMark = TextPreprocessor.extractVol(line.text)
                        val pitMark = TextPreprocessor.extractPitchAdjust(line.text)
                        val cleanText = TextPreprocessor.stripAllMarkers(line.text)
                        val effVol = (vol * volMark).coerceIn(0f, 2f)
                        val effPit = (pit + pitMark).coerceIn(-1f, 1f)
                        var pcm = engine.synthesize(cleanText, voice, spd, 1.0f, effPit)
                        if (pcm.isEmpty()) throw RuntimeException(str(R.string.dialog_role_no_audio, role.label))
                        // ★ 峰值归一化音量均衡
                        pcm = PcmResampler.balanceVolume16BitMono(pcm, effVol)
                        // 追加停顿静音（按引擎真实采样率生成，与 PCM 同采样率）
                        val silenceMs = pauses.sum()
                        if (silenceMs > 0) {
                            val silence = ByteArray(playRate * 2 * silenceMs / 1000)
                            pcm = pcm + silence
                        }

                        // 播放中被用户停止则中断后续行（用 stopRequested 标志，而非 track==null）
                        if (stopRequested) break
                        // 标记当前播放行（与实际发声同步）
                        _dialog.value = _dialog.value.copy(progressLine = i)
                        // 阻塞播放该行直到播完（stopDialog 置 stopRequested=true 打断等待）
                        playPcm(pcm, playRate)
                        // ★ 句间隔：本句播完后停顿再播下一句（可被停止打断）
                        //   优先级：本句单独间隔(>0) > 全局默认间隔
                        val lineInterval = AppPrefs.getLineIntervalMs(line.text).coerceIn(0, 10000)
                        val intervalMs = if (lineInterval > 0) lineInterval else defaultIntervalMs
                        if (intervalMs > 0 && i < parsed.size - 1) {
                            val until = System.currentTimeMillis() + intervalMs
                            while (System.currentTimeMillis() < until && !stopRequested) {
                                try { Thread.sleep(20) } catch (_: Throwable) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    MttsLog.e("Dialog", "play failed", e)
                    try { track?.pause() } catch (_: Throwable) {}
                    try { track?.flush() } catch (_: Throwable) {}
                    try { track?.release() } catch (_: Throwable) {}
                    track = null
                    _dialog.value = _dialog.value.copy(
                        playing = false, error = e.message ?: str(R.string.dialog_play_failed))
                }
            }
            stopRequested = false
            _dialog.value = _dialog.value.copy(playing = false, progressLine = -1)
        }
    }

    /** 停止对话播放 */
    fun stopDialog() {
        stopRequested = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try { track?.pause() } catch (_: Throwable) {}
                try { track?.flush() } catch (_: Throwable) {}
                try { track?.release() } catch (_: Throwable) {}
                track = null
            }
            _dialog.value = _dialog.value.copy(playing = false)
        }
    }

    /** 各引擎的合成采样率（微软 24kHz / 搜狗 16kHz / 百度 16kHz；未知引擎默认 24kHz） */
    private fun engineSampleRate(code: String): Int = when (code) {
        "sougou", "bdetts" -> 16000
        else -> 24000
    }

    /**
     * 音色真实采样率（主播放/试听用）：
     * 优先取 config.yaml 音色自带 sampleRate（搜狗/百度多为 16000，微软 24000），
     * 缺省回退引擎采样率。必须用真实采样率建 AudioTrack，
     * 否则 16k 数据按 24k 播放会变调 + 产生杂音。
     */
    private fun voiceSampleRate(voice: String, fallbackCode: String, voices: List<Voice> = _uiState.value.voices): Int {
        // ★ 采样率从「指定引擎音色列表」取（对话播放传入目标引擎 voices；否则找不到音色回退错误采样率）
        val v = voices.firstOrNull { it.shortName == voice }
        return v?.sampleRate?.takeIf { it > 0 } ?: engineSampleRate(fallbackCode)
    }

    override fun onCleared() {
        try { track?.pause() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
        super.onCleared()
    }
}