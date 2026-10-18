package com.mtts.app.util

import com.mtts.app.engine.VoicePackageManager
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 文本预处理层 —— 小说/朗读文本进入 TTS 引擎前统一处理。
 *
 * 三个独立能力（每个都有开关 + 额外配置字段，UI 二级页管理）：
 *  1. 文字替换：读取语音包根目录 replaces.yaml（MultiTTS ReplaceConfig 格式）
 *     + AppPrefs 附加规则（同样 MultiTTS 格式）+ 内置兜底。
 *     支持 MultiTTS 标准字段 activate/regex/source/target/group/name。
 *  2. 多音字纠正：内置常见多音字表 + AppPrefs 附加多音字表（MultiTTS 格式），
 *     将多音字替换为同音字/读音标注（如 干→淦、还→环、长→掌、得→德），纠正 TTS 误读。
 *  3. 对话识别：识别引号（""/“”/「」/『』）内的对白，可分配发言人（多引擎多人），
 *     对白段用角色音色合成、叙述段用默认音色，实现"一句话一个发言人"。
 *
 * 【额外配置字段 · 我们独有】pauseMs（毫秒停顿）：
 *  MultiTTS 规则只有 source→target 文字替换；我们新增 `pauseMs` 字段：
 *  - 替换功能不换文字，只加停顿：source=等一下, target=等一下, pauseMs=1000
 *    → 读到「等一下」时文字不变，合成完后停顿 1 秒。
 *  - 处理时在替换结果里注入停顿标记 ⟨pause:ms⟩；TtsService 逐段合成时
 *    识别该标记，在 PCM 输出中追加对应毫秒的静音（24kHz/16bit/mono）。
 *
 * 配置优先级：语音包 replaces.yaml（主）→ AppPrefs 附加（辅）→ 内置兜底。
 */
object TextPreprocessor {

    /** 引号对（含中文引号）。用于对话识别。 */
    private val QUOTE_PAIRS = listOf(
        "\u201c" to "\u201d",  // “ ”
        "\u2018" to "\u2019",  // ‘ ’
        "「" to "」",
        "\"" to "\"",
        "『" to "』"
    )

    /** 停顿标记：替换时注入文本，合成阶段识别为静音。格式 ⟨pause:ms⟩ */
    const val PAUSE_PREFIX = "\u27E8pause:"
    const val PAUSE_SUFFIX = "\u27E9"
    private val PAUSE_RE = Regex("""\u27E8pause:(\d+)\u27E9""")

    /** 占位符（用于标记保护）：选控制字符 \u0001/\u0002，位于
     *  中文/字母/数字/标点范围之外，保证替换/多音字/智能识别/标点停顿等步骤的正则
     *  **不会命中占位符**，从而不会跨标记替换、也不会替换掉标记内部的 `:` 等字符。 */
    private const val PAD1 = '\u0001'
    private const val PAD2 = '\u0002'

    /** 匹配所有已注入标记（pause/vol/pit），供「标记保护」占位使用 */
    private val MARKER_ANY_RE =
        Regex("""\u27E8(?:pause:\d+|vol:[\d.]+|pit:[-\d.]+)\u27E9""")

    /** 兜底清理畸形残缺标记：缺冒号（⟨pause400⟩）、空（⟨pause⟩）、内容非法等 */
    private val MARKER_BROKEN_RE =
        Regex("""\u27E8\s*(?:pause|vol|pit)\s*[^⟩\u0000-\u001f]{0,12}\s*\u27E9""", RegexOption.IGNORE_CASE)

    /** 保护已注入标记：把每个标记替换成 \u0001+序号+\u0002 占位符（不会被后续正则命中）。
     *  序号写入 stored（累加），从 1 起与 pad1 后的数字对应；process 流水线每步都调用本函数，
     *  确保「上一步注入的标记」在「下一步跑正则之前」被占位化，杜绝跨标记替换。 */
    private fun protectInto(text: String, stored: MutableList<String>): String =
        MARKER_ANY_RE.replace(text) { m ->
            stored.add(m.value)
            "$PAD1${stored.size}$PAD2"
        }

    /** 还原占位符为原始标记（占位符序号与 stored 一一对应；残留的落单控制字符一并清掉） */
    private fun restoreMarkers(text: String, stored: List<String>): String {
        val re = Regex("""\u0001(\d+)\u0002""")
        return re.replace(text) { m ->
            val i = m.groupValues[1].toIntOrNull() ?: 0
            stored.getOrNull(i - 1) ?: ""
        }.replace(PAD1, ' ').replace(PAD2, ' ')
    }

    /** 音量标记：格式 ⟨vol:倍率⟩，TtsService 合成时放大/缩小音量 */
    const val VOL_PREFIX = "\u27E8vol:"
    const val VOL_SUFFIX = "\u27E9"
    private val VOL_RE = Regex("""\u27E8vol:([\d.]+)\u27E9""")

    /** 音调标记：格式 ⟨pit:值⟩，TtsService 合成时抬高/降低音调 */
    const val PIT_PREFIX = "\u27E8pit:"
    const val PIT_SUFFIX = "\u27E9"
    private val PIT_RE = Regex("""\u27E8pit:([-\d.]+)\u27E9""")

    /** 单条替换规则（对齐 MultiTTS ReplaceConfig + 新增 pauseMs 字段） */
    data class ReplaceRule(
        val name: String = "",
        val group: String = "默认分组",
        val activate: Boolean = true,
        val regex: Boolean = false,
        val source: String = "",
        val target: String = "",
        val pauseMs: Int = 0        // ★ 额外配置字段：替换后停顿毫秒（我们独有）
    )

    /** 发言人角色（对话识别用）—— 绑定引擎 + 音色 */
    data class DialogRoleCfg(
        val label: String = "对白1",
        val engineCode: String = "microsoft",
        val voice: String = ""
    )

    /**
     * 角色规则（对齐 MultiTTS RoleRule）—— 用正则匹配文本段并绑定发言人。
     * 这是 MultiTTS 分配旁白/对白人物的核心：
     *  - regex 在文本上匹配；命中段落 → 用 speakerId 绑定引擎 + voice 合成
     *  - 未命中任何规则的段落 → 旁白（默认音色或旁白角色）
     *  - isAbstract=抽象规则：只做归属锚点不直接发音（如"他说"）
     *  - matchBefore/matchAfter：控制前后文匹配方向
     */
    data class RoleRule(
        val name: String = "",            // 规则名（人物名）
        val regex: String = "",           // 匹配正则
        val engineCode: String = "",      // 绑定发言人引擎（空=默认引擎）
        val voice: String = "",           // 绑定发言人音色（空=默认音色）
        val activate: Boolean = true,     // 启用
        val isAbstract: Boolean = false,  // 抽象规则（锚点）
        val matchBefore: Boolean = true,  // 匹配前置文本
        val matchAfter: Boolean = true    // 匹配后置文本
    )

    /** 角色匹配结果段：文本 + 是否对白 + 绑定引擎/音色（空=旁白默认） */
    data class RoleSeg(
        val text: String,
        val isSpeech: Boolean,        // true=对白（引号内/规则命中人物），false=旁白
        val engineCode: String = "",  // 空=用旁白/默认引擎
        val voice: String = ""
    )

    /**
     * 多音字下属规则 —— 上下文词 → 同音替换词。
     * 例：长 → 「长江 → 常江」（此处"长"读 cháng，同音"常"）。
     * 用同音字而非拼音：拼音会被 TTS 当英文逐字母念出来。
     */
    data class PolyRule(
        val word: String = "",        // 上下文词（含主字），如「长江」
        val target: String = "",      // 同音替换，如「常江」
        val activate: Boolean = true,
        val builtinKey: String = ""   // 内置原始词（仅内置规则用；附加表无此概念，保持空）
    )

    /**
     * 多音字条目 —— 主字 + 多条下属规则（识别不同上下文的不同读音）。
     * 例：长（cháng/zhǎng）→
     *   长江→常江、长城→常城、长征→常征   （cháng 读"常"）
     *   市长→市掌、校长→校掌、班长→班掌   （zhǎng 读"掌"）
     * 每条规则是一个"词 → 同音词"替换，应用时整词替换。
     */
    data class PolyEntry(
        val char: String = "",                 // 主字，如「长」
        val rules: List<PolyRule> = emptyList()
    )

    // ==================== 配置键（AppPrefs） ====================

    // 功能开关
    private const val KEY_ENABLE_REPLACE = "preprocess_enable_replace"
    private const val KEY_ENABLE_POLY = "preprocess_enable_poly"
    private const val KEY_ENABLE_DIALOG = "preprocess_enable_dialog"
    private const val KEY_ENABLE_EMOJI = "preprocess_enable_emoji"       // ★ 表情符号处理（emoji→文字 + 标点→停顿）
    private const val KEY_ENABLE_SMART = "preprocess_enable_smart"       // ★ 智能识别（等一下→停顿、大声说→音量）

    // 附加配置字段（用户自定义，MultiTTS 同款格式）
    private const val KEY_EXTRA_DICT = "preprocess_extra_dict"          // 附加替换规则 YAML（MultiTTS ReplaceConfig 格式）
    private const val KEY_EXTRA_POLY = "preprocess_extra_poly"          // 附加多音字 YAML（MultiTTS ReplaceConfig 格式）
    private const val KEY_POLY_OVERRIDES = "preprocess_poly_overrides"  // 内置多音字规则覆盖（编辑/删除/开关）
    // ★ 与多音字对齐：其余功能内置规则覆盖（编辑/删除/开关）
    private const val KEY_REPLACE_OVERRIDES = "preprocess_replace_overrides"  // 内置替换规则覆盖
    private const val KEY_EMOJI_OVERRIDES = "preprocess_emoji_overrides"      // 内置 emoji 映射覆盖
    private const val KEY_PUNCT_OVERRIDES = "preprocess_punct_overrides"      // 内置标点停顿规则覆盖
    private const val KEY_SMART_OVERRIDES = "preprocess_smart_overrides"      // 内置智能识别规则覆盖
    private const val KEY_DIALOG_ROLES = "preprocess_dialog_roles"      // 发言人（角色名|引擎|音色 每行）
    private const val KEY_DIALOG_ROLE_MODE = "preprocess_dialog_role_mode" // 分配模式: cycle=轮询 / same=全部用第一个 / off=不分
    private const val KEY_NARRATION_ROLE = "preprocess_narration_role"  // ★ 旁白/叙述段发言人（引擎|音色；空=用默认音色）
    private const val KEY_ROLE_RULES = "preprocess_role_rules"          // ★ 角色规则（MultiTTS RoleRule）：正则→发言人 每行

    // 各功能开关
    fun isReplaceEnabled(): Boolean = AppPrefs.getBoolean(KEY_ENABLE_REPLACE, true)
    fun isPolyEnabled(): Boolean = AppPrefs.getBoolean(KEY_ENABLE_POLY, true)
    fun isDialogEnabled(): Boolean = AppPrefs.getBoolean(KEY_ENABLE_DIALOG, false)
    fun isEmojiEnabled(): Boolean = AppPrefs.getBoolean(KEY_ENABLE_EMOJI, true)   // ★ 表情符号处理默认开
    fun isSmartEnabled(): Boolean = AppPrefs.getBoolean(KEY_ENABLE_SMART, true)   // ★ 智能识别默认开

    fun setReplaceEnabled(on: Boolean) = AppPrefs.putBoolean(KEY_ENABLE_REPLACE, on)
    fun setPolyEnabled(on: Boolean) = AppPrefs.putBoolean(KEY_ENABLE_POLY, on)
    fun setDialogEnabled(on: Boolean) = AppPrefs.putBoolean(KEY_ENABLE_DIALOG, on)
    fun setEmojiEnabled(on: Boolean) = AppPrefs.putBoolean(KEY_ENABLE_EMOJI, on)
    fun setSmartEnabled(on: Boolean) = AppPrefs.putBoolean(KEY_ENABLE_SMART, on)

    // 附加配置字段读写
    fun getExtraDictText(): String = AppPrefs.getString(KEY_EXTRA_DICT, "") ?: ""
    fun setExtraDictText(text: String) = AppPrefs.putString(KEY_EXTRA_DICT, text)

    fun getExtraPolyText(): String = AppPrefs.getString(KEY_EXTRA_POLY, "") ?: ""
    fun setExtraPolyText(text: String) = AppPrefs.putString(KEY_EXTRA_POLY, text)

    fun getDialogRolesText(): String = AppPrefs.getString(KEY_DIALOG_ROLES, "") ?: ""
    fun setDialogRolesText(text: String) = AppPrefs.putString(KEY_DIALOG_ROLES, text)

    fun getDialogRoleMode(): String = AppPrefs.getString(KEY_DIALOG_ROLE_MODE, "cycle") ?: "cycle"
    fun setDialogRoleMode(mode: String) = AppPrefs.putString(KEY_DIALOG_ROLE_MODE, mode)

    // ★ 旁白/叙述段发言人（多选，每行 "引擎|音色"，轮询分配；空=未配置 → 旁白用默认音色）
    fun getNarrationRoles(): List<DialogRoleCfg> {
        val out = ArrayList<DialogRoleCfg>()
        var idx = 1
        for (line in (AppPrefs.getString(KEY_NARRATION_ROLE, "") ?: "").lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val parts = t.split('|')
            if (parts.size < 2) continue
            out.add(DialogRoleCfg(
                label = "旁白$idx",
                engineCode = parts[0].trim().ifBlank { "microsoft" },
                voice = parts.getOrNull(1)?.trim().orEmpty()
            ))
            idx++
        }
        return out
    }
    /** 旁白多选保存：每行 "引擎|音色"（空=清空） */
    fun setNarrationRoles(roles: List<DialogRoleCfg>) {
        val sb = StringBuilder()
        for (r in roles) {
            if (r.engineCode.isBlank() || r.voice.isBlank()) continue
            sb.append(r.engineCode).append('|').append(r.voice).append('\n')
        }
        AppPrefs.putString(KEY_NARRATION_ROLE, sb.toString())
    }
    /** 旧接口兼容：单旁白（首个） */
    fun getNarrationRole(): DialogRoleCfg? = getNarrationRoles().firstOrNull()

    // ==================== 角色规则（MultiTTS RoleRule）====================

    /**
     * 保存角色规则列表。
     * 行格式：规则名|正则|引擎|音色|激活|抽象|matchBefore|matchAfter
     * 例：张三|他说(.{0,12})|microsoft|zh-CN-YunxiNeural|1|0|1|1
     */
    fun setRoleRules(rules: List<RoleRule>) {
        val sb = StringBuilder()
        for (r in rules) {
            if (r.name.isBlank()) continue
            sb.append(r.name).append('|')
                .append(r.regex).append('|')
                .append(r.engineCode).append('|')
                .append(r.voice).append('|')
                .append(if (r.activate) 1 else 0).append('|')
                .append(if (r.isAbstract) 1 else 0).append('|')
                .append(if (r.matchBefore) 1 else 0).append('|')
                .append(if (r.matchAfter) 1 else 0)
                .append('\n')
        }
        AppPrefs.putString(KEY_ROLE_RULES, sb.toString())
    }

    /** 解析角色规则列表 */
    fun loadRoleRules(): List<RoleRule> {
        val raw = AppPrefs.getString(KEY_ROLE_RULES, "") ?: ""
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<RoleRule>()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val p = t.split('|')
            val name = p.getOrNull(0)?.trim().orEmpty()
            if (name.isEmpty()) continue
            out.add(RoleRule(
                name = name,
                regex = p.getOrNull(1)?.trim().orEmpty(),
                engineCode = p.getOrNull(2)?.trim().orEmpty(),
                voice = p.getOrNull(3)?.trim().orEmpty(),
                activate = p.getOrNull(4)?.trim() != "0",
                isAbstract = p.getOrNull(5)?.trim() == "1",
                matchBefore = p.getOrNull(6)?.trim() != "0",
                matchAfter = p.getOrNull(7)?.trim() != "0"
            ))
        }
        return out
    }

    // ==================== YAML 解析（MultiTTS ReplaceConfig 格式，含 pauseMs） ====================

    /** 语音包根目录（含 replaces.yaml） */
    private fun configFile(): File? =
        VoicePackageManager.root?.let { File(it, "replaces.yaml") }

    /**
     * 从语音包根目录 replaces.yaml 读取替换规则（MultiTTS ReplaceConfig 格式）。
     * 结构：!!org.nobody.multitts.tts.replace.ReplaceConfig → replaces: [ {activate, group, name, regex, source, target} ]
     */
    fun loadReplacesFromVoice(): List<ReplaceRule> {
        val f = configFile() ?: return emptyList()
        if (!f.isFile) return emptyList()
        return try {
            val clean = f.readText()
                .lines()
                .joinToString("\n") { line ->
                    if (line.contains("!!org.nobody")) line.replace(Regex("!!org\\.nobody[\\.\\w]*"), "").trimEnd()
                    else line
                }
            parseRulesYaml(clean)
        } catch (e: Exception) {
            MttsLog.e("TxtPre", "load replaces.yaml failed: ${f.path}", e)
            emptyList()
        }
    }

    // ==================== replaces.yaml 单源读写（去附加，直接改配置文件） ====================

    /** 读取 replaces.yaml 的纯文本（供导出） */
    fun getReplaceYamlText(): String =
        try { configFile()?.takeIf { it.isFile }?.readText() ?: "" } catch (_: Exception) { "" }

    /** 解析任意 MultiTTS ReplaceConfig YAML 文本 → 规则列表（导入用，兼容 !! 标签） */
    fun parseReplaceYaml(text: String): List<ReplaceRule> {
        if (text.isBlank()) return emptyList()
        return try {
            val clean = text.lines().joinToString("\n") { line ->
                if (line.contains("!!org.nobody")) line.replace(Regex("!!org\\.nobody[\\.\\w]*"), "").trimEnd()
                else line
            }
            parseRulesYaml(clean)
        } catch (e: Exception) {
            MttsLog.w("TxtPre", "parse replace yaml failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 把整个规则列表写回 replaces.yaml（MultiTTS ReplaceConfig 格式，保留我们独有的 pauseMs 字段）。
     * 供 UI 在 新增/编辑/删除/开关/改分组 时直接落盘；不依赖 AppPrefs 附加规则。
     */
    fun saveReplacesToVoice(rules: List<ReplaceRule>): String {
        val f = configFile() ?: throw IllegalStateException("语音包目录不可用")
        val sb = StringBuilder("!!org.nobody.multitts.tts.replace.ReplaceConfig\nreplaces:\n")
        for (r in rules) {
            sb.append("- name: ").append(quoteYaml(r.name)).append('\n')
            sb.append("  group: ").append(quoteYaml(r.group.ifBlank { "默认分组" })).append('\n')
            sb.append("  activate: ").append(r.activate).append('\n')
            sb.append("  regex: ").append(r.regex).append('\n')
            sb.append("  source: ").append(quoteYaml(r.source)).append('\n')
            sb.append("  target: ").append(quoteYaml(r.target)).append('\n')
            if (r.pauseMs > 0) sb.append("  pauseMs: ").append(r.pauseMs).append('\n')
        }
        f.parentFile?.mkdirs()
        f.writeText(sb.toString())
        return f.absolutePath
    }

    /** YAML 单值引用。优先用单引号（正则/反斜杠在单引号内保持字面量，不会被误当转义序列）；值含换行/控制字符时改用双引号并转义。 */
    private fun quoteYaml(s: String): String {
        if (s.isEmpty()) return "''"
        // 含换行/制表等控制字符 → 只能用双引号并转义
        if (s.any { it == '\n' || it == '\r' || it == '\t' }) {
            return "\"" + s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\""
        }
        // 需要引号的情况：YAML 特殊字符或特殊起始符、首尾空白、引号自身
        val c = s.firstOrNull()
        val needsQuote = s.any { it in ":#{}[]\"'" } || (c != null && "|>-*&!%@`?[: \t".contains(c)) ||
            s.endsWith(' ') || s.startsWith(' ') || s.contains(": ") || s.startsWith("- ") || s.startsWith("? ") || s.startsWith(": ")
        if (!needsQuote) return s
        // 单引号内单引号转义为 ''；单引号不解析反斜杠转义，正则 \d \w \. 等保持原样
        return "'" + s.replace("'", "''") + "'"
    }

    /** replaces.yaml 中现有的分组集合（保留文档中出现顺序，保证「默认分组」在首位） */
    fun loadReplaceGroups(): List<String> {
        val seen = LinkedHashSet<String>()
        for (r in loadReplacesFromVoice()) if (r.group.isNotBlank()) seen.add(r.group)
        if ("默认分组" !in seen) {
            // 默认分组必须最先出现 → 重新构建，把默认分组放到最前
            return (linkedSetOf("默认分组") + seen).toList()
        }
        return seen.toList()
    }

    /** 解析 MultiTTS ReplaceConfig YAML（剥离 !! 标签，读 replaces 列表，含 pauseMs 新字段） */
    private fun parseRulesYaml(clean: String): List<ReplaceRule> {
        val root = Yaml().load<Any?>(clean)
        val map = if (root is Map<*, *>) root.entries.associate { (k, v) -> k to v } else emptyMap()
        val replacesRaw = map["replaces"]
        val list = when (replacesRaw) {
            is List<*> -> replacesRaw
            else -> emptyList<Any?>()
        }
        return list.mapNotNull { item ->
            if (item !is Map<*, *>) return@mapNotNull null
            val m = item.entries.associate { (k, v) -> k to v }
            val source = str(m["source"])
            if (source.isBlank()) return@mapNotNull null
            ReplaceRule(
                name = str(m["name"]),
                group = str(m["group"]).ifBlank { "默认分组" },
                activate = bool(m["activate"], true),
                regex = bool(m["regex"], false),
                source = source,
                target = str(m["target"]),
                pauseMs = int(m["pauseMs"])     // ★ 我们独有的新字段
            )
        }
    }

    /** 附加替换规则 YAML（用户自定义，MultiTTS 同款格式） */
    fun loadExtraRules(): List<ReplaceRule> {
        val raw = getExtraDictText()
        if (raw.isBlank()) return emptyList()
        return try {
            parseRulesYaml(raw.lines().joinToString("\n") { line ->
                if (line.contains("!!org.nobody")) line.replace(Regex("!!org\\.nobody[\\.\\w]*"), "").trimEnd()
                else line
            })
        } catch (e: Exception) {
            MttsLog.w("TxtPre", "extra dict yaml failed: ${e.message}")
            emptyList()
        }
    }

    /** 附加多音字 YAML（用户自定义，MultiTTS 同款格式，只取替换能力） */
    fun loadExtraPolyRules(): List<ReplaceRule> {
        val raw = getExtraPolyText()
        if (raw.isBlank()) return emptyList()
        return try {
            parseRulesYaml(raw.lines().joinToString("\n") { line ->
                if (line.contains("!!org.nobody")) line.replace(Regex("!!org\\.nobody[\\.\\w]*"), "").trimEnd()
                else line
            })
        } catch (e: Exception) {
            MttsLog.w("TxtPre", "extra poly yaml failed: ${e.message}")
            emptyList()
        }
    }

    // ==================== 发言人角色（对话识别）解析 ====================

    /** 发言人配置：每行 "角色名|引擎code|音色code" */
    fun loadDialogRoles(): List<DialogRoleCfg> {
        val roles = ArrayList<DialogRoleCfg>()
        var idx = 1
        for (line in getDialogRolesText().lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val parts = t.split('|')
            if (parts.size >= 2) {
                roles.add(DialogRoleCfg(
                    label = parts[0].trim().ifBlank { "对白$idx" },
                    engineCode = parts[1].trim().ifBlank { "microsoft" },
                    voice = parts.getOrNull(2)?.trim() ?: ""
                ))
            }
            idx++
        }
        return roles
    }

    // ==================== 内置多音字表 ====================

    /**
     * 常见多音字纠正（内置，附加表可补充）。
     * 行格式：主字|上下文词→同音替换词|上下文词→同音替换词|…
     * 例：长|长江→常江|市长→市掌
     *
     * 原则：
     *  - 只收「引擎易读错」的多音字词，每个主字覆盖其主要读音场景
     *  - 目标必须是真实同音字（同音同调或同音近调），严禁「词→同词」占位
     *  - 不用拼音（拼音会被 TTS 当英文逐字母念出来）
     */
    private val BUILTIN_POLY = """
长|长江→常江|长城→常城|长征→常征|漫长→漫常|延长→延常|长度→常度|很长→很常|长短→常短|市长→市掌|校长→校掌|班长→班掌|队长→队掌|家长→家掌
行|行走→形走|行动→形动|行人→形人|进行→进形|五行→五形|行列→杭列|行为→形为|步行→步形|自行车→自行形|银行→银杭|行业→杭业|排行→排杭
重|重要→众要|重点→众点|重量→众量|体重→体众|举重→举众|重病→众病|尊重→尊众|沉重→沉众|重中之重→众中之重|重来→崇来|重新→崇新|重复→崇复|重叠→崇叠|重庆→崇庆
得|得到→德到|得意→德意|得分→德分|获得→获德|不得→不德
还|还有→孩有|还是→孩是|还要→孩要|还没→孩没|还好→孩好|还钱→环钱|还给→环给|归还→归环|退还→退环|还清→环清|偿还→偿环
中|中奖→众奖|中意→众意|打中→打众|百发百中→百发百众
种|种类→肿类|品种→肿品|各种→各肿|一种→一肿|种子→肿子|种地→众地|种田→众田|种植→众植|播种→播众|种菜→众菜|种花→众花
数|数字→树字|数量→树量|数学→树学|数据→树据|数目→树目|数落→蜀落|数不清→蜀不清|数一数二→蜀一蜀二
少|少年→绍年|少女→绍女|少爷→绍爷
教|教书→交书|教课→交课|教唆→叫唆
看|看守→刊守|看门→刊门|看管→刊管|看家→刊家
着|穿着→穿卓|沉着→沉卓|着手→卓手
没|没有→煤有|没事→煤事|没人→煤人|没空→煤空|没关系→煤关系|没用→煤用|沉没→沉沫|淹没→淹沫|出没→出沫
藏|宝藏→宝葬|西藏→西葬
弹|弹琴→谈琴|弹奏→谈奏|子弹→子蛋|导弹→导蛋|炮弹→炮蛋|弹弓→蛋弓|弹性→蛋性
降|降落→酱落|下降→下酱|降温→酱温|降价→酱价|投降→投祥|降服→祥服|降伏→祥伏
折|折腾→遮腾|折断→哲断|折返→哲返|骨折→骨哲|折腰→哲腰|对折→对哲|折本→舌本|折了→舌了
和|唱和→唱贺|附和→附贺|和面→活面
系|系统→细统|系列→细列|关系→关细|联系→联细|体系→体细|派系→派细|系鞋带→季鞋带|系上→季上
差|差别→插别|差距→插距|差错→插错|差不多→岔不多|差劲→岔劲|差点→岔点|差事→拆事
称|称呼→撑呼|名称→名撑|称赞→撑赞|声称→声撑|称体重→撑体重|称职→衬职|对称→对衬|相称→相衬|称心→衬心
落|落枕→烙枕
背|背后→贝后|背景→贝景|背叛→贝叛|违背→违贝|脊背→脊贝|后背→后贝|背影→贝影|背包→悲包|背东西→悲东西|背债→悲债
传|传播→船播|传说→船说|传递→船递|传承→船承|传统→船统|传人→船人|自传→自撰|外传→外撰|水浒传→水浒撰
撒|撒种→洒种|撒谎→仨谎|撒欢→仨欢|撒丫子→仨丫子|撒腿就跑→仨腿就跑
便|大便→大变|小便→小变|便当→变当|便衣→变衣|便便→变便
调|调皮→笤皮|调教→笤教|协调→协笤|调换→掉换|调走→掉走|调动→掉动
喝|喝令→褐令|喝住→褐住|大喝一声→大褐一声
空|空地→控地|空闲→控闲|空着→控着|有空→有控|没空→没控
打|一打→一答
假|真假→真钾|假牙→钾牙|假冒→钾冒|假货→钾货|放假→放价
模|模糊→摩糊
参|人参→人身|海参→海身
观|道观→道贯|寺观→寺贯
冠|沐猴而冠→沐猴而罐
血|吐血→吐写|流血→流写|血淋淋→写淋淋|血糊糊→写糊糊
旋|旋转→玄转|旋律→玄律|盘旋→盘玄|回旋→回玄|旋涡→玄涡
任|任丘→仁丘|任县→仁县|任他去吧→仁他去吧
将|将领→酱领|将士→酱士|将官→酱官|将门→酱门|麻将→麻酱
为|为官→围官|为人→围人|为何→喂何|为谁→喂谁
相|相声→项声|相机→项机|宰相→宰项|首相→首项|照相→照项|看相→看项
兴|即兴→即性|助兴→助性|兴致→性致
朝|朝廷→潮廷|朝代→潮代|朝拜→潮拜|朝向→潮向|朝见→潮见
都|首都→首督|都市→督市|成都→成督|大都→大督
子|子夜→紫夜|子时→紫时|甲子→甲紫
片|片子→篇子
的|的确→涤确|目的→目地|有的放矢→有地放矢|无的放矢→无地放矢|众矢之的→众矢之地
倒|倒水→到水|倒影→到影|倒是→到是|倒不如→到不如|倒过来→到过来
应|应酬→映酬|应变→映变|效应→效映|应声→映声
分|身分→身份|分量→份量|处分→处份|本分→本份|分外→份外
""".trimIndent()

    /** 内置多音字 → PolyEntry 列表（主字 + 上下文规则），应用覆盖记录（编辑/删除/开关） */
    fun loadBuiltinPolyEntries(): List<PolyEntry> {
        val overrides = loadPolyOverrides()
        return BUILTIN_POLY.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) return@mapNotNull null
            val idx = t.indexOf('|')
            if (idx <= 0) return@mapNotNull null
            val char = t.substring(0, idx).trim()
            if (char.isEmpty()) return@mapNotNull null
            val rules = t.substring(idx + 1)
                .split('|')
                .mapNotNull { seg ->
                    val s = seg.trim()
                    if (s.isEmpty()) return@mapNotNull null
                    val arrow = s.indexOf('→')
                    if (arrow <= 0) return@mapNotNull null
                    val word = s.substring(0, arrow).trim()
                    val target = s.substring(arrow + 1).trim()
                    if (word.isEmpty() || target.isEmpty()) return@mapNotNull null
                    // 应用覆盖：key = 主字|内置原始词
                    val ov = overrides["$char|$word"]
                    when {
                        ov?.deleted == true -> null                      // 已删除
                        ov != null -> PolyRule(
                            word = ov.newWord.ifBlank { word },          // 可改词
                            target = ov.target.ifBlank { target },
                            activate = ov.enabled,
                            builtinKey = word                            // 内置原始词，稳定定位覆盖
                        )
                        else -> PolyRule(word = word, target = target, activate = true, builtinKey = word)
                    }
                }
            if (rules.isEmpty()) null
            else PolyEntry(char = char, rules = rules)
        }
    }

    // ==================== 内置多音字规则覆盖（编辑/删除/开关） ====================

    /** 单条内置规则的覆盖状态 */
    data class PolyOverride(
        val target: String = "",   // 修改后的同音词（空=保持内置 target）
        val newWord: String = "",  // 修改后的上下文词（空=保持内置 word）
        val enabled: Boolean = true,
        val deleted: Boolean = false
    )

    /** 解析覆盖记录：每行 "主字|词|target|enabled|deleted|newWord"（兼容旧数据：无 newWord） */
    private fun loadPolyOverrides(): Map<String, PolyOverride> {
        val raw = AppPrefs.getString(KEY_POLY_OVERRIDES, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        val out = HashMap<String, PolyOverride>()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val p = t.split('|')
            if (p.size < 3) continue
            val key = "${p[0].trim()}|${p[1].trim()}"
            if (p[0].isBlank() || p[1].isBlank()) continue
            out[key] = PolyOverride(
                target = p.getOrNull(2)?.trim().orEmpty(),
                enabled = p.getOrNull(3)?.trim() != "0",
                deleted = p.getOrNull(4)?.trim() == "1",
                newWord = p.getOrNull(5)?.trim().orEmpty()
            )
        }
        return out
    }

    /** 保存覆盖记录（合并写回） */
    fun setPolyOverride(char: String, word: String, ov: PolyOverride) {
        val map = loadPolyOverrides().toMutableMap()
        val key = "$char|$word"
        // 与内置相同（无改动）则删除覆盖，保持干净
        val builtin = loadRawBuiltinRule(char, word)
        val wordChanged = ov.newWord.isNotBlank() && ov.newWord != word
        val targetChanged = builtin != null && ov.target != builtin
        if (ov.deleted || !ov.enabled || wordChanged || targetChanged) {
            map[key] = ov
        } else {
            map.remove(key)
        }
        val sb = StringBuilder()
        for ((k, v) in map) {
            val parts = k.split('|')
            sb.append(parts.getOrNull(0).orEmpty()).append('|')
                .append(parts.getOrNull(1).orEmpty()).append('|')
                .append(v.target).append('|')
                .append(if (v.enabled) 1 else 0).append('|')
                .append(if (v.deleted) 1 else 0).append('|')
                .append(v.newWord).append('\n')
        }
        AppPrefs.putString(KEY_POLY_OVERRIDES, sb.toString())
    }

    /** 查内置原始规则（主字+词 → 原 target；无则 null） */
    fun loadRawBuiltinRule(char: String, word: String): String? {
        for (line in BUILTIN_POLY.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val idx = t.indexOf('|')
            if (idx <= 0) continue
            if (t.substring(0, idx).trim() != char) continue
            // 该主字下找词
            return t.substring(idx + 1).split('|').mapNotNull { seg ->
                val s = seg.trim()
                val ar = s.indexOf('→')
                if (ar <= 0) null else s.substring(0, ar).trim() to s.substring(ar + 1).trim()
            }.firstOrNull { it.first == word }?.second
        }
        return null
    }

    /**
     * 附加多音字 PolyEntry 序列化。
     * 行格式：主字|上下文词→同音词|上下文词→同音词|…
     * 例：长|长江→常江|市长→市掌
     */
    fun setExtraPolyEntries(entries: List<PolyEntry>) {
        val sb = StringBuilder()
        for (e in entries) {
            if (e.char.isBlank()) continue
            sb.append(e.char)
            for (r in e.rules.filter { it.word.isNotBlank() && it.target.isNotBlank() }) {
                sb.append('|').append(r.word).append('→').append(r.target)
            }
            sb.append('\n')
        }
        AppPrefs.putString(KEY_EXTRA_POLY, sb.toString())
    }

    /** 附加多音字 PolyEntry 解析（行格式：主字|上下文词→同音词|…） */
    fun loadExtraPolyEntries(): List<PolyEntry> {
        val raw = getExtraPolyText()
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<PolyEntry>()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val parts = t.split('|')
            val char = parts.getOrNull(0)?.trim().orEmpty()
            if (char.isEmpty()) continue
            val rules = parts.drop(1).mapNotNull { seg ->
                val s = seg.trim()
                val arrow = s.indexOf('→')
                if (arrow <= 0) return@mapNotNull null
                val word = s.substring(0, arrow).trim()
                val target = s.substring(arrow + 1).trim()
                if (word.isEmpty() || target.isEmpty()) null
                else PolyRule(word = word, target = target, activate = true)
            }
            if (rules.isEmpty()) continue
            out.add(PolyEntry(char = char, rules = rules))
        }
        return out
    }

    /** 用多音字 PolyEntry 列表应用替换（按上下文整词替换为同音词） */
    private fun applyPolyEntries(text: String, entries: List<PolyEntry>): String {
        var out = text
        for (e in entries) {
            for (r in e.rules) {
                if (!r.activate || r.word.isEmpty() || r.target.isEmpty()) continue
                out = out.replace(r.word, r.target)
            }
        }
        return out
    }

    /** 只跑多音字纠正（主字 + 上下文规则），供多音字二级页预览 */
    fun previewPoly(text: String): String {
        val entries = loadBuiltinPolyEntries() + loadExtraPolyEntries()
        return applyPolyEntries(text, entries)
    }

    // ==================== 对外主入口 ====================

    /**
     * 预处理一段文本（替换词典 → 多音字纠正 → 对话识别）。
     * 替换/多音字规则如带 pauseMs 新字段，会在替换文本中注入 ⟨pause:ms⟩ 停顿标记。
     *
     * @param text 原始文本（小说段落等）
     * @return 处理后结果（text 可能含停顿标记；dialogue 为对话段落列表）
     */
     fun process(text: String): ProcessResult {
         if (text.isBlank()) return ProcessResult("", emptyList())

         // ★★ 标记保护流水线（修复 ⟨pause⟨pause:150⟩400⟩ 嵌套畸形 → 残留 ⟨pause400⟩ 被朗读成 "200400"）★★
         //   旧实现里各步骤直接读上一步骤的输出字符串，
         //   「智能识别」多条规则的注入、以及标点停顿的注入，
         //   会让下一条规则的正则**扫描到已注入标记的内部字符（如 ⟨pause:150⟩ 的 `:`、`p`、`a`…）**，
         //   在标记中间再插入标记 → 产生畸形嵌套 ⟨pause⟨pause:150⟩400⟩，
         //   stripAllMarkers 只能剥掉内层 → 外层残留 ⟨pause400⟩（缺冒号）→ 正则不匹配 → 引擎朗读 "pause 400"。
         //   修复思路：每一步跑正则之前，把已注入的标记先占位化成 \u0001序号\u0002（控制字符，
         //   任何 [\u4e00-\u9fa5]/\d/[a-zA-Z]/标点半角类正则都匹配不到它），跑完再解回。
         val stored = ArrayList<String>()
         var out = protectInto(text, stored)

         // 1) 替换词典（MultiTTS replaces.yaml + 附加 + 内置兜底，内置规则应用覆盖）
         if (isReplaceEnabled()) {
             val rules = loadReplacesFromVoice() + loadExtraRules() + loadBuiltinReplaceRules()
             out = protectInto(out, stored)   // ★ 保护上一步注入的标记
             for (r in rules) {
                 if (!r.activate || r.source.isEmpty()) continue
                 out = applyRule(out, r)
             }
         }
         // 2) 表情符号处理（emoji→文字 + 标点→停顿）
         if (isEmojiEnabled()) {
             out = protectInto(out, stored)   // ★ 保护上一步注入的标记
             out = applyEmojiFeature(out)
         }
         // 3) 智能识别（等一下→停顿、大声说→音量；可联动对话功能情绪）
         if (isSmartEnabled()) {
             out = protectInto(out, stored)   // ★ 保护上一步注入的标记
             out = applySmartFeature(out)
         }
         // 4) 多音字纠正（内置 + 附加，PolyEntry：可多读音 + 激活项）
         if (isPolyEnabled()) {
             out = protectInto(out, stored)   // ★ 保护上一步注入的标记
             val entries = loadBuiltinPolyEntries() + loadExtraPolyEntries()
             out = applyPolyEntries(out, entries)
         }
         // 5) 对话识别已拆出为独立「对话功能」（DialogFeature）
         // ★★ 兜底：清理任何残留的畸形残缺标记（历史 bug 或用户误操作遗留），
         //   防止 ⟨pause400⟩ 这类缺冒号的畸形标记被引擎当普通文本朗读
         out = MARKER_BROKEN_RE.replace(out, "")
         return ProcessResult(restoreMarkers(out, stored), emptyList())
    }

    /** 处理结果 */
    data class ProcessResult(
        val text: String,
        val dialogue: List<RoleSeg>   // ★ 角色分配后的段（含绑定引擎/音色）
    )

    /** 对话段落：引号内的对白 + 前后叙述 */
    data class DialogueSeg(val text: String, val isSpeech: Boolean)

    /**
     * 应用单条规则：正则/纯文本替换 source→target，
     * 若规则带 pauseMs（额外配置字段），在替换结果后追加 ⟨pause:ms⟩ 停顿标记。
     *
     * 正则语义对齐 MultiTTS：pattern.matcher(text).replaceAll(target)，
     * 支持 lookahead/lookbehind、$1 反向引用等完整 Java 正则。
     */
    private fun applyRule(text: String, r: ReplaceRule): String {
        // 命中判断：正则用 Pattern.matcher.find()；纯文本用 contains()。
        // 命中后才做替换（避免 pauseMs 语义在未命中时误加停顿）。
        val matched = try {
            if (r.regex) Regex(r.source).containsMatchIn(text)
            else text.contains(r.source)
        } catch (e: Exception) {
            MttsLog.w("TxtPre", "rule match failed: ${r.name} ${e.message}")
            false
        }
        if (!matched) {
            // 未命中：若带 pauseMs，仍追加停顿（语义：目标=原文+停顿，如「等一下」）
            return if (r.pauseMs > 0) text + PAUSE_PREFIX + r.pauseMs + PAUSE_SUFFIX
                   else text
        }
        // 命中：执行替换。正则用 Matcher.replaceAll（支持 $1 反向引用），纯文本用字面替换。
        var out = try {
            if (r.regex) Regex(r.source).replace(text, r.target)
            else text.replace(r.source, r.target)
        } catch (e: Exception) {
            MttsLog.w("TxtPre", "rule failed: ${r.name} ${e.message}")
            text
        }
        if (r.pauseMs > 0) {
            // 停顿标记：在替换结果的末尾追加（不换文字，只加停顿）
            // 正则/纯文本统一：命中即给整段末尾追加停顿标记，保证停顿生效
            out = out + PAUSE_PREFIX + r.pauseMs + PAUSE_SUFFIX
        }
        return out
    }

    /**
     * 从处理后文本提取停顿标记列表（供 TtsService 逐段插入静音）。
     * @return List<ms> 每个标记对应停顿毫秒
     */
    fun extractPauses(text: String): List<Int> {
        return PAUSE_RE.findAll(text).map { it.groupValues[1].toIntOrNull() ?: 0 }.filter { it > 0 }.toList()
    }

    /** 剥离停顿标记，返回纯净文本（供引擎合成用） */
    fun stripPauses(text: String): String = text.replace(PAUSE_RE, "")

    // ---------- 内置小规则（保底，仅当语音包无 replaces.yaml 时兜底常见符号） ----------

    private fun builtinRules(): List<ReplaceRule> = listOf(
        ReplaceRule(name = "省略号→停顿", regex = true, source = "(…+|\\\\.{3,6})", target = " ——"),
        ReplaceRule(name = "竖线→消音", regex = true, source = "(?<!\\d)\\|(?!\\d)", target = ""),
        ReplaceRule(name = "等于→等于", regex = true, source = "(?<=[0-9a-zA-Z])=(?=[0-9a-zA-Z])", target = "等于"),
        ReplaceRule(name = "乘号→乘", regex = true, source = "[ꁘ*＊Xx×](?=\\d)", target = "乘"),
        ReplaceRule(name = "520→五二零", regex = true, source = "(?<!\\d)520(?!\\d)", target = "五二零"),
        ReplaceRule(name = "TA→祂", regex = true, source = "(?<![a-zA-Z])([Tt][Aa])(?![a-zA-Z])", target = "祂"),
        ReplaceRule(name = "BJ→北京", regex = true, source = "(?<![a-zA-Z])([Bb][Jj])(?![a-zA-Z])", target = "北京"),
        ReplaceRule(name = "SH→上海", regex = true, source = "(?<![a-zA-Z])([Ss][Hh])(?![a-zA-Z])", target = "上海"),
        ReplaceRule(name = "TM→他妈", regex = true, source = "(?<![a-zA-Z])([Tt][Mm])(?![a-zA-Z])", target = "他妈")
    )

    // ==================== ★ 表情符号处理（emoji→文字 + 标点→停顿） ====================

    /** 常见 emoji → 中文可读文字（TTS 能正确朗读） */
    private val EMOJI_MAP = linkedMapOf(
        "🪿" to "鹅",
        "😂" to "笑哭",
        "🤣" to "笑哭",
        "😀" to "开心",
        "😁" to "开心",
        "😄" to "开心",
        "😊" to "微笑",
        "🙂" to "微笑",
        "😉" to "眨眼",
        "😍" to "喜欢",
        "🤩" to "惊喜",
        "😢" to "难过",
        "😭" to "大哭",
        "😡" to "生气",
        "😠" to "生气",
        "🤔" to "思考",
        "😴" to "睡觉",
        "🤗" to "拥抱",
        "🤐" to "闭嘴",
        "😱" to "惊恐",
        "🙏" to "拜托",
        "👍" to "点赞",
        "👎" to "差评",
        "👌" to "好的",
        "✌️" to "胜利",
        "🖐️" to "手掌",
        "🙌" to "欢呼",
        "👏" to "鼓掌",
        "💪" to "加油",
        "❤️" to "爱心",
        "💕" to "爱心",
        "💖" to "爱心",
        "💔" to "心碎",
        "⭐" to "星星",
        "🌟" to "星星",
        "✨" to "闪光",
        "🔥" to "火热",
        "💧" to "水滴",
        "☀️" to "太阳",
        "🌙" to "月亮",
        "☁️" to "云朵",
        "🌧️" to "下雨",
        "❄️" to "雪花",
        "🌈" to "彩虹",
        "🌸" to "花朵",
        "🌹" to "玫瑰",
        "🍀" to "幸运",
        "🍎" to "苹果",
        "🍚" to "米饭",
        "🍜" to "面条",
        "☕" to "咖啡",
        "🍺" to "啤酒",
        "🎂" to "蛋糕",
        "🎉" to "庆祝",
        "🎊" to "庆祝",
        "🎁" to "礼物",
        "🎵" to "音乐",
        "🎶" to "音乐",
        "🎤" to "麦克风",
        "🎧" to "耳机",
        "💰" to "金钱",
        "💡" to "灯泡",
        "📱" to "手机",
        "💻" to "电脑",
        "⌚" to "手表",
        "📚" to "书本",
        "✏️" to "铅笔",
        "📝" to "笔记",
        "📌" to "图钉",
        "🔍" to "放大镜",
        "🔑" to "钥匙",
        "🔒" to "锁",
        "🚀" to "火箭",
        "✈️" to "飞机",
        "🚗" to "汽车",
        "🚌" to "公交",
        "🚲" to "自行车",
        "🏠" to "房子",
        "⛰️" to "山峰",
        "🌊" to "海浪",
        "🐔" to "小鸡",
        "🐶" to "小狗",
        "🐱" to "小猫",
        "🐰" to "兔子",
        "🐼" to "熊猫",
        "🦊" to "狐狸",
        "🐻" to "熊",
        "🐮" to "牛",
        "🐷" to "猪",
        "🐸" to "青蛙",
        "🐵" to "猴子",
        "🦁" to "狮子",
        "🐯" to "老虎",
        "🐺" to "狼",
        "🐴" to "马",
        "🦋" to "蝴蝶",
        "🐝" to "蜜蜂",
        "🐢" to "乌龟",
        "🐳" to "鲸鱼",
        "🐬" to "海豚",
        "🐟" to "鱼",
        "🦆" to "鸭子",
        "🦉" to "猫头鹰",
        "🦅" to "老鹰",
        "🦢" to "天鹅"
    )
/** 标点停顿规则（默认 50ms，保留标点本身并**追加**停顿标记） */
    private val PUNCT_PAUSE = listOf(
        Triple(Regex("…+|…{2,}|\\.{3,6}"), 50, "省略号停顿"),
        Triple(Regex("[。！？!?]"), 50, "句末停顿"),
        Triple(Regex("[，,、；;]"), 50, "句中停顿"),
        Triple(Regex("[：:]"), 50, "冒号停顿"),
        Triple(Regex("[—–-]{1,2}"), 50, "破折号停顿")
    )

    // ==================== ★ emoji/标点/智能/内置替换 覆盖机制（对齐多音字 PolyOverride） ====================

    /** 单条内置规则的覆盖状态：edit = 编辑后的值（字段相关），enabled 开关，deleted 删除 */
    private data class BuiltinOverride(
        val edit: String = "",
        val enabled: Boolean = true,
        val deleted: Boolean = false
    )

    /** 通用：解析覆盖记录（key|edit|enabled|deleted） */
    private fun loadOverrides(key: String): Map<String, BuiltinOverride> {
        val raw = AppPrefs.getString(key, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        val out = HashMap<String, BuiltinOverride>()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val p = t.split('|')
            if (p.size < 2) continue
            if (p[0].isBlank()) continue
            out[p[0].trim()] = BuiltinOverride(
                edit = p.getOrNull(1)?.trim().orEmpty(),
                enabled = p.getOrNull(2)?.trim() != "0",
                deleted = p.getOrNull(3)?.trim() == "1"
            )
        }
        return out
    }

    /** 通用：写回覆盖记录 */
    private fun saveOverrides(key: String, map: Map<String, BuiltinOverride>) {
        val sb = StringBuilder()
        for ((k, v) in map) {
            sb.append(k).append('|')
                .append(v.edit).append('|')
                .append(if (v.enabled) 1 else 0).append('|')
                .append(if (v.deleted) 1 else 0).append('\n')
        }
        AppPrefs.putString(key, sb.toString())
    }

    /** 通用：设置单条覆盖（与内置一致自动清理保持干净） */
    private fun setOverride(
        key: String,
        ruleKey: String,
        builtinEdit: String?,
        newEdit: String,
        enabled: Boolean,
        deleted: Boolean
    ) {
        val map = loadOverrides(key).toMutableMap()
        if (deleted || !enabled || (builtinEdit != null && newEdit != builtinEdit)) {
            map[ruleKey] = BuiltinOverride(edit = newEdit, enabled = enabled, deleted = deleted)
        } else {
            map.remove(ruleKey)
        }
        saveOverrides(key, map)
    }

    // ==================== 内置替换规则覆盖（编辑/删除/开关） ====================

    /** 加载应用覆盖后的内置替换规则（✎可编辑 target、开关、删除） */
    fun loadBuiltinReplaceRules(): List<ReplaceRule> {
        val ov = loadOverrides(KEY_REPLACE_OVERRIDES)
        val out = ArrayList<ReplaceRule>()
        for (r in builtinRules()) {
            val o = ov[r.name]
            when {
                o?.deleted == true -> {}                                       // 已删除
                o != null -> if (o.enabled) out.add(r.copy(target = o.edit.ifBlank { r.target }))
                else -> out.add(r)
            }
        }
        return out
    }

    /** 设置内置替换规则覆盖（key=规则名；与内置一致自动清理） */
    fun setReplaceBuiltinOverride(name: String, newTarget: String, enabled: Boolean, deleted: Boolean) {
        val builtin = builtinRules().firstOrNull { it.name == name }?.target
        setOverride(KEY_REPLACE_OVERRIDES, name, builtin, newTarget, enabled, deleted)
    }

    // ==================== emoji / 标点 / 智能 覆盖（编辑/删除/开关） ====================

    /** 加载应用覆盖后的 emoji 映射（有序：emoji → 中文词） */
    fun loadEmojiMaps(): List<Pair<String, String>> {
        val ov = loadOverrides(KEY_EMOJI_OVERRIDES)
        val out = ArrayList<Pair<String, String>>()
        for ((emoji, word) in EMOJI_MAP) {
            val o = ov[emoji]
            when {
                o?.deleted == true -> {}                                       // 已删除
                o != null -> if (o.enabled) out.add(emoji to (o.edit.ifBlank { word }))
                else -> out.add(emoji to word)
            }
        }
        return out
    }

    /** 设置 emoji 映射覆盖（key=emoji；与内置一致自动清理） */
    fun setEmojiOverride(emoji: String, newWord: String, enabled: Boolean, deleted: Boolean) {
        setOverride(KEY_EMOJI_OVERRIDES, emoji, EMOJI_MAP[emoji], newWord, enabled, deleted)
    }

    /** 标点停顿规则（暴露给 UI：pattern 正则源文本 / ms / 名称 / 开关） */
    data class PunctRule(
        val pattern: String,
        val ms: Int,
        val name: String,
        val enabled: Boolean = true
    )

    private fun builtinPunctRules(): List<PunctRule> = PUNCT_PAUSE.map { (re, ms, name) ->
        PunctRule(pattern = re.pattern, ms = ms, name = name)
    }

    /** 加载应用覆盖后的标点停顿规则 */
    fun loadPunctRules(): List<PunctRule> {
        val ov = loadOverrides(KEY_PUNCT_OVERRIDES)
        val out = ArrayList<PunctRule>()
        for (r in builtinPunctRules()) {
            val o = ov[r.pattern]
            when {
                o?.deleted == true -> {}                                       // 已删除
                o != null -> if (o.enabled) out.add(r.copy(ms = o.edit.toIntOrNull() ?: r.ms))
                else -> out.add(r)
            }
        }
        return out
    }

    /** 设置标点停顿覆盖（key=正则源文本；与内置一致自动清理） */
    fun setPunctOverride(pattern: String, newMs: Int, enabled: Boolean, deleted: Boolean) {
        val builtin = builtinPunctRules().firstOrNull { it.pattern == pattern }?.ms?.toString()
        setOverride(KEY_PUNCT_OVERRIDES, pattern, builtin, newMs.toString(), enabled, deleted)
    }

    /** 加载应用覆盖后的智能识别规则（✎可编辑值/标签、开关、删除） */
    fun loadSmartRules(): List<SmartRule> {
        val ov = loadOverrides(KEY_SMART_OVERRIDES)
        val out = ArrayList<SmartRule>()
        for (r in SMART_RULES) {
            val o = ov[r.regex.pattern]
            when {
                o?.deleted == true -> {}                                       // 已删除
                o != null -> {
                    if (o.enabled) {
                        val parts = o.edit.split('|')
                        val v = parts.getOrNull(0)?.toFloatOrNull() ?: r.value
                        val label = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: r.label
                        out.add(r.copy(value = v, label = label))
                    }
                }
                else -> out.add(r)
            }
        }
        return out
    }

    /** 设置智能识别覆盖（key=正则源文本；edit 存 "value|label"；与内置一致自动清理） */
    fun setSmartOverride(pattern: String, newValue: Float, newLabel: String, enabled: Boolean, deleted: Boolean) {
        val builtin = SMART_RULES.firstOrNull { it.regex.pattern == pattern }
        val builtinEdit = builtin?.let { "${it.value}|${it.label}" }
        setOverride(KEY_SMART_OVERRIDES, pattern, builtinEdit, "$newValue|$newLabel", enabled, deleted)
    }

    // ==================== ★ 表情符号处理（emoji→文字 + 标点→停顿） ====================

    /** emoji 转文字（应用覆盖：✎编辑 target / 开关 / 删除） */
    private fun applyEmoji(text: String): String {
        var out = text
        for ((emoji, word) in loadEmojiMaps()) {
            if (out.contains(emoji)) out = out.replace(emoji, word)
        }
        return out
    }

    /** 标点 → 停顿标记：**保留标点本身**，在其后**追加**停顿标记（不替换标点）。
     *  例：`你好，世界。` → `你好，⟨pause:50⟩世界。⟨pause:50⟩`
     *  （旧实现用 `replace` 把标点替换成标记，会丢失标点的语义；现改为追加语义。）
     *  使用 regex.find 逐位匹配后再在末尾追加 marker，跳过匹配之间的字符。 */
    private fun applyPunctPause(text: String): String {
        // ★ 标记保护：多条规则顺序跑时，前一条规则刚注入的 ⟨pause:50⟩ 里的英文冒号 `:`
        //   会被「冒号停顿」规则再次命中，产生 ⟨pause:⟨pause:50⟩50⟩ 双重嵌套。
        //   修法：每次跑正则前把已有标记占位化，跑完再还原。
        val stored = ArrayList<String>()
        var out = protectInto(text, stored)
        for (r in loadPunctRules()) {
            try {
                val re = Regex(r.pattern)
                val marker = PAUSE_PREFIX + r.ms + PAUSE_SUFFIX
                val sb = StringBuilder()
                var last = 0
                for (m in re.findAll(out)) {
                    sb.append(out, last, m.range.start)
                    sb.append(out.substring(m.range))   // ★ 保留标点本身
                    sb.append(marker)                   // ★ 追加停顿标记
                    last = m.range.endInclusive + 1
                }
                sb.append(out.substring(last))
                out = sb.toString()
                out = protectInto(out, stored)          // ★ 保护本轮新注入的标记，防止下一条规则命中
            } catch (_: Exception) { /* 忽略 */ }
        }
        return restoreMarkers(out, stored)
    }

    /** 表情符号处理主入口：emoji→文字 + 标点→停顿 */
    fun applyEmojiFeature(text: String): String {
        if (!isEmojiEnabled()) return text
        return applyPunctPause(applyEmoji(text))
    }

    // ==================== ★ 智能识别（等一下→停顿、大声说→音量，联动情绪） ====================

    /** 智能规则：词/正则 → 追加标记类型 + 值 */
    private val SMART_RULES = listOf(
        // —— 停顿类：口语停顿词 → 句后停顿
        SmartRule(Regex("(等|稍|且|别)+[一二三四五六七八九十两]*?[等一下等会儿稍等一下]"), "pause", 900f, "等一下"),
        SmartRule(Regex("(快|赶|立刻|马上)"), "pause", 150f, "紧急接续"),
        SmartRule(Regex("(可爱|软软|糯糯|轻轻)地"), "pause", 100f, "动作停顿"),
        // —— 音量类：大声 → 放大音量；小声/轻声 → 缩小音量
        SmartRule(Regex("(大声|吼道|怒吼|咆哮|喊)"), "vol", 1.6f, "大声"),
        SmartRule(Regex("(小声|轻声|低语|悄悄|耳语|嘀咕)"), "vol", 0.5f, "轻声"),
        SmartRule(Regex("(尖叫|惊叫|惊呼)"), "vol", 1.8f, "尖叫"),
        // —— 音调类：情绪激动 → 抬音调；低沉 → 降音调
        SmartRule(Regex("(激动|兴奋|高兴|开心|笑)"), "pit", 0.2f, "开心"),
        SmartRule(Regex("(难过|悲伤|哭|哽咽|抽泣|低沉|失落)"), "pit", -0.3f, "难过"),
        SmartRule(Regex("(害怕|恐惧|颤抖|哆嗦)"), "pit", 0.15f, "害怕"),
        SmartRule(Regex("(愤怒|生气|发火|怒吼)"), "pit", 0.25f, "愤怒")
    )

    /** 智能规则：匹配正则 → 在句末/词尾注入某标记 */
    data class SmartRule(
        val regex: Regex,
        val type: String,      // pause / vol / pit
        val value: Float,
        val label: String
    )

    /** 智能识别主入口：命中"等一下/大声说"等 → 注入停顿/音量/音调标记（应用覆盖：✎编辑值/开关/删除） */
    fun applySmartFeature(text: String): String {
        if (!isSmartEnabled()) return text
        var out = text
        for (rule in loadSmartRules()) {
            try {
                if (rule.regex.containsMatchIn(out)) {
                    val marker = when (rule.type) {
                        "pause" -> PAUSE_PREFIX + rule.value.toInt() + PAUSE_SUFFIX
                        "vol" -> VOL_PREFIX + rule.value + VOL_SUFFIX
                        "pit" -> PIT_PREFIX + rule.value + PIT_SUFFIX
                        else -> ""
                    }
                    if (marker.isNotEmpty()) out = out + marker
                }
            } catch (_: Exception) { /* 忽略非法规则 */ }
        }
        return out
    }

    // ==================== 标记提取（供 TtsService） ====================

    /** 提取音量标记（段内最后一个命中生效；默认 1f） */
    fun extractVol(text: String): Float {
        val all = VOL_RE.findAll(text).map { it.groupValues[1].toFloatOrNull() ?: 1f }.toList()
        return all.lastOrNull() ?: 1f
    }

    /** 提取音调标记（段内最后一个命中生效；默认 0f） */
    fun extractPitchAdjust(text: String): Float {
        val all = PIT_RE.findAll(text).map { it.groupValues[1].toFloatOrNull() ?: 0f }.toList()
        return all.lastOrNull() ?: 0f
    }

    /** 剥离全部标记（停顿/音量/音调），返回纯净文本。
     *  ★ 兜底清理：除了标准 ⟨pause:ms⟩/⟨vol:x⟩/⟨pit:x⟩，也清掉畸形残缺标记
     *  （如 ⟨pause400⟩ 缺冒号、⟨pause⟩ 空、⟨pause:150⟩ 内部被破坏等），
     *  防止历史 bug 或用户误操作遗留的畸形标记被引擎当普通文本朗读
     *  （历史上曾产生「200400」这种被朗读的数字）。 */
    fun stripAllMarkers(text: String): String {
        var out = text.replace(PAUSE_RE, "").replace(VOL_RE, "").replace(PIT_RE, "")
        // 再清扫任何残缺/畸形标记（⟨pause400⟩/⟨pit⟩/⟨vol1.5⟩ 等），确保不残留可朗读字符
        out = MARKER_BROKEN_RE.replace(out, "")
        return out
    }

    // ==================== 对话识别 ====================

    /**
     * 把文本切分为交替的 [叙述, 对白, 叙述, ...] 段落。
     * 对白 = 引号内内容；叙述 = 引号外。
     */
    fun splitDialogue(text: String): List<DialogueSeg> {
        val segs = ArrayList<DialogueSeg>()
        var idx = 0
        val sb = StringBuilder()
        while (idx < text.length) {
            var matched = false
            for ((open, close) in QUOTE_PAIRS) {
                if (text.startsWith(open, idx)) {
                    val end = text.indexOf(close, idx + open.length)
                    if (end > idx) {
                        if (sb.isNotEmpty()) { segs.add(DialogueSeg(sb.toString(), false)); sb.clear() }
                        val inner = text.substring(idx + open.length, end)
                        segs.add(DialogueSeg(inner, true))
                        idx = end + close.length
                        matched = true
                        break
                    }
                }
            }
            if (!matched) {
                sb.append(text[idx])
                idx++
            }
        }
        if (sb.isNotEmpty()) segs.add(DialogueSeg(sb.toString(), false))
        return segs
    }

    // ==================== 角色规则分配（对齐 MultiTTS RoleRule）====================

    /**
     * 用角色规则把文本段分配到发言人（MultiTTS DLG/NRT 分配机制）：
     *  - 遍历已启用的规则，用 regex 在段文本上匹配
     *  - 命中 → 段归属该规则绑定的发言人（engineCode/voice）
     *  - 未命中任何规则 → 旁白段（isSpeech=false，engineCode/voice 空 → 用旁白角色/默认）
     *  - 抽象规则（isAbstract）只做锚点不直接分配（用于"他说"这类前缀）
     *
     * 结合 splitDialogue 的使用方式：
     *  - 先按引号切出 对白(引号内)/旁白(引号外)
     *  - 再对每个段跑角色规则：引号内段命中规则 → 用规则发言人；引号外段命中规则 → 该规则发言人（如"张三说"前后归属）
     *
     * @return 分配后的段列表（每段含绑定引擎/音色，空=旁白默认）
     */
    fun applyRoleRules(dialogue: List<DialogueSeg>): List<RoleSeg> {
        val rules = loadRoleRules().filter { it.activate && it.regex.isNotBlank() }
        if (rules.isEmpty()) {
            // 无角色规则：对白段按原样（引擎空），旁白段同样 → 由 TtsService 按旁白/对白角色兜底
            return dialogue.map { RoleSeg(it.text, it.isSpeech) }
        }
        val out = ArrayList<RoleSeg>()
        for (seg in dialogue) {
            val text = seg.text.trim()
            if (text.isEmpty()) continue
            var assigned: RoleRule? = null
            // 按规则顺序匹配：正则命中即分配（abstract 规则不直接分配，仅锚点）
            for (r in rules) {
                if (r.isAbstract) continue   // 抽象规则跳过（不直接发音）
                try {
                    if (Regex(r.regex).containsMatchIn(text)) { assigned = r; break }
                } catch (_: Exception) { /* 非法正则忽略 */ }
            }
            if (assigned != null) {
                out.add(RoleSeg(text, true, assigned.engineCode, assigned.voice))
            } else {
                // 未命中 → 旁白段（engineCode 空 → 由调用方用旁白角色/默认）
                out.add(RoleSeg(text, false))
            }
        }
        return out
    }

    /** 角色规则预览：把文本按角色规则分配后的段列表（供 UI 展示） */
    fun previewRoleRules(text: String): List<RoleSeg> = applyRoleRules(splitDialogue(text))

    // ==================== 测试预览（供 UI） ====================

    /** 只跑替换词典（含停顿标记），供替换词典二级页预览 */
    fun previewReplace(text: String): String {
        var out = text
        val rules = loadReplacesFromVoice() + loadExtraRules() + loadBuiltinReplaceRules()
        for (r in rules) {
            if (!r.activate || r.source.isEmpty()) continue
            out = applyRule(out, r)
        }
        return out
    }

    /** 单条替换规则应用到文本（供列表逐条展示"处理前→处理后"） */
    fun applySingleRule(text: String, r: ReplaceRule): String =
        if (!r.activate || r.source.isEmpty()) text else applyRule(text, r)

    /** 单条多音字条目应用到文本（按上下文词替换为同音词，供 UI 展示/试听） */
    fun applySinglePoly(text: String, e: PolyEntry): String {
        var out = text
        for (r in e.rules) {
            if (!r.activate || r.word.isEmpty() || r.target.isEmpty()) continue
            out = out.replace(r.word, r.target)
        }
        return out
    }

    /** 只跑对话识别，供对话识别二级页预览 */
    fun previewDialog(text: String): List<DialogueSeg> = splitDialogue(text)

    // ==================== helpers ====================

    private fun str(v: Any?): String = when (v) {
        is String -> v
        is Number -> v.toString()
        else -> v?.toString() ?: ""
    }

    private fun int(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    private fun bool(v: Any?, def: Boolean): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.equals("true", true) || v == "1"
        else -> def
    }
}