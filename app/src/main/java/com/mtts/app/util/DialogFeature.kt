package com.mtts.app.util

/**
 * 对话功能（独立于文本预处理的大设置）。
 *
 * 目标：从小说/朗读文本中**识别对话人**，并为每个说话人**稳定分配固定音色**；
 * 支持多个旁白音色；音色按【声音分组】（女/男/老/少）归档，角色从分组装载；
 * 支持【情感规则】——正则匹配文本特征（如诗句）→ 调整语速/音量/音调或切换发言人。
 *
 * 段落流水线：
 *   1. 引号切分 → 对白段（引号内）/ 旁白段（引号外）
 *   2. 对白段关联"说话人"：扫描其紧邻的前置旁白文本，匹配角色名（如「莉可说」）
 *   3. 分配音色：角色有固定音色 → 用之；否则从角色的声音分组挑一个音色
 *   4. 旁白段 → 从旁白音色列表轮询（多旁白）
 *   5. 情感规则：正则匹配段文本 → 覆盖语速/音量/音调（甚至换发言人）
 *
 * 所有数据从 AppPrefs 读取（由 UI 保存）。
 */

/**
 * 高频阈值：与【朗读积累满 10 次自动注册为正式角色】完全统一。
 *
 * 语义：一个说话人必须反复出现（≥5 次）才判定为"重要角色"——
 *  - TTS 朗读积累：朗读到第 5 次才注册为正式角色；
 *  - 文本自动识别：出现 ≥5 次才默认勾选，出现 1 次的多为误判短语。
 *
 * 两处共用同一常量，改一处即全局生效，避免阈值漂移。
 */
const val HIGH_FREQ_THRESHOLD = 5

/** 声音分组：女/男/老/少等（可绑定多个引擎+音色，角色按名字稳定分配到其中一个） */
data class DialogVoiceGroup(
    val name: String = "女",
    val gender: String = "女",                        // 女 / 男 / 老 / 少
    val voices: List<Pair<String, String>> = emptyList()  // (engineCode, voice) 多选列表，可空=按性别自动挑
) {
    /** 兼容：单引擎+音色视图（旧字段用法），取第一个 */
    val engineCode: String get() = voices.firstOrNull()?.first ?: ""
    val voice: String get() = voices.firstOrNull()?.second ?: ""
}

/** 角色：固定绑定一个分组 + 可选固定音色（空=从分组自动装载） */
data class DialogCharacter(
    val name: String = "",       // 角色名（如 莉可 / 苍宫月）
    val group: String = "",      // 所属声音分组（如 女 / 男）
    val engineCode: String = "", // 固定引擎（空=从分组/默认）
    val voice: String = "",      // 固定音色（空=从分组自动匹配）
    val manual: Boolean = false  // true=手动分配（用户手动指定固定音色）；false=自动分配
)

/** 抽象角色（朗读积累中，未达注册阈值）：名字 + 已朗读次数 */
data class AbstractRole(
    val name: String = "",
    val count: Int = 0,
    val gender: String = "女"    // 推测性别分组（他多→男）
)

/** 自动识别结果：人名 + 命中规则 + 推测性别分组 + 出现次数（权重） */
data class DetectedRole(
    val name: String = "",
    val rule: String = "",       // 命中句式说明（如 名字+冒号+引号）
    val gender: String = "女",   // 推测性别分组（他多→男）
    val count: Int = 1           // 在文本中作为说话人被识别的次数（权重，越高越可信）
)

/** 旁白音色（可多个，轮询） */
data class DialogNarrator(
    val engineCode: String = "microsoft",
    val voice: String = ""
)

/** 情感规则：正则命中 → 覆盖合成参数（可切换发言人） */
data class DialogEmotion(
    val name: String = "诗句",
    val regex: String = "",           // 正则（如 床前明月光|疑是地上霜）
    val engineCode: String = "",      // 命中后使用的引擎（空=保持当前）
    val voice: String = "",           // 命中后使用的音色（空=保持当前）
    val speed: Float = 1f,            // 语速倍率（<1 变慢，>1 变快）
    val volume: Float = 1f,
    val pitch: Float = 0f
)

/** 预处理结果段：文本 + 类型 + 绑定引擎/音色 + 合成参数（供 TtsService 逐段合成） */
data class DialogSeg(
    val text: String,
    val isSpeech: Boolean,          // true=对白（引号内），false=旁白
    val speaker: String = "",       // 说话人（旁白为空）
    val engineCode: String = "",    // 空=用默认引擎
    val voice: String = "",
    val speed: Float = 1f,          // 语速倍率（情感规则可调）
    val volume: Float = 1f,
    val pitch: Float = 0f
)

object DialogFeature {

    /** 引号对（含中文引号） */
    private val QUOTE_PAIRS = listOf(
        "\u201c" to "\u201d",  // “ ”
        "\u2018" to "\u2019",  // ‘ ’
        "「" to "」",
        "\"" to "\"",
        "『" to "』"
    )

    // ==================== 说话人识别 ====================

    /** 从旁白文本中识别说话人：匹配 "XXX说/问/答/道/喊/回" 等 */
    private val SPEAKER_VERB_RE = Regex("([\\u4e00-\\u9fa5]{1,6})(?:说|问|答|道|喊|回|叹|叫|嚷|应|答话|开口|轻语|低语)")
    /**
     * ★ 强格式识别（用冒号+引号，杜绝"知道/街道"里"道"字的误判）：
     *  - 格式A：XX说/问/道：'台词'  或  XX：'台词'  （名字+说话动词，冒号后紧跟引号）
     *  - 格式B：'台词'，XX说/问/道。 （引号内容结束后，紧接名字+说话动词）
     *  - 格式C：XX说/问/道'台词'    （名字+动词直接接引号，无冒号）
     * 只在这些结构里出现的名字才可能是说话人，"我知道一个地方"这类普通"道"字不会命中。
     *
     * ★ 名字组用【懒惰匹配 {1,6}?】+ 复合动词（说道/问道…）优先，
     *   避免贪婪匹配把"说道"吞进名字（"苍宫月说道「」"→提取"苍宫月"而非"苍宫月说"）。
     */
    private val SPEAKER_COLON_RE = Regex("([\\u4e00-\\u9fa5]{1,6}?)(?:说道|问道|答道|喊道|叹道|叫道|笑道|应道|嘀咕道|喃喃道|说|问|答|道|喊|回|叹|叫|嚷|应|开口|轻语|低语)?\\s*[:：]\\s*[\"“‘「『]")
    private val SPEAKER_AFTER_QUOTE_RE = Regex("[\"”’」』]\\s*(?:，|,|。|\\.|、)?\\s*([\\u4e00-\\u9fa5]{1,6}?)(?:说道|问道|答道|喊道|叹道|叫道|笑道|应道|嘀咕道|喃喃道|说|问|答|道|喊|回|叹|叫|嚷|应|开口|轻语|低语)")
    private val SPEAKER_VERB_QUOTE_RE = Regex("([\\u4e00-\\u9fa5]{1,6}?)(?:说道|问道|答道|喊道|叹道|叫道|笑道|应道|嘀咕道|喃喃道|说|问|答|道|喊|回|叹|叫|嚷|应|开口|轻语|低语)[\"“‘「『]")
    /** 旁白段（引号已剥离）以这些动词结尾时，视为"说话人+动词"短句，可提炼人名 */
    private val SPEAK_VERBS_FOR_TAIL = listOf(
        "说道", "问道", "答道", "喊道", "叹道", "叫道", "笑道", "应道", "嘀咕道", "喃喃道",
        "说", "问", "答", "道", "喊", "回", "叹", "叫", "嚷", "应", "开口", "轻语", "低语", "喃喃"
    )

    /** ★ 身份/称谓词表：可独立作"说话主体"锚定（不依赖"说/道"动词）——
     *   "班长皱起眉头：'…'" / "掌柜风轻云淡道：'…'" 这类动作描写夹在中间也算发言。 */
    private val TITLE_NOUNS = listOf(
        "班长", "副班长", "学习委员", "体育委员", "劳动委员", "课代表", "组长", "舍长", "寝室长", "队长",
        "团长", "连长", "排长", "军长", "司令", "将军", "元帅", "总督", "知府", "知县", "县令", "太守",
        "掌柜", "老板", "老板娘", "经理", "主管", "主任", "校长", "院长", "所长", "局长", "处长", "科长",
        "镇长", "乡长", "村长", "书记", "主席", "老师", "老师傅", "教授", "医生", "大夫", "护士", "律师", "法官",
        "检察官", "警察", "警官", "保安", "司机", "师傅", "厨师", "服务员", "编辑", "记者", "主编",
        "同学", "同桌", "校友", "室友", "舍友", "学长", "学姐", "学弟", "学妹", "师兄", "师姐", "师弟", "师妹",
        "教练", "裁判", "队员", "选手", "球员", "学霸", "学神", "大佬", "大神", "前辈", "后辈",
        "大哥", "大姐", "小哥", "大叔", "大妈", "阿姨", "叔叔", "伯伯", "爷爷", "奶奶", "姥姥", "姥爷",
        "哥哥", "姐姐", "弟弟", "妹妹", "爸爸", "妈妈", "父亲", "母亲", "陛下", "王爷", "太子", "公主",
        "皇子", "丞相", "尚书", "侍郎", "大学士", "钦差", "巡抚", "大人", "官人", "娘子", "夫人", "老爷",
        "少爷", "公子", "小姐", "姑娘", "楼主", "群主", "管理员"
    )

    /** ★ 在片段中找"最可能是说话主体"的锚点：已知角色名 + 称谓/身份词，取出现位置最靠后者。
     *   （说话主体通常在动作描写前、紧邻冒号/引号处——"班长皱起眉头：" → 班长） */
    private fun speakerTitleIn(s: String, known: List<String>): String? {
        var best = ""
        var bestPos = -1
        val pool = LinkedHashSet<String>()
        pool.addAll(known.filter { it.isNotBlank() && it.length >= 2 })
        pool.addAll(TITLE_NOUNS)
        pool.addAll(MALE_NAME_PHRASES)
        pool.addAll(FEMALE_NAME_PHRASES)
        for (n in pool) {
            val pos = s.lastIndexOf(n)
            if (pos > bestPos) { bestPos = pos; best = n }
        }
        return best.ifBlank { null }
    }

    /** ★ 换行逗号式：名字+说+逗号，换行，再引号 —— "林晚说，\n“……”" 自动识别也要认 */
    private val SPEAKER_COMMA_NEWLINE_RE = Regex("([\\u4e00-\\u9fa5]{1,6}?)(?:说道|问道|答道|喊道|叹道|叫道|笑道|应道|嘀咕道|喃喃道|说|问|答|道|喊|回|叹|叫|嚷|应|开口|轻语|低语)?\\s*[，,]\\s*\\n\\s*[\"“‘「『]")
    /** 旁白中的"人名"候选（可能多字名，这里按全句匹配角色名做更稳） */

    /**
     * 切分文本为 旁白/对白 交替段（保留每段原文，供后续识别说话人）。
     *
     * ★ 智能规则：引号内是"短引用"（≤ 16 字且无终止标点 ,。！？,!?;"）时
     *   视为**修饰名词**（如 "星尘"、"极光幻影星空气泡饮"、"专属权"），
     *   直接并入相邻旁白，不切分成对白段。
     *   只有引号内含终止标点（.，！?;）的才视为真正的对白。
     *
     *   这样 "推了个叫'星尘'的饮料到桌上" 保持整段旁白，
     *   而 "澈澈说：'我不想吃'" 里的引号内容仍正确识别为对白。
     */
    fun splitSegments(text: String): List<DialogSeg> {
        val segs = ArrayList<DialogSeg>()
        var idx = 0
        val sb = StringBuilder()

        // 判断引号内内容是否为"短引用"（应并入旁白）
        fun isShortQuote(inner: String): Boolean {
            if (inner.isBlank()) return true
            if (inner.length > 16) return false
            // 含终止标点 = 真实对白
            if (inner.contains("。") || inner.contains("，") || inner.contains("！") ||
                inner.contains("？") || inner.contains(".") || inner.contains(",") ||
                inner.contains("!") || inner.contains("?")) return false
            return true
        }

        while (idx < text.length) {
            var matched = false
            for ((open, close) in QUOTE_PAIRS) {
                if (text.startsWith(open, idx)) {
                    val end = text.indexOf(close, idx + open.length)
                    if (end > idx) {
                        val inner = text.substring(idx + open.length, end)
                        if (isShortQuote(inner)) {
                            // 短引用：不切分，直接把引号+内容并入当前旁白
                            sb.append(open).append(inner).append(close)
                            idx = end + close.length
                        } else {
                            // 真实对白：切段
                            if (sb.isNotBlank()) { segs.add(DialogSeg(sb.toString(), false)); sb.clear() }
                            segs.add(DialogSeg(inner, true))
                            idx = end + close.length
                        }
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
        if (sb.isNotBlank()) segs.add(DialogSeg(sb.toString(), false))
        return segs
    }

    /**
     * 识别某段对白的说话人：扫描其**之前**最近的旁白段，用角色名/人名池匹配。
     * 匹配模式（强格式，冒号/引号结构，避免"知道/街道"误判）：
     *  - "莉可说：「……」" / "莉可：「……」"（名字+可选动词+冒号+引号）
     *  - "「……」莉可说"（引号结束+名字+动词）
     *  - "莉可说「……」"（名字+动词+引号）
     *  - "林玖皱起眉头：「……」"（冒号前锚定全文人名池中的名字——先定人，再锚定发言）
     * 回退：文本里只出现一个已知角色名时，用该角色。
     *
     * @param namePool 全文人名单池（collectNamePool 结果），让"动作描写引导的发言"也能锚定到人
     */
    private fun identifySpeaker(
        narration: String?,
        characters: List<DialogCharacter>,
        namePool: Set<String> = emptySet()
    ): String {
        val text = narration?.trim().orEmpty()
        if (text.isEmpty()) return ""
        // ★ 已注册角色名 + 全文人名池优先：任一名字出现在候选片段开头 → 直接用（避免分词把名字切坏）
        val known = LinkedHashSet<String>()
        for (c in characters) if (c.name.isNotBlank()) known.add(c.name)
        known.addAll(namePool.filter { it.isNotBlank() })
        fun resolve(raw: String): String {
            if (raw.isBlank()) return ""
            val hit = known.firstOrNull { raw.startsWith(it) }
            if (hit != null) return hit
            return purifyName(raw)
        }
        // 1) 强格式：名字(+动词)+冒号+引号 → 名字
        val m1 = SPEAKER_COLON_RE.find(text)
        if (m1 != null) {
            val who = resolve(m1.groupValues[1].trim())
            if (who.isNotBlank() && who !in PHRASE_BLACKLIST) return who
        }
        // 2) 强格式：引号结束+名字+动词
        val m2 = SPEAKER_AFTER_QUOTE_RE.find(text)
        if (m2 != null) {
            val who = resolve(m2.groupValues[1].trim())
            if (who.isNotBlank() && who !in PHRASE_BLACKLIST) return who
        }
        // 3) 强格式：名字+动词+引号
        val m3 = SPEAKER_VERB_QUOTE_RE.find(text)
        if (m3 != null) {
            val who = resolve(m3.groupValues[1].trim())
            if (who.isNotBlank() && who !in PHRASE_BLACKLIST) return who
        }
        // 4) 切段引导：splitSegments 已剥离引号，旁白段形如 "林晚说：" / "班长皱起眉头：" / "周掌柜……应道："。
        //    以冒号结尾时，取冒号前最后出现的说话主体锚点（已知角色名 + 称谓/身份词；
        //    "班长皱起眉头：" → 班长——动作描写("皱起眉头")夹在中间也算发言，不依赖"说/道"动词）；
        //    ★ 无锚点时用 jieba+动词剥离从片段头提取人名。
        val ci = text.lastIndexOf('：').takeIf { it >= 0 } ?: text.lastIndexOf(':')
        if (ci >= 0) {
            val head = text.substring(0, ci)
            val anchor = speakerTitleIn(head, known.toList())
            if (anchor != null) return anchor
            val who = resolve(head)
            if (who.isNotBlank() && who !in PHRASE_BLACKLIST && who !in PRONOUN_BLACKLIST) return who
        }
        // 4.5) 无冒号但以说话动词结尾（"林晚说""周掌柜应道""林晚说，\n"）：短旁白直接提炼人名候选。
        //      先剥尾部标点/换行（"林晚说，\n" 尾部既有逗号又有换行——splitSegments 会把
        //      "xx说，" 和换行一起留在旁白段，不剥掉就判断不出动词结尾），再限 2~8 字。
        val trimmed = text.trimEnd(' ', '　', '\t', '\n', '\r', '，', ',', '。', '.', '！', '!', '？', '?', '·', '…', '：', ':')
        if (trimmed.length in 2..8 && SPEAK_VERBS_FOR_TAIL.any { trimmed.endsWith(it) }) {
            val who = resolve(trimmed)
            if (who.isNotBlank() && who !in PHRASE_BLACKLIST && who !in PRONOUN_BLACKLIST) return who
        }
        // 5) 回退：只出现一个已知角色名时，用该角色（"她说"前文已明确是谁）
        if (characters.size == 1) return characters[0].name
        return ""
    }

    // ==================== 全文人名单池（先定人，再锚定发言） ====================

    /** 说话动词/语气单字：n-gram 含这些字多为"说/道/问"短语而非人名 */
    private val SPEECH_CHARS = "说道问答喊嚷叹叫笑道回应"

    /** 常见句首动词单字：主语位 n-gram 若以此开头多为动作描写（看着他/点了点头…）而非人名 */
    private val SENTENCE_VERB_CHARS = "看听说道想问觉感转抬低摇点叹沉愣怔盯瞪翻咬抿皱坐站走笑哼望瞧瞥扬挑"

    /**
     * ★ 统计/算法驱动的人名发现（主力，不靠动作词表硬编码）：
     * 原理：说话人无论用什么动作/神态描写引导发言，其名字总是【反复】出现在
     * "冒号+引号"引导片段的最开头（主语位）；动作描写词只出现在主语位之后
     * （起始 i>=2），且不跨片段在开头重复。
     * 做法：对每个引导片段取开头连续汉字，枚举 2~4 字 n-gram，按起始位置加权
     * （i=0 权重最高），跨片段聚合总分；总分高、出现片段多、不含动词/句首动词
     * 字的候选即人名。"林玖皱起眉头：「…」" → "林玖" 反复出现在 i=0 高分入选，
     * "皱起眉头" 始终 i>=2 低分排除。
     */
    private fun discoverSpeakersByStats(text: String): LinkedHashSet<String> {
        val anchorGuideRe = Regex("""[：:][\s]*["“‘「『]""")
        val fragSplitRe = Regex("""[。.！!？?；;、，,\n\r…]""")
        val score = HashMap<String, Int>()
        val shown = HashMap<String, Int>()
        fun isHan(c: Char) = c in '\u4e00'..'\u9fa5'
        for (m in anchorGuideRe.findAll(text)) {
            val ci = m.range.first
            var start = ci
            for (b in fragSplitRe.findAll(text.substring(0, ci).reversed())) {
                start = ci - b.range.first - 1
                break // 取最近一个标点（从右往左第一个）
            }
            if (ci - start > 16) start = ci - 16
            val frag = text.substring(start, ci).trim()
            if (frag.isEmpty()) continue
            // 取片段开头的连续汉字（最多 6 个）——说话人总是贴近片段头
            val hz = StringBuilder()
            for (ch in frag) {
                if (!isHan(ch)) break
                hz.append(ch)
                if (hz.length >= 6) break
            }
            val prefix = hz.toString()
            if (prefix.length < 2) continue
            // 枚举 2~4 字 n-gram，按起始位置加权（越靠前越像主语位）
            for (i in 0 until prefix.length - 1) {
                val maxLen = minOf(4, prefix.length - i)
                for (len in 2..maxLen) {
                    val cand = prefix.substring(i, i + len)
                    val w = when (i) { 0 -> 4; 1 -> 3; 2 -> 2; else -> 1 }
                    score[cand] = (score[cand] ?: 0) + w
                    shown[cand] = (shown[cand] ?: 0) + 1
                }
            }
        }
        val out = LinkedHashSet<String>()
        val sorted = score.entries.sortedByDescending { it.value }
        for ((name, sc) in sorted) {
            if (name in PRONOUN_BLACKLIST || name in PHRASE_BLACKLIST) continue
            if (name.any { it in SPEECH_CHARS || it in "的得了和与为是在上" }) continue
            if (name.first() in SENTENCE_VERB_CHARS) continue // 句首动词，非人名
            if ((shown[name] ?: 0) < 2 && sc < 6) continue     // 至少在 2 个引导点出现，或极高单点分
            out.add(name)
        }
        return out
    }

    /**
     * ★ 先确定"全文有哪些人"，再让人名锚定发言——不管他当时在不在说话。
     * 来源（按主力优先级）：
     *  ① 统计发现（discoverSpeakersByStats）——动作/神态描写引导的发言也能自动发现，
     *     不依赖"说/道"动词与动作词表
     *  ② 已注册角色名 + 强格式"名字+说/道"提取（林玖说：…）
     *  ③ 称谓/身份词表（班长/掌柜/老师…）——兜底
     */
    private fun collectNamePool(text: String, characters: List<DialogCharacter>): LinkedHashSet<String> {
        val pool = LinkedHashSet<String>()

        // ★ 0. 模型真名池（NER，最高优先级）：bert-mini 识别出该出现在文本里的所有人名
        //    —— 命中即入池，动作/神态/并列/引号后置全部覆盖（Linux 融合评测 F1=0.846）
        //    ★ 懒加载：不判 isReady（未加载时 persons() 内部自动加载，失败返回空）
        if (text.isNotBlank()) {
            try {
                for (p in NerOnnx.persons(text)) {
                    val clean = postTailClean(p)
                    if (clean.length >= 2 && clean !in PRONOUN_BLACKLIST && clean !in PHRASE_BLACKLIST) {
                        pool.add(clean)
                    }
                }
            } catch (t: Throwable) {
                com.mtts.app.util.MttsLog.w("NER", "pool err: ${t.message}")
            }
        }

        // ① 统计驱动发现（主力）
        pool.addAll(discoverSpeakersByStats(text))
        // ② 已注册角色
        for (c in characters) if (c.name.trim().length >= 2) pool.add(c.name.trim())
        if (text.isBlank()) return pool
        // ③ 称谓/身份词表命中（兜底）
        for (t in TITLE_NOUNS) if (text.contains(t)) pool.add(t)
        for (t in MALE_NAME_PHRASES) if (text.contains(t)) pool.add(t)
        for (t in FEMALE_NAME_PHRASES) if (text.contains(t)) pool.add(t)
        val knownNames = pool.toSet()
        // ④ 强格式提取（说/道明确引导）
        fun addFrom(re: Regex) {
            for (m in re.findAll(text)) {
                val raw = m.groupValues[1].trim()
                if (raw.isBlank() || raw in PRONOUN_BLACKLIST || raw in PHRASE_BLACKLIST) continue
                val who = if (knownNames.any { raw.startsWith(it) }) {
                    knownNames.first { raw.startsWith(it) }
                } else JiebaNameExtractor.extractName(raw, knownNames)
                if (who.isBlank() || who in PRONOUN_BLACKLIST || who in PHRASE_BLACKLIST) continue
                if (who.length < 2) continue
                pool.add(who)
            }
        }
        addFrom(SPEAKER_COLON_RE)
        addFrom(SPEAKER_AFTER_QUOTE_RE)
        addFrom(SPEAKER_VERB_QUOTE_RE)
        addFrom(SPEAKER_COMMA_NEWLINE_RE)
        return pool
    }

    // ==================== 声音分组装载 ====================

    /**
     * 根据性别从引擎音色列表中挑选一个音色。
     * gender: 女/男/老/少（老→男低音倾向、少→女高音倾向；这里按男/女映射 + 首选项）
     */
    fun pickVoiceForGender(engineVoices: List<com.mtts.app.engine.Voice>, gender: String): String? {
        if (engineVoices.isEmpty()) return null
        val wantMale = when (gender) {
            "男", "老" -> true
            "女", "少" -> false
            else -> false
        }
        // 先按性别过滤
        val matched = engineVoices.filter { it.gender.let { g -> if (wantMale) g.equals("Male", true) else g.equals("Female", true) } }
        if (matched.isNotEmpty()) return matched.first().shortName
        return engineVoices.first().shortName
    }

    /** 角色装载音色：固定音色 > 分组自动装载（被屏蔽音色全部跳过） */
    fun resolveCharacterVoice(
        char: DialogCharacter,
        engineVoicesByEngine: (String) -> List<com.mtts.app.engine.Voice>,
        defaultEngine: String
    ): Pair<String, String> {   // (engineCode, voiceShortName)
        // 固定引擎 + 固定音色（被屏蔽 → 视作未固定，回退后续装载）
        if (char.engineCode.isNotBlank() && char.voice.isNotBlank()) {
            if (!AppPrefs.isVoiceMuted(char.engineCode, char.voice)) {
                return char.engineCode to char.voice
            }
        }
        // 固定音色（引擎为空 → 用默认引擎；被屏蔽 → 回退）
        if (char.voice.isNotBlank()) {
            val eng = char.engineCode.ifBlank { defaultEngine }
            if (!AppPrefs.isVoiceMuted(eng, char.voice)) {
                return eng to char.voice
            }
        }
        // 从分组装载：优先用分组绑定的音色列表（过滤被屏蔽），否则按分组性别自动匹配
        val groups = AppPrefs.loadVoiceGroups()
        val group = groups.firstOrNull { it.name == char.group }
        // ① 分组绑定了多个音色 → 过滤被屏蔽后，按角色名稳定 hash 分配其中一个（同一角色始终同一音色）
        if (group != null && group.voices.isNotEmpty()) {
            val list = group.voices.filterNot { (e, v) -> AppPrefs.isVoiceMuted(e, v) }
            if (list.isNotEmpty()) {
                val idx = if (char.name.isBlank()) 0
                          else (char.name.hashCode() and 0x7fffffff) % list.size
                val (e, v) = list[idx]
                return e to v
            }
            // 全被屏蔽 → 继续回退按性别自动挑
        }
        val gender = group?.gender ?: char.group   // 分组未配置时直接用分组名当性别
        val eng = char.engineCode.ifBlank { defaultEngine }
        val voices = engineVoicesByEngine(eng).filterNot { AppPrefs.isVoiceMuted(eng, it.shortName) }
        val voice = pickVoiceForGender(voices, gender) ?: ""
        return eng to voice
    }

    /** 情感规则匹配：返回命中规则（顺序第一个命中） */
    fun matchEmotion(text: String, emotions: List<DialogEmotion>): DialogEmotion? {
        for (e in emotions) {
            if (e.regex.isBlank()) continue
            try {
                if (Regex(e.regex).containsMatchIn(text)) return e
            } catch (_: Exception) { /* 非法正则忽略 */ }
        }
        return null
    }

    // ==================== 发言人表（声音提供者池）分配 ====================

    /**
     * 从【发言人表】分配音色（角色 = 绑定发言人之一）。
     * @param key 说话人/角色名；空表示"仅轮询"（说话人识别不出的对白）
     * @param mode 分配模式：cycle=轮询 / same=全部用第一个 / off=不分（返回默认）
     * 稳定映射：同一 key 用 hash 固定选中同一发言人（同角色始终同音色）。
     */
    private fun dialogVoiceFor(
        key: String,
        roles: List<TextPreprocessor.DialogRoleCfg>,
        mode: String,
        defaultEngine: String,
        defaultVoice: String
    ): Pair<String, String> {
        if (roles.isEmpty()) return defaultEngine to defaultVoice
        val usable = roles.filterNot { r ->
            r.engineCode.isBlank() || r.voice.isBlank() ||
                AppPrefs.isVoiceMuted(r.engineCode, r.voice)
        }
        if (usable.isEmpty()) return defaultEngine to defaultVoice
        return when (mode) {
            "off" -> defaultEngine to defaultVoice
            "same" -> usable[0].engineCode to usable[0].voice
            else -> { // cycle / 默认
                if (key.isBlank()) { // 仅轮询：按调用次数轮流换人
                    val idx = dialogRoleCursor.getAndIncrement().toInt().mod(usable.size)
                    usable[idx].engineCode to usable[idx].voice
                } else { // 有角色名 → 稳定分配（同一角色始终同一发言人）
                    val idx = (key.hashCode() and 0x7fffffff) % usable.size
                    usable[idx].engineCode to usable[idx].voice
                }
            }
        }
    }

    /** 语音池轮询游标（对白分配模式 cycle，说话人识别不出时逐个换人） */
    private val dialogRoleCursor = java.util.concurrent.atomic.AtomicInteger(0)

    // ==================== 主处理 ====================

    /**
     * 处理一段文本 → 段列表（含发言人/音色/参数）。
     * 对话功能未启用时返回 null（调用方按整段处理，此时智能识别/表情处理由 TextPreprocessor.process 注入标记）。
     *
     * ★ 智能识别联动：无论对话功能是否启用，"等一下→停顿 / 大声说→音量" 都由 TextPreprocessor 注入标记；
     *   这里把已注入标记的文本分派到各段，供 TtsService 解析（pause/vol/pit 标记保留在段文本里）。
     *
     * ★ 旁白音色「段落级轮询」：narrators.size>1 时，每次 process 调用切下一个 narrator（跨段落轮换），
     *   同一段落内所有旁白仍用同一个 narrator。这样避免「句号/引号就换音色」的混乱，
     *   又能在长文/换章时切换旁白人，减轻单一音色疲劳。
     */
    // 段落级旁白轮询游标 + 随机判定（避免每次都切）
    @Volatile private var narratorIdx: Int = -1
    private val narratorRnd = java.util.Random()

    fun process(
        text: String,
        engineVoicesByEngine: (String) -> List<com.mtts.app.engine.Voice>,
        defaultEngine: String,
        defaultVoice: String
    ): List<DialogSeg>? {
        if (!AppPrefs.isDialogFeatureEnabled()) return null
        if (text.isBlank()) return null

        // ★ 被屏蔽的默认音色安全化：系统 TTS 请求的默认音色若被屏蔽，
        //   自动回退到该引擎第一个未屏蔽音色（彻底关闭，避免整段仍读出来）。
        var defaultV = defaultVoice
        if (defaultV.isNotBlank() && AppPrefs.isVoiceMuted(defaultEngine, defaultV)) {
            defaultV = engineVoicesByEngine(defaultEngine)
                .firstOrNull { !AppPrefs.isVoiceMuted(defaultEngine, it.shortName) }?.shortName ?: defaultV
        }

        val characters = AppPrefs.loadCharacters()
        val narrators = AppPrefs.loadNarrators()
        val emotions = AppPrefs.loadEmotions()
        val groups = AppPrefs.loadVoiceGroups()

        // 文本可能已含智能识别标记（⟨pause⟩/⟨vol⟩/⟨pit⟩），先按引号切段，标记跟随原段保留
        val segs = splitSegments(text)
        // ★ 全文人名单池：先确定"这一整段文本里有哪些人"（含动作描写锚定到的，
        //   不要求当时在说话），再让后面的对白段用人名锚定发言——"林玖皱起眉头：…"
        //   也能把"林玖"识别为说话人并计数。
        val namePool = collectNamePool(text, characters)
        val out = ArrayList<DialogSeg>()
        var lastNarration: String? = null

        // ★ 旁白音色「段落级智能切换」：
        //   - narrators.size <= 1：不切（始终用第一个）
        //   - narrators.size > 1：每次 process() 用**随机判定**是否切下一个
        //     ~15% 概率切下一个 narrator（保持当前 85%），偶尔换人减疲劳，但不过度切换。
        //   同一段落内所有旁白段用同一个 narrator（一个句号/引号不切）。
        // ★ 被屏蔽的旁白音色不参与轮询（彻底关闭）。
        val availableNarrators = narrators.filterNot { AppPrefs.isVoiceMuted(it.engineCode, it.voice) }
        val narrator: DialogNarrator? = if (availableNarrators.isEmpty()) {
            null
        } else if (availableNarrators.size == 1) {
            availableNarrators[0]
        } else {
            // 首次调用固定从 0 开始；之后 15% 概率切下一个
            if (narratorIdx < 0) {
                narratorIdx = 0
            } else if (narratorRnd.nextInt(100) < 15) {
                narratorIdx = (narratorIdx + 1) % availableNarrators.size
            }
            availableNarrators[narratorIdx]
        }
        val narratorEng = narrator?.engineCode ?: defaultEngine
        val narratorVoice = narrator?.voice?.ifBlank { defaultV } ?: defaultV

        for (seg in segs) {
            val t = seg.text.trim()
            if (t.isEmpty()) continue
            if (!seg.isSpeech) {
                // 旁白段：整篇使用固定的 narrator（不再轮询）
                out.add(seg.copy(
                    isSpeech = false, speaker = "",
                    engineCode = narratorEng, voice = narratorVoice, speed = 1f, volume = 1f, pitch = 0f,
                    text = com.mtts.app.util.TextPreprocessor.applySmartFeature(t)))
                lastNarration = t
} else {
                 // 对白段：识别说话人 → 分配音色（角色绑定发言人之一；未识别出则按分配模式轮询发言人表）
                 val speaker = identifySpeaker(lastNarration, characters, namePool)
                 var eng = defaultEngine
                 var voice = defaultV
                 if (speaker.isNotBlank()) {
                  val char = characters.firstOrNull { it.name == speaker }
                  if (char != null) {
                        val (ce, cv) = resolveCharacterVoice(char, engineVoicesByEngine, defaultEngine)
                        eng = ce
                        voice = cv.ifBlank { defaultV }
                    } else {
                        // ★ 识别出说话人但未注册角色：立即从发言人表（声音提供者池）稳定分配，
                        //   角色 = 绑定发言人中的一个（同一角色始终同一音色，按名 hash 稳定）。
                        val roles = TextPreprocessor.loadDialogRoles()
                        val (ce, cv) = dialogVoiceFor(speaker, roles, TextPreprocessor.getDialogRoleMode(), defaultEngine, defaultV)
                        eng = ce
                        voice = cv
                        // 保留抽象积累（朗读满 10 次自动注册正式角色，由 UI/阈值统一管理）
                        val gender = if (lastNarration.orEmpty().contains("他") &&
                                        !lastNarration.orEmpty().contains("她")) "男" else "女"
                        AppPrefs.bumpAbstractRole(speaker, gender)
                    }
                } else {
                    // ★ 说话人识别不出（如纯对白无前导人名）：按对白分配模式从发言人表轮询
                    //   （对齐旧对话识别：cycle=轮询换人 / same=全部用第一个 / off=不分）
                    val roles = TextPreprocessor.loadDialogRoles()
                    val (ce, cv) = dialogVoiceFor("", roles, TextPreprocessor.getDialogRoleMode(), defaultEngine, defaultV)
                    eng = ce
                    voice = cv
                }
                // 情感规则（诗句等）→ 可换发言人/调速
                var speed = 1f; var volume = 1f; var pitch = 0f
                val emo = matchEmotion(t, emotions)
                if (emo != null) {
                    // ★ 情绪功能可从声音分组切换发言人（与智能识别"唯一发言人"区别）
                    // 被屏蔽的音色不生效（彻底关闭）
                    if (emo.engineCode.isNotBlank() && emo.voice.isNotBlank() &&
                        !AppPrefs.isVoiceMuted(emo.engineCode, emo.voice)) {
                        eng = emo.engineCode
                        voice = emo.voice
                    }
                    speed = emo.speed; volume = emo.volume; pitch = emo.pitch
                }
                // 对白也应用智能识别标记（"大声说"等）——保留标记，TtsService 解析 volume 增益
                val processedText = com.mtts.app.util.TextPreprocessor.applySmartFeature(t)
                out.add(seg.copy(
                    isSpeech = true, speaker = speaker,
                    engineCode = eng, voice = voice, speed = speed, volume = volume, pitch = pitch,
                    text = processedText))
            }
        }
        return out
    }

    // ==================== 自动识别角色 ====================

    /** 常见代词/称谓（不作为角色名） */
    private val PRONOUN_BLACKLIST = setOf(
        "她", "他", "你", "我", "它", "您", "咱",
        "我们", "你们", "她们", "他们", "它们", "咱们", "人家", "别人",
        "大家", "众人", "有人", "某人", "自己", "彼此"
    )

    /** 动作短语黑名单：这些虽能命中说话格式但不是人名 */
    private val PHRASE_BLACKLIST = setOf(
        "没有回头", "回过头", "转过头", "抬起头", "低下头",
        "摇摇头", "点点头", "想了想", "愣了愣", "叹了口气",
        "叹了一口气", "清了清嗓子", "回过头来", "转过头来",
        "抬起头来", "低下头来", "皱了皱眉", "沉吟片刻",
        "沉默片刻", "犹豫片刻"
    )

    /** 净化候选名：基于 jieba 分词提取可能的人名（"莉可忽然"→"莉可"，"没有回头"→""） */
    private fun purifyName(raw: String): String =
        JiebaNameExtractor.extractName(raw)

    /** ★ NER 模型输出尾巴裁剪："苍宫月淡淡道"→"苍宫月" / "林玖冷冷地问"→"林玖"
     *  （模型偶尔把动作/语气词吸进人名 span，像 Linux 融合评测 post_tail_clean 那样剥掉）
     */
    private fun postTailClean(name: String): String {
        val tails = listOf(
            "淡淡道", "冷冷地问", "轻声问", "低声问", "笑着说", "笑道", "叹道", "说道", "问道",
            "愤怒地说", "平静地说", "缓缓道", "沉声道", "轻声道", "冷冷道", "笑着说"
        )
        for (t in tails) {
            if (name.endsWith(t) && name.length > t.length + 1) {
                return name.dropLast(t.length)
            }
        }
        // 单字动作尾
        if (name.length > 2 && name.last() in "说问道喊叹叫嚷应") {
            return name.dropLast(1)
        }
        return name
    }

    // ==================== 性别判定（按名称优先） ====================

    /**
 * 性别判定：三档优先级
 *  ① 称谓词组（子串匹配）：周掌柜/林晚姑娘/老爷/娘子 → 直接判定
 *  ② 名字用字（后缀锚定）：末字命中即判定（张泽坤→坤=男；李婉 → 婉=女）
 *  ③ 后缀双命中 → 男表优先；都未命中 → null（交给代词统计兜底）
 */
    /** 男名「称谓词组」：子串匹配（这些字组基本不会出现在普通姓名里） */
    private val MALE_NAME_PHRASES = setOf(
        "掌柜", "掌柜的", "老爷", "少东", "老伯", "大伯", "伯伯", "叔叔",
        "大叔", "阿公", "爷爷", "公公", "先生", "郎中", "医师", "大夫",
        "师傅", "师父", "老丈", "老爹", "阿爹", "老哥", "军爷", "大爷",
        "少爷", "公子", "少侠", "将军", "元帅", "军师", "老道", "道士", "和尚"
    )

    /** 男名「用字」：后缀锚定匹配（用户权威表全文，含单/双字） */
    private val MALE_NAME_CHARS: String =
        "锴策罡焜标坡铖彪勃栋柱翔豪强滔虎鼎鹏猛铠阔魁将韬鉴闯烽潮刚森洲城钢骥嵩震锋侃浩壮啸旺帅龙泰骁昊权钊铎豹翀骏亮勇皓楼山涛斌桓灏耿臣仓财峰峻煌镇序政铁统兵磊纲榜雄诚丞雍法昶宾霆挺威晟赐武朋键蛟滨枭超腾麟岗创拓冲骐翰昆凯博通乾岳竣植康达炯瀚军剑健呈坚梁雷港升生炜民州恺楷科河哲驰隆毅野盛湖泉勋展堃劲济川松振耀昂恒奎璋求才攀锟航库冠征奇屹奔帝骋淞骅夫琨战彬巨彧东举高宽喆江庚堂蓬印伟基功良经轲翼舟祥铮深邦迅涌朔弘舜秉波钧积更淦浚智源羿浪峥忠辉众团富丰寅任尧义致垒亨泽轶行敦委声杭北麒胜成谦孔际殿进志全旭炳章顶特程伯坤柏列吾起铭原昌占晖渊重宇曙发业巍周钟鸣旗谋裕衡力硕承拥国上飞赫彭杰付增洋申赞佑珩前骞玺晋禄佐禹次圣广厚天余录省甫建为帆锐劼中理跃纬观泓崇桥普福光璞聚俊用添名燚轩远佰鸿能宏望庭衍炼首尚炎仁实治宸旻明仲陆校根响典兴扬嗣礼繁尊信流岐均伦樟位阳锁德鑫祯见常傲闻争郑辰甲欧保合图体顺土汉赟公庄霖忱朗逢竞启星遵现祖贵记主岸嵘石宪琛默胤岭友捷得家贺林游临集有官煜寿永训向选长太延葛冶毕动宗齐大聪奥兆钦留修照朝鲁淳绍计克烈戈道枫元正玮初相锡玖恩伍庆芃其鹤久焱养善昭非唐谷万焰灼同臻洪继" +
        "士世村加立铨仑铸昇棚烜苟焘号樑府逊敢海仕燊火舰操材登盟栓颢帮淮淏疆鲲渤坦宙社魏本水珲召浦传驹渠泳显怀茂澍斗学日冀来献应辽树木澎槐胡开卫季模安九里和孝冬师农慰弛徽牧运都纪领译备喜收党沛干千地韩放于要洵潘就育昱未曾术熠熹绪祁炀张丙布尘灯满池栩古钱定新文杨交六韦陵宝田乐廷颂人京让睿益郡袁愚革关"

    /** 女名「称谓词组」：子串匹配 */
    private val FEMALE_NAME_PHRASES = setOf(
        "娘子", "姑娘", "小姐", "夫人", "媳妇", "婆娘", "婶子", "大嫂", "小妾",
        "阿娘", "母亲", "祖母", "婆婆", "奶奶", "姥姥", "外婆"
    )

    /** 女名「用字」：后缀锚定匹配（用户权威表全文） */
    private val FEMALE_NAME_CHARS: String =
        "婷媛氏婕婧姝娴妮妹娜娥妍娟娣茵婉莎琴莲娇娅梅霞倩花茶莉菊翠茹姣薇囡萍蕊丽玲素兰淑蓉芬萱珍恬芹茜玫姿雯粉芷卉芯滢香菡琬馨蓓珊丝仙曼钗姑甜莹艳枚荷伶芊妃秀雅影燕英美彩蕙晴巧芳莺琼姬朵蔓樱漫容芝女绒改怡玥静杏苒凤绮荟枝俐芙依菲盈语苹筱悠芸霜珠腊紫碧璇汐漪伊慧换瑶桃沫丹瑛洁桂梨苓月棉歆颖柔彤琳筠爱微雪梦瑄凝俏儿惠变艾绘柠圆岚悦韵鸽蕾幼铃兮带央妙诺虹么璐恋分仪菁米惜苗霏露苑荧红采玛麦吟含七宛沁贞瑾欣闪环佩晶鲜秋柳诗盼净园舒芮转叶第珺音咏停穗飘若澜靓灵格蓝娃密招蜜嘎阿玉心葵扣会敏萌引晚竹涵细颜单屏琪沙函连忆贝勤群钰目慈雁雨予淇莫优俞豆艮笑风缘真蕴术尹冉早艺小亭欢绿妞鸾奶玟烟眉汝瞳菱针潞湾晞夕草銮羚宜拿二纳芽云因子雲鱼如尔范璠漩指思朦橙示愉榕唯唱丛我喻沅爽皖"

    /** 姓名里出现就属于噪声（既非男字也非女字），先剔除再判定 */
    private val NAME_NOISE_CHARS: String = "之了和与是为在上下天中大子小日月年时" +
        "一二三四五六七八九十百千万零甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥"

    /**
     * 按【角色名称】判定性别：
     *  ① 称谓词组子串命中 → 直接判定
     *  ② 后缀锚定：去掉噪声字后取最后 1~2 个汉字匹配用字表
     *  ③ 双表都命中 → 男表优先；都未命中 → null
     */
    private fun genderByName(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        // ① 称谓词组（子串匹配）
        if (MALE_NAME_PHRASES.any { n.contains(it) }) return "男"
        if (FEMALE_NAME_PHRASES.any { n.contains(it) }) return "女"
        // ② 后缀锚定：去掉噪声字，取末尾字符与用字表比对
        val cleaned = n.filterNot { NAME_NOISE_CHARS.contains(it) }
        val s = cleaned.ifEmpty { n }
        val tail1 = s.lastOrNull()
        val tail2 = if (s.length >= 2) s.takeLast(2) else ""
        val mHit = (tail1 != null && MALE_NAME_CHARS.contains(tail1)) ||
            (tail2.isNotEmpty() && MALE_NAME_CHARS.contains(tail2))
        val fHit = (tail1 != null && FEMALE_NAME_CHARS.contains(tail1)) ||
            (tail2.isNotEmpty() && FEMALE_NAME_CHARS.contains(tail2))
        return when {
            mHit && !fHit -> "男"
            fHit && !mHit -> "女"
            mHit && fHit -> "男"   // 双命中：男表优先
            else -> null
        }
    }

    /**
     * 完整性别判定（名称优先 → 代词兜底 → 默认女）
     */
    private fun guessGender(name: String, sheCount: Int, heCount: Int): String {
        genderByName(name)?.let { return it }
        return if (heCount > sheCount) "男" else "女"
    }

    /**
     * 从小说文本自动识别说话人（强格式：冒号+引号结构，避免"知道/街道"误判）。
     * 返回 DetectedRole 列表（人名 + 命中规则 + 推测性别分组），UI 只展示这两样。
     */
    fun autoDetectCharacters(text: String): List<DetectedRole> {
        if (text.isBlank()) return emptyList()
        // 1) 提取名字：统计出现次数（权重）+ 命中规则
        val countMap = HashMap<String, Int>()       // name -> 出现次数
        val ruleMap = HashMap<String, String>()     // name -> 命中规则
        // ★ 已注册角色名作为分词优先词典（避免"苍宫月说道"把"说道"切进人名）
        val knownNames = AppPrefs.loadCharacters().map { it.name }.filter { it.isNotBlank() }.toSet()
        // ★ 0. NER 模型真名池（最高优先级）：bert-mini 直接识别文本中的所有人名（PER span）
        //    —— 动作/神态/并列/引号后置全覆盖，不依赖"说/道"动词（Linux 融合评测 F1=0.846）
        //    ★ 懒加载：不判 isReady（未加载时 persons() 内部自动加载，失败返回空）
        var nerHits = 0
        val allText = text
        if (allText.isNotBlank()) {
            try {
                for (p in NerOnnx.persons(allText)) {
                    val clean = postTailClean(p)
                    if (clean.length >= 2 && clean !in PRONOUN_BLACKLIST && clean !in PHRASE_BLACKLIST) {
                        // ★ 模型高置信：等同 5 次规则出现 → 默认勾选（>= HIGH_FREQ_THRESHOLD）
                        countMap[clean] = (countMap[clean] ?: 0) + 5
                        if (clean !in ruleMap) ruleMap[clean] = "模型识别（NER）"
                        nerHits++
                    }
                }
                com.mtts.app.util.MttsLog.d("NER", "autoDetect: NER hits=${nerHits}, ready=${NerOnnx.isReady}")
            } catch (t: Throwable) {
                com.mtts.app.util.MttsLog.w("NER", "detect err: ${t.message}")
            }
        } else {
            com.mtts.app.util.MttsLog.d("NER", "autoDetect: NER skipped (ready=${NerOnnx.isReady}, state=${NerOnnx.loadState})")
        }
        // ★ 统计驱动发现（主力）：动作/神态描写引导的发言（"林玖皱起眉头：「…」"）也能自动发现人名
        for (n in discoverSpeakersByStats(text)) {
            countMap[n] = (countMap[n] ?: 0) + 1
            if (n !in ruleMap) ruleMap[n] = "统计发现（主语位高频）"
        }
        fun collect(re: Regex, rule: String) {
            for (m in re.findAll(text)) {
                val raw = m.groupValues[1].trim()
                if (raw.isBlank() || raw in PRONOUN_BLACKLIST) continue
                // ★ 净化：jieba 分词提取（"莉可忽然"→"莉可"，"没有回头"→""），已注册角色名优先
                val who = if (knownNames.any { raw.startsWith(it) }) {
                    knownNames.first { raw.startsWith(it) }
                } else JiebaNameExtractor.extractName(raw, knownNames)
                if (who.isBlank() || who in PRONOUN_BLACKLIST) continue
                if (who in PHRASE_BLACKLIST || raw in PHRASE_BLACKLIST) continue
                // ★ 名字 ≥ 2 字才认（单字"道/说/她"这类不是人名）
                if (who.length < 2) continue
                countMap[who] = (countMap[who] ?: 0) + 1
                if (who !in ruleMap) ruleMap[who] = rule
            }
        }
        collect(SPEAKER_COLON_RE, "名字(+动词)+冒号+引号")
        collect(SPEAKER_AFTER_QUOTE_RE, "引号结束+名字+动词")
        collect(SPEAKER_VERB_QUOTE_RE, "名字+动词+引号")
        collect(SPEAKER_COMMA_NEWLINE_RE, "名字+动词+逗号+换行+引号")
        // ★ 称谓/身份词锚点（对齐朗读链路 speakerTitleIn）：
        //   "班长有些不悦地皱起眉头：" → 班长；"周老师推门进来，笑着说：" → 周老师
        //   动作描写/插入语（逗号）夹在称谓和冒号之间也算发言——逗号不切断，只认说话主体位。
        val anchorTitleRe = Regex("""[：:][\s]*["“‘「『]""")
        // ★ 只以"强终止符"切断（句号/叹号/问号/分号/顿号/换行/省略号）；
        //   逗号是插入语/动作描写常见分隔，不能切断，否则"周老师推门进来，笑着说："
        //   会被切成"笑着说"丢称谓。
        val fragSplitReLocal = Regex("""[。.！!？?；;、

…]""")
        for (m in anchorTitleRe.findAll(text)) {
            val ci = m.range.first
            var start = ci
            for (b in fragSplitReLocal.findAll(text.substring(0, ci).reversed())) {
                start = ci - b.range.first - 1
                break
            }
            // 动作描写可能较长（"老板站在柜台后看了看账本，才缓缓开口："），放宽回看窗口到 24 字
            if (ci - start > 24) start = ci - 24
            val frag = text.substring(start, ci).trim()
            if (frag.isBlank()) continue
            val anchor = speakerTitleIn(frag, knownNames.toList())
            if (anchor != null && anchor.length >= 2 &&
                anchor !in PRONOUN_BLACKLIST && anchor !in PHRASE_BLACKLIST) {
                countMap[anchor] = (countMap[anchor] ?: 0) + 1
                if (anchor !in ruleMap) ruleMap[anchor] = "称谓/身份词（说话主体位）"
            }
        }
        if (countMap.isEmpty()) return emptyList()
        // 2) 统计「她/他」推测性别
        val segs = splitSegments(text)
        val tempChars = countMap.keys.map { DialogCharacter(name = it) }
        val she = HashMap<String, Int>()
        val he = HashMap<String, Int>()
        var lastNarration: String? = null
        for (seg in segs) {
            val t = seg.text.trim()
            if (t.isEmpty()) continue
            if (!seg.isSpeech) {
                lastNarration = t
            } else {
                // ★ 用全文人名池锚定（countMap 即池），动作描写引导的发言也能正确归属
                val sp = identifySpeaker(lastNarration, tempChars, countMap.keys.toSet())
                if (sp.isNotBlank()) {
                    val n = lastNarration.orEmpty()
                    if (n.contains("她")) she[sp] = (she[sp] ?: 0) + 1
                    if (n.contains("他")) he[sp] = (he[sp] ?: 0) + 1
                }
            }
        }
        // 3) 组装结果：按出现次数（权重）降序排列，重复词优先
        val out = ArrayList<DetectedRole>()
        val names = countMap.keys.toMutableList()
        names.sortByDescending { countMap[it] ?: 0 }
        for (n in names) {
            val s = she[n] ?: 0
            val h = he[n] ?: 0
            out.add(DetectedRole(
                name = n,
                rule = ruleMap[n] ?: "",
                // ★ 性别判定：名称特征词优先（周掌柜→男、林晚→女），再代词兜底，最后默认女
                gender = guessGender(n, s, h),
                count = countMap[n] ?: 1
            ))
        }
        return out
    }

    // ==================== 预览（供 UI 测试） ====================

    /** 预览对话功能处理结果 */
    fun preview(text: String, engineVoicesByEngine: (String) -> List<com.mtts.app.engine.Voice>, defaultEngine: String, defaultVoice: String): List<DialogSeg> {
        return process(text, engineVoicesByEngine, defaultEngine, defaultVoice) ?: emptyList()
    }
}