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

    // ---------- 保活 / 退出开关 ----------

    private const val KEY_KEEP_ALIVE = "keep_alive_enabled"

    /** 是否启用常驻保活服务（true=常驻通知；false=已退出/停用） */
    fun isKeepAliveEnabled(): Boolean = getBoolean(KEY_KEEP_ALIVE, true)

    fun setKeepAliveEnabled(on: Boolean) = putBoolean(KEY_KEEP_ALIVE, on)

    // ---------- sherpa 引擎启动预热开关 ----------

    private const val KEY_SHERPA_WARMUP = "sherpa_warmup_enabled"

    /** 是否在启动时预热 sherpa 模型（true=后台预加载所有模型，首次合成不卡；false=首次合成时才加载） */
    fun isSherpaWarmupEnabled(): Boolean = getBoolean(KEY_SHERPA_WARMUP, true)

    fun setSherpaWarmupEnabled(on: Boolean) = putBoolean(KEY_SHERPA_WARMUP, on)

    // ---------- 本地 HTTP 网关 ----------

    private const val KEY_HTTP_ENABLED = "http_gateway_enabled"
    private const val KEY_HTTP_PORT = "http_gateway_port"
    private const val DEFAULT_HTTP_PORT = 8080
    private const val KEY_HTTP_TOKEN = "http_gateway_token"
    private const val DEFAULT_HTTP_TOKEN = "mttsdev123"

    /** HTTP 网关是否启用（默认开） */
    fun isHttpEnabled(): Boolean = getBoolean(KEY_HTTP_ENABLED, true)
    fun setHttpEnabled(on: Boolean) = putBoolean(KEY_HTTP_ENABLED, on)

    /** HTTP 端口（默认 8080；若被占用 LocalHttpServer 会自动回退到其后端口） */
    fun httpPort(): Int = getInt(KEY_HTTP_PORT, DEFAULT_HTTP_PORT).coerceIn(1024, 65535)
    fun setHttpPort(port: Int) = putInt(KEY_HTTP_PORT, port.coerceIn(1024, 65535))

    /** HTTP 认证令牌（X-Auth-Token，默认 mttsdev123） */
    fun httpToken(): String =
        (getString(KEY_HTTP_TOKEN, "") ?: "").trim().ifBlank { DEFAULT_HTTP_TOKEN }
    fun setHttpToken(token: String) = putString(KEY_HTTP_TOKEN, token.trim())

    /** 任意 HTTP 开关或端口被改后是否需要重启 HTTP 服务 */
    fun httpNeedsRestart(): Boolean = isHttpEnabled()

    /**
     * 同步落盘保活开关（用于退出场景：必须在杀进程前确保 false 已写入磁盘，
     * 否则异步 apply 未落盘、进程被杀后下次启动又读到默认 true，KeepAlive 会被重新拉起）。
     */
    fun setKeepAliveEnabledSync(on: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_KEEP_ALIVE, on)?.commit()
    }
}