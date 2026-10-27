package com.mtts.app.util

import android.content.Context
import android.content.SharedPreferences
import com.mtts.app.MttsApplication
import com.mtts.app.R

/**
 * 轻量 Settings 持久化封装（SharedPreferences）。
 *
 * 存储两类数据：
 *  1. 测试文本 —— 可编辑的设置项，供「内置测试」按钮填入。
 *  2. 每个音色的合成参数（语速 speed / 音量 volume / 音高 pitch），
 *     按音色 shortName 维度存储；未设置时回退到 config.yaml 的自带值。
 */
object AppPrefs {

    private const val PREFS = "mtts_settings"
    private const val KEY_TEST_TEXT = "test_text"

    private const val KEY_VOICE_SPEED = "voice_speed_"
    private const val KEY_VOICE_VOLUME = "voice_volume_"
    private const val KEY_VOICE_PITCH = "voice_pitch_"

    /** 测试文本默认值（读字符串资源，避免硬编码） */
    val DEFAULT_TEST_TEXT: String
        get() = MttsApplication.ctx()?.getString(R.string.test_text_default) ?: ""

    private fun prefs(): SharedPreferences? =
        MttsApplication.ctx()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 测试文本 ----------

    fun getTestText(): String =
        prefs()?.getString(KEY_TEST_TEXT, DEFAULT_TEST_TEXT) ?: DEFAULT_TEST_TEXT

    fun setTestText(text: String) {
        prefs()?.edit()?.putString(KEY_TEST_TEXT, text)?.apply()
    }

    // ---------- 每音色合成参数（null = 未自定义，回退 config） ----------

    fun getVoiceSpeed(voice: String): Float? = getVoiceParam(KEY_VOICE_SPEED, voice)
    fun getVoiceVolume(voice: String): Float? = getVoiceParam(KEY_VOICE_VOLUME, voice)
    fun getVoicePitch(voice: String): Float? = getVoiceParam(KEY_VOICE_PITCH, voice)

    fun setVoiceSpeed(voice: String, v: Float) = setVoiceParam(KEY_VOICE_SPEED, voice, v)
    fun setVoiceVolume(voice: String, v: Float) = setVoiceParam(KEY_VOICE_VOLUME, voice, v)
    fun setVoicePitch(voice: String, v: Float) = setVoiceParam(KEY_VOICE_PITCH, voice, v)

    private fun getVoiceParam(prefix: String, voice: String): Float? {
        val p = prefs() ?: return null
        if (!p.contains(prefix + voice)) return null
        return p.getFloat(prefix + voice, 0f)
    }

    private fun setVoiceParam(prefix: String, voice: String, v: Float) {
        prefs()?.edit()?.putFloat(prefix + voice, v)?.apply()
    }

    /** 清除某音色的全部自定义参数（回退到 config 默认） */
    fun clearVoiceParams(voice: String) {
        prefs()?.edit()
            ?.remove(KEY_VOICE_SPEED + voice)
            ?.remove(KEY_VOICE_VOLUME + voice)
            ?.remove(KEY_VOICE_PITCH + voice)
            ?.apply()
    }

    /** 清除单项音色参数（关闭单独设置开关用；不清除则旧的自定义值会一直残留） */
    fun clearVoiceSpeed(voice: String) = prefs()?.edit()?.remove(KEY_VOICE_SPEED + voice)?.apply()
    fun clearVoiceVolume(voice: String) = prefs()?.edit()?.remove(KEY_VOICE_VOLUME + voice)?.apply()
    fun clearVoicePitch(voice: String) = prefs()?.edit()?.remove(KEY_VOICE_PITCH + voice)?.apply()

    // ---------- 全局合成参数（无 per-voice 单独配置时的回退基准） ----------

    private const val KEY_GLOBAL_SPEED = "global_speed"
    private const val KEY_GLOBAL_VOLUME = "global_volume"
    private const val KEY_GLOBAL_PITCH = "global_pitch"

    /** 全局语速（null = 未设置，跟随 config 自带/默认） */
    fun getGlobalSpeed(): Float? = getGlobalParam(KEY_GLOBAL_SPEED)
    /** 全局音量（null = 未设置，跟随 config 自带/默认） */
    fun getGlobalVolume(): Float? = getGlobalParam(KEY_GLOBAL_VOLUME)
    /** 全局音调（null = 未设置，跟随 config 自带/默认） */
    fun getGlobalPitch(): Float? = getGlobalParam(KEY_GLOBAL_PITCH)

    fun setGlobalSpeed(v: Float) { prefs()?.edit()?.putFloat(KEY_GLOBAL_SPEED, v)?.apply() }
    fun setGlobalVolume(v: Float) { prefs()?.edit()?.putFloat(KEY_GLOBAL_VOLUME, v)?.apply() }
    fun setGlobalPitch(v: Float) { prefs()?.edit()?.putFloat(KEY_GLOBAL_PITCH, v)?.apply() }

    /** 清除全局参数（回到跟随 config 自带/默认） */
    fun clearGlobalParams() {
        prefs()?.edit()
            ?.remove(KEY_GLOBAL_SPEED)
            ?.remove(KEY_GLOBAL_VOLUME)
            ?.remove(KEY_GLOBAL_PITCH)
            ?.apply()
    }

    private fun getGlobalParam(key: String): Float? {
        val p = prefs() ?: return null
        if (!p.contains(key)) return null
        return p.getFloat(key, -1f).takeIf { it >= 0 }
    }

    // ---------- 系统 TTS 参数（是否使用外部 App 传入的语速/音调） ----------

    private const val KEY_EXTERNAL_PARAMS = "use_external_params"

    /**
     * 系统 TTS 场景下的参数来源开关（默认 false，忽略系统传入参数）。
     *   true  = 使用系统 TTS 传入的 speechRate / pitch（阅读器、微信等 App 自己设的语速/音调生效）
     *   false = 忽略系统传入的参数，强制使用本 App 配置的全局/per-voice 参数
     *           （默认值。避免 OPPO 等厂商把"系统中间"的 rate=292 传给引擎，
     *            被 /100 误判成 2.92x，导致阅读器等外部 App 调用 mtts 整体偏快）
     */
    fun isExternalParamsEnabled(): Boolean = getBoolean(KEY_EXTERNAL_PARAMS, false)

    fun setExternalParamsEnabled(on: Boolean) { putBoolean(KEY_EXTERNAL_PARAMS, on) }

    // ---------- 通用字符串 / 布尔存取（供文本预处理层等使用） ----------

    fun getString(key: String, def: String = ""): String? =
        prefs()?.getString(key, def)

    fun putString(key: String, value: String) {
        prefs()?.edit()?.putString(key, value)?.apply()
    }

    fun getBoolean(key: String, def: Boolean = false): Boolean =
        prefs()?.getBoolean(key, def) ?: def

    fun putBoolean(key: String, value: Boolean) {
        prefs()?.edit()?.putBoolean(key, value)?.apply()
    }

    fun getInt(key: String, def: Int = 0): Int =
        prefs()?.getInt(key, def) ?: def

    fun putInt(key: String, value: Int) {
        prefs()?.edit()?.putInt(key, value)?.apply()
    }

    // ---------- 每个引擎页选中的发言人（切引擎/重启后恢复） ----------

    private const val KEY_SELECTED_VOICE = "selected_voice_"

    /** 某引擎上次选择的发言人（未保存返回 null） */
    fun getSelectedVoice(engine: String): String? =
        getString(KEY_SELECTED_VOICE + engine, "")?.takeIf { it.isNotBlank() }

    /** 保存某引擎当前选择的发言人 */
    fun setSelectedVoice(engine: String, voice: String) =
        putString(KEY_SELECTED_VOICE + engine, voice)

    // ---------- 每引擎音色显示顺序（长按拖动排序持久化） ----------

    private const val KEY_VOICE_ORDER_PREFIX = "voice_order_"

    /** 保存某引擎音色显示顺序（shortName 列表，换行分隔）；空=使用默认引擎顺序 */
    fun saveVoiceOrder(engine: String, voices: List<String>) {
        val sb = StringBuilder()
        for (v in voices) sb.append(v).append('\n')
        putString(KEY_VOICE_ORDER_PREFIX + engine, sb.toString())
    }

    /** 读取某引擎音色显示顺序（shortName 列表）；未自定义返回 null */
    fun loadVoiceOrder(engine: String): List<String>? {
        val raw = getString(KEY_VOICE_ORDER_PREFIX + engine, "") ?: ""
        if (raw.isBlank()) return null
        val out = ArrayList<String>()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return out.ifEmpty { null }
    }

    // ---------- 当前选中引擎（重启后恢复，尊重用户选择） ----------

    private const val KEY_SELECTED_ENGINE = "selected_engine"

    /** 上次选择的引擎 code（未保存返回 null） */
    fun getSelectedEngine(): String? =
        getString(KEY_SELECTED_ENGINE, "")?.takeIf { it.isNotBlank() }

    /** 保存当前选择的引擎 */
    fun setSelectedEngine(code: String) =
        putString(KEY_SELECTED_ENGINE, code)

    // ---------- 群聊对话持久化（角色 + 脚本） ----------

    private const val KEY_DIALOG_ROLES = "dialog_roles"      // 每行：标签|引擎|音色|头像|禁言
    private const val KEY_DIALOG_SCRIPT = "dialog_script"    // 纯文本脚本（角色：台词 每行）

    /** 保存群聊角色列表（序列化：标签|引擎|音色|头像|muted） */
    fun saveDialogRoles(roles: List<com.mtts.app.ui.DialogRole>) {
        val sb = StringBuilder()
        for (r in roles) {
            sb.append(r.label).append('|')
                .append(r.engineCode).append('|')
                .append(r.voice).append('|')
                .append(r.avatar).append('|')
                .append(r.muted)
                .append('\n')
        }
        putString(KEY_DIALOG_ROLES, sb.toString())
    }

    /** 读取群聊角色列表 */
    fun loadDialogRolesList(): List<com.mtts.app.ui.DialogRole> {
        val raw = getString(KEY_DIALOG_ROLES, "") ?: ""
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<com.mtts.app.ui.DialogRole>()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val p = t.split('|')
            if (p.size >= 2) {
                out.add(com.mtts.app.ui.DialogRole(
                    label = p[0].trim().ifBlank { "角色${out.size + 1}" },
                    engineCode = p[1].trim().ifBlank { "microsoft" },
                    voice = p.getOrNull(2)?.trim() ?: "",
                    avatar = p.getOrNull(3)?.trim() ?: "",
                    muted = p.getOrNull(4)?.trim()?.toBooleanStrictOrNull() ?: false
                ))
            }
        }
        return out
    }

    fun saveDialogScript(script: String) = putString(KEY_DIALOG_SCRIPT, script)
    fun loadDialogScript(): String = getString(KEY_DIALOG_SCRIPT, "") ?: ""

    // ---------- 对话行间隔（毫秒） ----------

    private const val KEY_DIALOG_INTERVAL_MS = "dialog_interval_ms"

    /** 对话播放时每句之间的默认间隔（毫秒），默认 0 */
    fun getDialogIntervalMs(): Int = getInt(KEY_DIALOG_INTERVAL_MS, 0)

    /** 设置对话默认间隔（毫秒，0=无间隔） */
    fun setDialogIntervalMs(v: Int) = putInt(KEY_DIALOG_INTERVAL_MS, v)

    // ---------- 单句自定义间隔（毫秒，按台词原文行精确匹配） ----------

    private const val KEY_LINE_INTERVAL_PREFIX = "line_interval_"

    /** 某行台词的单独间隔（0=跟随默认间隔） */
    fun getLineIntervalMs(rawLine: String): Int = getInt(KEY_LINE_INTERVAL_PREFIX + rawLine.trim(), 0)

    /** 设置某行台词的单独间隔（毫秒，0=跟随默认） */
    fun setLineIntervalMs(rawLine: String, ms: Int) {
        val key = KEY_LINE_INTERVAL_PREFIX + rawLine.trim()
        if (ms <= 0) prefs()?.edit()?.remove(key)?.apply()
        else putInt(key, ms)
    }

    // ---------- 对话功能（独立大设置） ----------

    private const val KEY_DIALOG_FEATURE_ENABLED = "dialog_feature_enabled"
    private const val KEY_VOICE_GROUPS = "dialog_voice_groups"        // 声音分组：group|性别 每行
    private const val KEY_CHARACTERS = "dialog_characters"            // 角色：名|分组名|引擎|音色|manual 每行
    private const val KEY_ABSTRACT_ROLES = "dialog_abstract_roles"    // 抽象角色：名|次数|性别 每行（朗读积累中）
    private const val KEY_NARRATORS = "dialog_narrators"              // 旁白：引擎|音色 每行（多选）
    private const val KEY_MUTED_VOICES = "dialog_muted_voices"        // 被屏蔽音色：引擎|音色 每行（多选）
    private const val KEY_EMOTIONS = "dialog_emotions"                // 情感：名|正则|引擎|音色|语速|音量|音调 每行

    fun isDialogFeatureEnabled(): Boolean = getBoolean(KEY_DIALOG_FEATURE_ENABLED, false)
    fun setDialogFeatureEnabled(on: Boolean) = putBoolean(KEY_DIALOG_FEATURE_ENABLED, on)

    // ---------- 抽象角色（朗读积累，满10次自动注册为正式角色） ----------

    /** 抽象角色：每行 "名|次数|性别"；空=清空 */
    fun saveAbstractRoles(roles: List<com.mtts.app.util.AbstractRole>) {
        val sb = StringBuilder()
        for (r in roles) sb.append(r.name).append('|').append(r.count).append('|').append(r.gender).append('\n')
        putString(KEY_ABSTRACT_ROLES, sb.toString())
    }
    fun loadAbstractRoles(): List<com.mtts.app.util.AbstractRole> {
        val out = ArrayList<com.mtts.app.util.AbstractRole>()
        for (line in (getString(KEY_ABSTRACT_ROLES, "") ?: "").lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val p = t.split('|')
            if (p[0].isNotBlank()) out.add(com.mtts.app.util.AbstractRole(
                name = p[0].trim(),
                count = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0,
                gender = p.getOrNull(2)?.trim()?.ifBlank { "女" } ?: "女"))
        }
        return out
    }

    /**
     * 朗读时对某说话人计数 +1；满 10 次自动注册为正式角色（按性别分组自动分配声音）。
     * 返回本次是否发生了"新注册"。
     */
    fun bumpAbstractRole(name: String, gender: String): Boolean {
        if (name.isBlank()) return false
        // 已注册过 → 不计
        if (loadCharacters().any { it.name == name }) return false
        var roles = loadAbstractRoles().toMutableList()
        val idx = roles.indexOfFirst { it.name == name }
        if (idx >= 0) {
            roles[idx] = roles[idx].copy(count = roles[idx].count + 1, gender = if (roles[idx].gender.isBlank()) gender else roles[idx].gender)
        } else {
            roles.add(com.mtts.app.util.AbstractRole(name = name, count = 1, gender = gender))
        }
        val cur = roles.firstOrNull { it.name == name } ?: return false
        if (cur.count >= HIGH_FREQ_THRESHOLD) {
            // 满 10 次 → 注册为正式角色（自动分配：按性别分组装载，未指定固定音色）
            val chars = loadCharacters().toMutableList()
            if (chars.none { it.name == name }) {
                chars.add(com.mtts.app.util.DialogCharacter(name = name, group = cur.gender, manual = false))
                saveCharacters(chars)
            }
            roles.removeAll { it.name == name }
            saveAbstractRoles(roles)
            return true
        }
        saveAbstractRoles(roles)
        return false
    }

    /**
     * 声音分组：每行 "分组名|性别|音色列表"，音色列表形如 "eng1:v1,eng2:v2"（逗号分隔多选，可空）。
     * 兼容旧格式 "分组名|性别" / "分组名|性别|引擎|音色"。
     */
    fun saveVoiceGroups(groups: List<com.mtts.app.util.DialogVoiceGroup>) {
        val sb = StringBuilder()
        for (g in groups) {
            sb.append(g.name).append('|').append(g.gender).append('|')
            val parts = g.voices.joinToString(",") { (e, v) -> "$e:$v" }
            sb.append(parts).append('\n')
        }
        putString(KEY_VOICE_GROUPS, sb.toString())
    }
    fun loadVoiceGroups(): List<com.mtts.app.util.DialogVoiceGroup> {
        val out = ArrayList<com.mtts.app.util.DialogVoiceGroup>()
        for (line in (getString(KEY_VOICE_GROUPS, "") ?: "").lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val p = t.split('|')
            if (p[0].isNotBlank()) {
                val voices = ArrayList<Pair<String, String>>()
                val raw = p.getOrNull(2)?.trim().orEmpty()
                if (raw.isNotEmpty() && !raw.contains(":")) {
                    // 旧格式第3/4字段：单个 引擎|音色
                    val e = raw
                    val v = p.getOrNull(3)?.trim().orEmpty()
                    if (e.isNotBlank() && v.isNotBlank()) voices.add(e to v)
                } else if (raw.isNotEmpty()) {
                    // 新格式：eng1:v1,eng2:v2
                    for (pair in raw.split(",")) {
                        val idx = pair.indexOf(':')
                        if (idx > 0) {
                            val e = pair.substring(0, idx).trim()
                            val v = pair.substring(idx + 1).trim()
                            if (e.isNotBlank() && v.isNotBlank()) voices.add(e to v)
                        }
                    }
                }
                out.add(com.mtts.app.util.DialogVoiceGroup(
                    name = p[0].trim(),
                    gender = p.getOrNull(1)?.trim()?.ifBlank { "女" } ?: "女",
                    voices = voices))
            }
        }
        return out
    }

    /** 角色：每行 "角色名|分组名|引擎|音色|manual"；空=清空 */
    fun saveCharacters(chars: List<com.mtts.app.util.DialogCharacter>) {
        val sb = StringBuilder()
        for (c in chars) sb.append(c.name).append('|').append(c.group).append('|').append(c.engineCode).append('|').append(c.voice).append('|').append(c.manual).append('\n')
        putString(KEY_CHARACTERS, sb.toString())
    }
    fun loadCharacters(): List<com.mtts.app.util.DialogCharacter> {
        val out = ArrayList<com.mtts.app.util.DialogCharacter>()
        for (line in (getString(KEY_CHARACTERS, "") ?: "").lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val p = t.split('|')
            if (p[0].isNotBlank()) out.add(com.mtts.app.util.DialogCharacter(
                name = p[0].trim(), group = p.getOrNull(1)?.trim().orEmpty(),
                engineCode = p.getOrNull(2)?.trim().orEmpty(), voice = p.getOrNull(3)?.trim().orEmpty(),
                manual = p.getOrNull(4)?.trim()?.toBooleanStrictOrNull() ?: false))
        }
        return out
    }

    /** 旁白：每行 "引擎|音色"（多选）；空=清空 */
    fun saveNarrators(narrators: List<com.mtts.app.util.DialogNarrator>) {
        val sb = StringBuilder()
        for (n in narrators) sb.append(n.engineCode).append('|').append(n.voice).append('\n')
        putString(KEY_NARRATORS, sb.toString())
    }
    fun loadNarrators(): List<com.mtts.app.util.DialogNarrator> {
        val out = ArrayList<com.mtts.app.util.DialogNarrator>()
        for (line in (getString(KEY_NARRATORS, "") ?: "").lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val p = t.split('|')
            if (p.size >= 2 && p[1].isNotBlank()) out.add(com.mtts.app.util.DialogNarrator(
                engineCode = p[0].trim(), voice = p[1].trim()))
        }
        return out
    }

    /** 被屏蔽音色：每行 "引擎|音色"（多选）；空=清空 */
    fun saveMutedVoices(pairs: List<Pair<String, String>>) {
        val sb = StringBuilder()
        for ((ec, vc) in pairs) sb.append(ec).append('|').append(vc).append('\n')
        putString(KEY_MUTED_VOICES, sb.toString())
    }
    fun loadMutedVoices(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (line in (getString(KEY_MUTED_VOICES, "") ?: "").lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val p = t.split('|')
            if (p.size >= 2 && p[1].isNotBlank()) out.add(p[0].trim() to p[1].trim())
        }
        return out
    }
    /** 某音色是否被屏蔽 */
    fun isVoiceMuted(engineCode: String, voice: String): Boolean =
        loadMutedVoices().any { it.first == engineCode && it.second == voice }

    /** 情感：每行 "名称|正则|引擎|音色|语速|音量|音调"；空=清空 */
    fun saveEmotions(emotions: List<com.mtts.app.util.DialogEmotion>) {
        val sb = StringBuilder()
        for (e in emotions) sb.append(e.name).append('|').append(e.regex).append('|')
            .append(e.engineCode).append('|').append(e.voice).append('|')
            .append(e.speed).append('|').append(e.volume).append('|').append(e.pitch).append('\n')
        putString(KEY_EMOTIONS, sb.toString())
    }
    fun loadEmotions(): List<com.mtts.app.util.DialogEmotion> {
        val out = ArrayList<com.mtts.app.util.DialogEmotion>()
        for (line in (getString(KEY_EMOTIONS, "") ?: "").lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val p = t.split('|')
            if (p.size >= 2 && p[0].isNotBlank()) out.add(com.mtts.app.util.DialogEmotion(
                name = p[0].trim(), regex = p.getOrNull(1)?.trim().orEmpty(),
                engineCode = p.getOrNull(2)?.trim().orEmpty(), voice = p.getOrNull(3)?.trim().orEmpty(),
                speed = p.getOrNull(4)?.trim()?.toFloatOrNull() ?: 1f,
                volume = p.getOrNull(5)?.trim()?.toFloatOrNull() ?: 1f,
                pitch = p.getOrNull(6)?.trim()?.toFloatOrNull() ?: 0f))
        }
        return out
    }

    // ---------- 旧对话识别（即将移除，兼容读取） ----------

    /** 兼容：旧对话识别开关（新对话功能未启用时也可被 TtsService 使用） */
    fun isLegacyDialogEnabled(): Boolean = getBoolean("preprocess_enable_dialog", false)

    /** 兼容：旧旁白角色读取（新功能未配置时回退） */
    fun getLegacyNarrator(): Pair<String, String>? {
        val raw = getString("preprocess_narration_role", "") ?: ""
        if (raw.isBlank()) return null
        val p = raw.trim().split('|')
        return if (p.size >= 2) p[0].trim() to p[1].trim() else null
    }

    // ---------- 百度引擎授权密钥（用户在引擎页填写，持久化保存） ----------

    private const val KEY_BD_APPKEY_PREFIX = "bd_appkey_"
    private const val KEY_BD_APPSECRET_PREFIX = "bd_appsecret_"

    /** 读取某 modelCode 的 appKey（未设置返回空串） */
    fun getBdAppKey(modelCode: String): String =
        getString(KEY_BD_APPKEY_PREFIX + modelCode, "") ?: ""

    /** 读取某 modelCode 的 appSecret（未设置返回空串） */
    fun getBdAppSecret(modelCode: String): String =
        getString(KEY_BD_APPSECRET_PREFIX + modelCode, "") ?: ""

    /** 保存某 modelCode 的 appKey */
    fun setBdAppKey(modelCode: String, value: String) =
        putString(KEY_BD_APPKEY_PREFIX + modelCode, value.trim())

    /** 保存某 modelCode 的 appSecret */
    fun setBdAppSecret(modelCode: String, value: String) =
        putString(KEY_BD_APPSECRET_PREFIX + modelCode, value.trim())

    // ---------- 微软嵌入式授权 license（用户在引擎页填写，持久化保存） ----------

    private const val KEY_MS_LICENSE = "ms_embedded_license"

    /** 读取微软嵌入式 license（未设置返回空串） */
    fun getMsLicense(): String =
        getString(KEY_MS_LICENSE, "") ?: ""

    /** 保存微软嵌入式 license */
    fun setMsLicense(value: String) =
        putString(KEY_MS_LICENSE, value.trim())

    // ---------- 保活 / 退出开关 ----------

    private const val KEY_KEEP_ALIVE = "keep_alive_enabled"

    /** 是否启用常驻保活服务（true=常驻通知；false=已退出/停用；默认关） */
    fun isKeepAliveEnabled(): Boolean = getBoolean(KEY_KEEP_ALIVE, false)

    fun setKeepAliveEnabled(on: Boolean) = putBoolean(KEY_KEEP_ALIVE, on)

    // ---------- sherpa 引擎启动预热开关 ----------

    private const val KEY_SHERPA_WARMUP = "sherpa_warmup_enabled"

    /** 是否在启动时预热 sherpa 模型（true=后台预加载所有模型，首次合成不卡；false=首次合成时才加载；默认关） */
    fun isSherpaWarmupEnabled(): Boolean = getBoolean(KEY_SHERPA_WARMUP, false)

    fun setSherpaWarmupEnabled(on: Boolean) = putBoolean(KEY_SHERPA_WARMUP, on)

    // ---------- 本地 HTTP 网关 ----------

    private const val KEY_HTTP_ENABLED = "http_gateway_enabled"
    private const val KEY_HTTP_PORT = "http_gateway_port"
    private const val DEFAULT_HTTP_PORT = 8080
    private const val KEY_HTTP_TOKEN = "http_gateway_token"
    private const val LEGACY_HTTP_TOKEN = "mttsdev123"

    /** HTTP 网关默认关闭；保留用户已显式保存的开关设置。 */
    fun isHttpEnabled(): Boolean = getBoolean(KEY_HTTP_ENABLED, false)
    fun setHttpEnabled(on: Boolean) = putBoolean(KEY_HTTP_ENABLED, on)

    /** HTTP 端口（默认 8080；若被占用 LocalHttpServer 会自动回退到其后端口） */
    fun httpPort(): Int = getInt(KEY_HTTP_PORT, DEFAULT_HTTP_PORT).coerceIn(1024, 65535)
    fun setHttpPort(port: Int) = putInt(KEY_HTTP_PORT, port.coerceIn(1024, 65535))

    /** 首次使用或遇到旧默认令牌时，生成并保存安装实例独有的随机令牌。 */
    @Synchronized
    fun httpToken(): String {
        val p = checkNotNull(prefs()) { "HTTP token storage is unavailable" }
        val saved = p.getString(KEY_HTTP_TOKEN, "").orEmpty().trim()
        if (saved.isNotEmpty() && saved != LEGACY_HTTP_TOKEN) return saved
        val generated = newHttpToken()
        check(p.edit().putString(KEY_HTTP_TOKEN, generated).commit()) {
            "Failed to persist HTTP token"
        }
        return generated
    }

    /** 清空令牌或重新填写旧默认值时，改为生成新的随机令牌。 */
    @Synchronized
    fun setHttpToken(token: String) {
        val p = checkNotNull(prefs()) { "HTTP token storage is unavailable" }
        val trimmed = token.trim()
        val value = if (trimmed.isEmpty() || trimmed == LEGACY_HTTP_TOKEN) newHttpToken() else trimmed
        check(p.edit().putString(KEY_HTTP_TOKEN, value).commit()) {
            "Failed to persist HTTP token"
        }
    }

    private fun newHttpToken(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** 任意 HTTP 开关或端口被改后是否需要重启 HTTP 服务 */
    fun httpNeedsRestart(): Boolean = isHttpEnabled()

    /**
     * 同步落盘保活开关（用于退出场景：必须在杀进程前确保 false 已写入磁盘，
     * 否则异步 apply 未落盘、进程被杀后下次启动又读到默认 true，KeepAlive 会被重新拉起）。
     */
    fun setKeepAliveEnabledSync(on: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_KEEP_ALIVE, on)?.commit()
    }

    // ---------- 音效功能（BGM + 短音效） ----------

    private const val KEY_BGM_ENABLED = "sfx_bgm_enabled"
    private const val KEY_BGM_PATH = "sfx_bgm_path"
    private const val KEY_BGM_VOLUME = "sfx_bgm_volume"
    private const val KEY_SFX_ENABLED = "sfx_sfx_enabled"
    private const val KEY_SFX_VOLUME = "sfx_sfx_volume"
    private const val KEY_SFX_MAP = "sfx_map"          // 每行：标记名|文件路径
    private const val KEY_SFX_KEYWORDS = "sfx_keywords" // 每行：关键词|标记名

    fun isBgmEnabled(): Boolean = getBoolean(KEY_BGM_ENABLED, false)
    fun setBgmEnabled(on: Boolean) = putBoolean(KEY_BGM_ENABLED, on)

    fun getBgmPath(): String = getString(KEY_BGM_PATH, "") ?: ""
    fun setBgmPath(path: String) = putString(KEY_BGM_PATH, path.trim())

    fun getBgmVolume(): Float = prefs()?.getFloat(KEY_BGM_VOLUME, 0.3f) ?: 0.3f
    fun setBgmVolume(v: Float) { prefs()?.edit()?.putFloat(KEY_BGM_VOLUME, v)?.apply() }

    fun isSfxEnabled(): Boolean = getBoolean(KEY_SFX_ENABLED, true)
    fun setSfxEnabled(on: Boolean) = putBoolean(KEY_SFX_ENABLED, on)

    fun getSfxVolume(): Float = prefs()?.getFloat(KEY_SFX_VOLUME, 0.8f) ?: 0.8f
    fun setSfxVolume(v: Float) { prefs()?.edit()?.putFloat(KEY_SFX_VOLUME, v)?.apply() }

    /** 音效注册表：每行 "标记名|文件路径" */
    fun saveSfxMap(entries: List<Pair<String, String>>) {
        val sb = StringBuilder()
        for ((name, path) in entries) sb.append(name).append('|').append(path).append('\n')
        putString(KEY_SFX_MAP, sb.toString())
    }
    fun loadSfxMap(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (line in (getString(KEY_SFX_MAP, "") ?: "").lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val p = t.split('|')
            if (p.size >= 2 && p[0].isNotBlank() && p[1].isNotBlank()) out.add(p[0].trim() to p[1].trim())
        }
        return out
    }

    /** 关键词触发：每行 "关键词|标记名" */
    fun saveSfxKeywords(entries: List<Pair<String, String>>) {
        val sb = StringBuilder()
        for ((kw, name) in entries) sb.append(kw).append('|').append(name).append('\n')
        putString(KEY_SFX_KEYWORDS, sb.toString())
    }
    fun loadSfxKeywords(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (line in (getString(KEY_SFX_KEYWORDS, "") ?: "").lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val p = t.split('|')
            if (p.size >= 2 && p[0].isNotBlank() && p[1].isNotBlank()) out.add(p[0].trim() to p[1].trim())
        }
        return out
    }

    // ---------- 内置示例音效（assets/soundfx，Mixkit，可免费商用） ----------

    private const val KEY_SFX_BUILTIN_IMPORTED = "sfx_builtin_imported_v2"

    /** 内置音效在私有目录的存放子目录名 */
    const val BUILTIN_SFX_DIR = "soundfx"

    /**
     * 内置音效 → 自然语言触发关键词。
     * 泛词（如「脚步」「雨」「开门」「魔法」）会被多个音效共享，
     * 注册时形成多候选随机池：命中后每次随机选一个，不锁死同一个声音。
     * 未列出的条目回退为同名关键词。
     */
    val BUILTIN_SFX_KEYWORDS: Map<String, List<String>> = mapOf(
        "rain" to listOf("雨", "下雨", "雨声", "雨滴", "大雨", "小雨"),
        "thunder" to listOf("雷", "雷声", "打雷", "雷鸣", "惊雷"),
        "footstep" to listOf("脚步", "脚步声", "走路", "行走"),
        "dog" to listOf("狗叫", "狗吠", "犬吠", "汪汪"),
        "knock" to listOf("敲门", "叩门", "敲门声"),
        "中世纪门锁" to listOf("开锁", "上锁", "门锁", "锁"),
        "中型狗低吼" to listOf("低吼", "狗吼", "呜咽"),
        "中型狗吠" to listOf("狗吠", "狗叫", "汪汪"),
        "仙女传送" to listOf("传送", "瞬移", "魔法"),
        "仙女闪亮" to listOf("闪亮", "星光", "闪烁", "魔法"),
        "仙女闪光" to listOf("星光", "星尘", "闪光", "魔法"),
        "倒水入杯" to listOf("倒水", "倒茶", "倒水声"),
        "光法术" to listOf("圣光", "法术", "魔法", "光芒"),
        "公鸡打鸣" to listOf("公鸡", "鸡叫", "鸡鸣"),
        "冻土脚步" to listOf("脚步", "硬地脚步", "踩地"),
        "剑刃挥动" to listOf("挥剑", "舞剑", "剑"),
        "剑斩嗖" to listOf("斩击", "剑", "挥砍"),
        "匕首挥砍" to listOf("匕首", "小刀", "刀"),
        "卡通猫喵" to listOf("猫叫", "喵", "卡通"),
        "卡通虫快跑" to listOf("虫子跑", "虫子"),
        "吱呀关门" to listOf("关门", "吱呀", "关门声"),
        "吱呀门开" to listOf("开门", "门开", "吱呀", "推门"),
        "吱呀门开关" to listOf("开门", "关门", "吱呀"),
        "喝水" to listOf("喝水", "饮水", "咕咚"),
        "嘎吱脚步循环" to listOf("脚步", "嘎吱"),
        "大猫呼噜" to listOf("呼噜", "猫咪", "猫"),
        "女巫嗖" to listOf("女巫", "巫师", "魔法"),
        "巨人脚步" to listOf("脚步", "巨人", "巨大"),
        "快乐小狗吠" to listOf("小狗", "幼犬", "狗叫", "汪汪"),
        "快剑挥砍" to listOf("挥砍", "剑", "斩"),
        "快速剑击" to listOf("剑击", "格挡", "剑"),
        "怒狗低吼" to listOf("狗", "低吼", "凶狗"),
        "怪物脚步" to listOf("脚步", "怪物", "怪兽"),
        "恐怖木门开" to listOf("开门", "木门", "恐怖"),
        "愤怒猫喵" to listOf("猫叫", "喵", "哈气"),
        "敲门厚木门" to listOf("敲门", "叩门"),
        "木地板吱呀" to listOf("地板", "吱呀", "木地板"),
        "机器人脚步" to listOf("脚步", "机器人", "机械"),
        "林中小鸟" to listOf("鸟叫", "鸟鸣", "小鸟"),
        "柜门关" to listOf("关门", "柜门"),
        "森林奔跑" to listOf("奔跑", "跑", "树林"),
        "森林跑动" to listOf("跑动", "奔跑", "草丛"),
        "水泡" to listOf("冒泡", "咕噜", "水声"),
        "水花" to listOf("水花", "溅水", "泼水", "水声"),
        "泥泞脚步" to listOf("脚步", "泥泞", "踩泥"),
        "深水泡" to listOf("冒泡", "咕嘟", "水声"),
        "烦狗吠" to listOf("狗叫", "狗吠", "烦"),
        "热带鸟叫" to listOf("鸟叫", "热带鸟", "鹦鹉"),
        "牛叫" to listOf("牛叫", "哞", "牛"),
        "狗吠两声" to listOf("狗叫", "狗吠", "汪汪"),
        "狮子咆哮" to listOf("狮子", "狮吼", "咆哮"),
        "狼嚎" to listOf("狼", "狼嚎", "狼叫"),
        "猫咪喵叫" to listOf("猫叫", "喵", "猫咪"),
        "监狱铁门" to listOf("铁门", "牢门", "开门", "关门"),
        "石巨人行走" to listOf("脚步", "巨人", "石巨人"),
        "硬币叮当" to listOf("叮当", "金币", "钱币"),
        "硬币声" to listOf("硬币", "钱币", "金币"),
        "硬币处理" to listOf("硬币", "数钱", "钱币"),
        "老教堂门开" to listOf("开门", "教堂", "老门"),
        "草地脚步" to listOf("脚步", "草地", "踩草"),
        "草地行走" to listOf("脚步", "草地", "散步"),
        "蟋蟀鸣叫" to listOf("蟋蟀", "虫鸣", "蛐蛐"),
        "行军脚步" to listOf("脚步", "行军", "踏步"),
        "跳入水中" to listOf("跳水", "落水", "水声"),
        "重型推拉门" to listOf("推拉门", "拉门"),
        "重草地脚步" to listOf("脚步", "草地", "沉重"),
        "野兽咆哮" to listOf("野兽", "兽吼", "咆哮"),
        "金属剑击" to listOf("剑击", "金属", "兵刃"),
        "金属挥砍" to listOf("挥砍", "刀", "金属"),
        "金币奖励" to listOf("金币", "奖励", "钱币"),
        "金币胜利" to listOf("金币", "胜利", "钱币"),
        "隧道脚步循环" to listOf("脚步", "隧道", "回声"),
        "雁群" to listOf("大雁", "雁叫", "鸟叫"),
        "雪地脚步" to listOf("脚步", "雪地", "踩雪"),
        "饿猫喵" to listOf("猫叫", "喵", "饿"),
        "马嘶" to listOf("马叫", "马嘶", "马"),
        "马嘶鸣" to listOf("马叫", "马嘶", "长嘶"),
        "高跟鞋脚步" to listOf("脚步", "高跟鞋"),
        "魔法咒语" to listOf("咒语", "施法", "魔法"),
        "魔法哨声" to listOf("哨声", "魔法"),
        "魔法棒闪光" to listOf("魔法棒", "闪光", "魔法"),
        "魔法通知铃" to listOf("铃声", "通知", "提示音"),
        "魔法闪光" to listOf("闪光", "魔法"),
        "魔法闪光嗖" to listOf("闪现", "魔法", "闪光"),
        "龙咆哮" to listOf("龙", "龙吼", "咆哮")
    )

    /**
     * 首次启动把 assets/soundfx 内置音效解压到私有目录并注册进音效表 + 关键词表。
     * 动态枚举 assets：目录里放多少就注册多少；文件名（去扩展名）即标记名。
     * 只补缺：用户已注册同名标记则跳过，绝不覆盖用户配置。
     */
    fun ensureBuiltinSfxImported() {
        val ctx = MttsApplication.ctx() ?: return
        if (getBoolean(KEY_SFX_BUILTIN_IMPORTED, false)) return
        val names = try { ctx.assets.list(BUILTIN_SFX_DIR)?.toList() ?: emptyList() } catch (_: Throwable) { emptyList() }
        if (names.isEmpty()) return
        val dir = java.io.File(ctx.getExternalFilesDir(null), BUILTIN_SFX_DIR)
        dir.mkdirs()
        val map = loadSfxMap().toMutableList()
        val kws = loadSfxKeywords().toMutableList()
        var changed = false
        for (asset in names) {
            val name = asset.substringBeforeLast('.').trim()
            if (name.isBlank()) continue
            val dest = java.io.File(dir, asset)
            if (!dest.exists() || dest.length() == 0L) {
                try {
                    ctx.assets.open("$BUILTIN_SFX_DIR/$asset").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (t: Throwable) { continue }
            }
            if (dest.exists() && dest.length() > 0L && map.none { it.first == name }) {
                map.add(name to dest.absolutePath)
                // 开箱即用关键词：映射表优先（泛词共享→随机池），否则同名
                val kwsFor = BUILTIN_SFX_KEYWORDS[name] ?: listOf(name)
                for (kw in kwsFor) {
                    if (kw.isNotBlank()) kws.add(kw to name)
                }
                changed = true
            }
        }
        if (changed) {
            saveSfxMap(map)
            saveSfxKeywords(kws)
            SoundEffectManager.clearCache()
        }
        putBoolean(KEY_SFX_BUILTIN_IMPORTED, true)
    }
}