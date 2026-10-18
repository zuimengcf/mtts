package com.mtts.app.util

import com.huaban.analysis.jieba.JiebaSegmenter
import com.huaban.analysis.jieba.SegToken

/**
 * 说话人识别分词封装 —— 基于 jieba-analysis（Maven com.huaban:jieba-analysis:1.0.2）。
 *
 * 利用 jieba 的词典 + HMM 把候选片段切成词，从中提取"像人名"的部分：
 *  - "莉可忽然说" → 分词 [莉可][忽然][说] → 人名 "莉可"
 *  - "苍宫月说道：「…」" → 分词 [苍宫月][说道] → 人名 "苍宫月"
 *  - "没有回头道" → 分词 [没有][回头][道] → 无名词 → 丢弃
 *
 * 人名判定：词长度 2~4、不在黑名单（代词/动作/虚词）、
 * 且 jieba 分词后该词可作为独立 token 存在（或在说话动词/修饰词前紧邻）。
 * 已注册角色名优先：命中已注册角色 → 直接用。
 */
object JiebaNameExtractor {

    /**
     * 分词器（异步加载）。词库 ~2MB + HMM 概率表，首次加载 5~15s。
     * 为"后台载入词库、不阻塞请求"：加载放在独立线程，未就绪前
     * cut()/extractName() 立即返回（跳过 jieba 净化，走纯黑名单/规则过滤），
     * 绝不在调用线程上同步触发加载 —— 避免 HTTP /detect 首次调用被卡死。
     */
    @Volatile
    private var segmenter: JiebaSegmenter? = null

    /** 词库是否已加载就绪 */
    @Volatile
    private var ready: Boolean = false

    /** 后台预热：异步触发词典加载（已在 MttsApplication 的非主线程调用） */
    fun warmUp() {
        // 幂等：已就绪或已在加载则跳过
        if (ready || loadStarted.get()) return
        if (!loadStarted.compareAndSet(false, true)) return
        try {
            segmenter = JiebaSegmenter()
            // 触发一次分词以完成词典/HMM 初始化
            segmenter?.process("预热测试文本", JiebaSegmenter.SegMode.SEARCH)
            ready = true
            com.mtts.app.util.MttsLog.d("Jieba", "seg ready")
        } catch (t: Throwable) {
            com.mtts.app.util.MttsLog.w("Jieba", "warmup failed: ${t.message}")
            // 失败重置，允许重试
            loadStarted.set(false)
        }
    }

    private val loadStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 说话动词（从长到短，用于从候选片段尾部剥离） */
    private val SPEAK_VERBS = listOf(
        "说道", "问道", "答道", "喊道", "叹道", "叫道", "笑道", "应道", "嘀咕道", "喃喃道",
        "回答说", "轻声说", "低声说", "大声说", "笑着问", "开口问",
        "说", "问", "答", "道", "喊", "回", "叹", "叫", "嚷", "应", "开口", "轻语", "低语", "喃喃"
    )

    /** 修饰词（人名与动词之间） */
    private val MODIFIERS = listOf(
        "忽然", "突然", "慢慢", "缓缓", "轻轻", "微微", "淡淡", "悄悄", "默默", "冷冷", "静静",
        "终于", "依旧", "仍然", "忽而", "似乎", "好像", "稍稍", "略微", "笑了笑", "冷笑", "苦笑",
        "微笑", "大笑", "于是", "然后", "接着", "随后", "迟疑", "犹豫", "顿了顿", "想了想",
        "转头", "回头", "转过身", "抬起头", "低下头", "皱起眉", "沉声", "低声", "轻声", "厉声",
        "也", "又", "便", "就", "才", "还", "并", "却", "更", "再", "刚", "正"
    )

    /** 动作短语黑名单（紧贴引号的非人名片段） */
    private val ACTION_PHRASES = setOf(
        "没有回头", "没有回答", "没有吭声", "没有接话", "没有理睬", "没有看他", "没有看她",
        "回过头", "转过头", "抬起头", "低下头", "摇摇头", "点点头", "摆了摆手", "挥了挥手",
        "叹了口气", "叹口气", "顿了顿", "沉默片刻", "沉吟片刻", "犹豫片刻", "想了想",
        "清了清嗓子", "咽了口唾沫", "揉了揉眼睛", "擦了擦汗", "耸了耸肩", "摊了摊手",
        "皱了皱眉", "眯了眯眼", "弯了弯唇", "勾了勾唇", "扯了扯嘴角"
    )

    /** 代词/称谓黑名单 */
    private val PRONOUNS = setOf(
        "她", "他", "你", "我", "它", "您", "咱",
        "我们", "你们", "她们", "他们", "它们", "咱们", "人家", "别人",
        "大家", "众人", "有人", "某人", "自己", "彼此"
    )

    /** 常见虚词/停用词（分词后若整体落在此集合则丢弃） */
    private val STOP_WORDS = setOf(
        "一个", "一些", "这时", "那时", "此刻", "这里", "那里", "身边", "面前", "身后", "旁边",
        "因为", "所以", "但是", "可是", "然而", "虽然", "如果", "那么", "于是", "然后", "接着",
        "突然", "忽然", "慢慢", "缓缓", "轻轻", "微微", "悄悄", "终于", "还是", "已经", "正在",
        "没有", "不是", "就是", "真是", "还是", "又是", "也是", "也能", "都要", "都会", "可以"
    )

    /** 分词（返回词列表）。词库未就绪时立即返回空列表（不阻塞、不触发加载，跳过 jieba 净化）。 */
    fun cut(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val seg = segmenter ?: return emptyList()
        return seg.process(text, JiebaSegmenter.SegMode.SEARCH)
            .map { it.word.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 从候选片段提取人名：
     *  - 先剥离尾部说话动词（说道/说/道…）
     *  - 再剥离尾部修饰词（忽然/慢慢…）
     *  - 用 jieba 分词检查剩余部分：若整体是黑名单词/短语 → 返回 ""
     *  - 若剩余部分 jieba 切出多词且最后一个是动词/修饰/动作 → 再剥
     *  - 最终长度 2~4 才认作人名
     */
    fun extractName(raw: String, knownNames: Set<String> = emptySet()): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""

        // 已注册角色名优先
        val hit = knownNames.firstOrNull { s.startsWith(it) }
        if (hit != null) return hit

        // 1) 剥离尾部说话动词
        for (v in SPEAK_VERBS) {
            if (s.length > v.length && s.endsWith(v)) {
                s = s.substring(0, s.length - v.length)
                break
            }
        }
        // 2) 剥离尾部修饰词（循环）
        var changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            for (m in MODIFIERS) {
                if (s.length > m.length && s.endsWith(m)) {
                    s = s.substring(0, s.length - m.length)
                    changed = true
                    break
                }
            }
        }
        // 3) 剥离尾部动作短语
        for (a in ACTION_PHRASES) {
            if (s == a || s.endsWith(a)) {
                s = s.substring(0, s.length - a.length)
                break
            }
        }
        val clean = s.trim()
        if (clean.length < 2) return ""

        // 4) 整体黑名单判定
        if (clean in PRONOUNS || clean in ACTION_PHRASES || clean in STOP_WORDS) return ""

        // 5) jieba 分词：若剩余部分切出多词且尾词是动词/修饰/动作 → 再剥
        val words = cut(clean)
        if (words.size >= 2) {
            val last = words.last()
            if (last in MODIFIERS || last in SPEAK_VERBS || last in ACTION_PHRASES || last in PRONOUNS) {
                val reduced = clean.removeSuffix(last).trim()
                if (reduced.length >= 2) return reduced
            }
        }
        // 6) 长度校验
        return if (clean.length in 2..4) clean else ""
    }
}