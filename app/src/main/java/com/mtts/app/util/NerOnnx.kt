package com.mtts.app.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ★ 中文 NER 人名识别（ONNX Runtime 推理封装）
 *
 * 模型：bert-mini H288 (BIO 9 标签)，from TomatoMTL/bert-mini-finetuned-ner-chinese-onnx
 *  - assets/ner/model_quantized.onnx  (12.6MB, int8 量化)
 *  - assets/ner/vocab.txt             (21128 词表)
 * 输入：input_ids / attention_mask / token_type_ids  [batch, seq] int64
 * 输出：logits [batch, seq, 9]  (0=O 1=B-PER 2=I-PER 3=B-ORG 4=I-ORG 5=B-LOC 6=I-LOC 7=B-MISC 8=I-MISC)
 *
 * 中文按单字符 token（与 Linux 原型 eval_accuracy.NER 逻辑一致）；
 * 非中文（英文/数字/符号）按单字符近似 token（[UNK] 兜底）。
 * 推理结果只取 PERSON（B-PER/I-PER）span，返回人名列表。
 *
 * 线程安全：session 创建后只读，run() 可并发调用（OrtSession.run 线程安全）。
 */
object NerOnnx {

    private const val MAX_LEN = 256
    private const val TAG = "NerOnnx"

    /** 9 个 BIO 标签（与模型输出 logits 最后一维对应） */
    private val LABELS = arrayOf(
        "O", "B-PER", "I-PER", "B-ORG", "I-ORG",
        "B-LOC", "I-LOC", "B-MISC", "I-MISC"
    )

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var tok2id: Map<String, Int>? = null
    @Volatile private var ready = false
    private val lock = Any()

    // ★ 性能控制：空闲释放（没朗读/没识别时不常驻内存）
    private const val IDLE_RELEASE_MS = 120_000L   // 空闲 2 分钟自动释放 session（~12.6MB）
    @Volatile private var lastUsedAt = 0L          // 最近一次推理时间戳（elapsedRealtime）
    private val idleScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ner-idle").apply { isDaemon = true }
    }

    // ★ 懒加载：App 启动不预热，首次朗读/识别时才加载（需要 applicationContext）
    @Volatile private var appContext: Context? = null
    private val idleCheckStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 绑定全局 Context（MttsApplication.onCreate 调用，供懒加载用） */
    fun attach(context: Context) {
        appContext = context.applicationContext
        if (idleCheckStarted.compareAndSet(false, true)) {
            // 周期巡查：就绪但超过空闲阈值未使用 → 释放
            idleScheduler.scheduleWithFixedDelay({
                try {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (ready && lastUsedAt > 0 && now - lastUsedAt > IDLE_RELEASE_MS) {
                        MttsLog.d(TAG, "idle ${(now - lastUsedAt) / 1000}s > ${IDLE_RELEASE_MS / 1000}s → release")
                        release()
                    }
                } catch (_: Throwable) {}
            }, 30, 30, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /** 主动释放：关闭 session、清词表，下次使用自动重新加载（env 是 ORT 全局单例，不关） */
    fun release() {
        synchronized(lock) {
            if (!ready) return
            try { session?.close() } catch (_: Throwable) {}
            session = null
            // env 是 OrtEnvironment.getEnvironment() 全局单例，sherpa 引擎同进程共用，绝不能 close
            env = null
            tok2id = null
            ready = false
            lastUsedAt = 0
            loadState = "已释放（用时自动加载）"
            MttsLog.d(TAG, "released")
        }
    }

    /** 加载状态（供 UI 展示）：未加载 / 加载中 / 就绪 / 失败 */
    @Volatile var loadState: String = "未加载"
    @Volatile var loadError: String? = null
    @Volatile var loadMs: Long = 0

    /** 是否已加载就绪 */
    val isReady: Boolean get() = ready

    /** 后台加载（assets 读取 + session 创建，需在非主线程调用） */
    fun load(context: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            loadState = "加载中…"
            loadError = null
            try {
                val t0 = System.currentTimeMillis()
                // 1) 读 assets
                val modelBytes = context.assets.open("ner/model_quantized.onnx").use { it.readBytes() }
                val vocabLines = context.assets.open("ner/vocab.txt").use { it.bufferedReader().readLines() }
                // 2) 词表 → id
                val map = HashMap<String, Int>(vocabLines.size * 2)
                for ((i, line) in vocabLines.withIndex()) {
                    val w = line.removeSuffix("\n").removeSuffix("\r")
                    if (w.isNotEmpty()) map[w] = i
                }
                tok2id = map
                // 3) 建 session（CPU provider）
                val e = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions()
                // int8 量化模型在 CPU 上跑（NNAPI 对 DynamicQuantizeLinear 支持有限，先纯 CPU 保正确）
                val s = e.createSession(modelBytes, opts)
                env = e
                session = s
                ready = true
                loadMs = System.currentTimeMillis() - t0
                loadState = "就绪"
                MttsLog.d(TAG, "loaded in ${loadMs}ms, vocab=${map.size}")
            } catch (t: Throwable) {
                loadState = "失败"
                loadError = t.message
                MttsLog.w(TAG, "load failed: ${t.message}")
                // 失败不置 ready，下次可重试
            }
        }
    }

    /** 中文判断 */
    private fun isHan(c: Char): Boolean = c in '\u4e00'..'\u9fff'

    /**
     * tokenize（与 Linux NER._tok 一致）：
     * [CLS] + 逐字符（中文单字/符号单 token；空白跳过） + [SEP] + pad 到 MAX_LEN
     */
    private fun tokenize(
        text: String,
        ids: LongArray, mask: LongArray, seg: LongArray
    ) {
        val map = tok2id ?: return
        ids.fill(0); mask.fill(0); seg.fill(0)
        var n = 0
        ids[0] = map["[CLS]"]?.toLong() ?: 101L
        mask[0] = 1L; seg[0] = 0L
        n = 1
        for (ch in text) {
            if (ch.isWhitespace()) continue
            if (n >= MAX_LEN - 1) break
            val idx = map[ch.toString()] ?: map["[UNK]"] ?: 100
            ids[n] = idx.toLong()
            mask[n] = 1L
            seg[n] = 0L
            n++
        }
        ids[n] = map["[SEP]"]?.toLong() ?: 102L
        mask[n] = 1L
        // 其余保持 0（pad）
    }

    /**
     * 对文本做 NER，返回人名列表（B-PER/I-PER span，BIO 解码）。
     * 未就绪/失败 → 空列表（不抛异常，调用方无需感知）。
     * ★ 懒加载：若已释放/未加载，自动用 attach 时的 context 重新加载（首次调用约几百 ms）。
     */
    fun persons(text: String): List<String> {
        if (!ready) {
            val ctx = appContext
            if (ctx != null) {
                load(ctx)   // 内部有锁 + ready 判断，幂等
            } else {
                return emptyList()
            }
        }
        val s = session ?: return emptyList()
        val map = tok2id ?: return emptyList()
        if (text.isBlank()) return emptyList()
        lastUsedAt = android.os.SystemClock.elapsedRealtime()
        return try {
            val ids = LongArray(MAX_LEN)
            val mask = LongArray(MAX_LEN)
            val seg = LongArray(MAX_LEN)
            tokenize(text, ids, mask, seg)
            val shape = longArrayOf(1L, MAX_LEN.toLong())
            val env0 = env ?: return emptyList()
            val (logits, seqLen) = runSession(env0, s, ids, mask, seg, shape) ?: return emptyList()
            decodePersons(logits, seqLen, ids, mask, map)
        } catch (t: Throwable) {
            MttsLog.w(TAG, "persons err: ${t.message}")
            emptyList()
        }
    }

    private fun runSession(
        env0: OrtEnvironment,
        s: OrtSession,
        ids: LongArray, mask: LongArray, seg: LongArray,
        shape: LongArray
    ): Pair<FloatBuffer, Int>? {
        // 输入张量（int64 [1, seq]）
        val idsBuf = LongBuffer.wrap(ids)
        val maskBuf = LongBuffer.wrap(mask)
        val segBuf = LongBuffer.wrap(seg)
        var tIds: OnnxTensor? = null
        var tMask: OnnxTensor? = null
        var tSeg: OnnxTensor? = null
        var res: OrtSession.Result? = null
        return try {
            tIds = OnnxTensor.createTensor(env0, idsBuf, shape)
            tMask = OnnxTensor.createTensor(env0, maskBuf, shape)
            tSeg = OnnxTensor.createTensor(env0, segBuf, shape)
            val inputs = LinkedHashMap<String, OnnxTensor>()
            inputs["input_ids"] = tIds
            inputs["attention_mask"] = tMask
            inputs["token_type_ids"] = tSeg
            res = s.run(inputs)
            val out = res[0] // logits [1, seq, 9]
            val t = out as? OnnxTensor ?: return null
            val shape0 = t.info.shape // [1, seq, 9]
            val seqLen = if (shape0.size >= 2) shape0[1].toInt() else MAX_LEN
            t.floatBuffer to seqLen
        } finally {
            try { tIds?.close() } catch (_: Exception) {}
            try { tMask?.close() } catch (_: Exception) {}
            try { tSeg?.close() } catch (_: Exception) {}
            try { res?.close() } catch (_: Exception) {}
        }
    }

    /** BIO 解码：只取 PER span；logits 为扁平 FloatBuffer [batch*seq*9] */
    private fun decodePersons(
        logits: FloatBuffer,
        seqLen: Int,
        ids: LongArray, mask: LongArray,
        map: Map<String, Int>
    ): List<String> {
        // argmax per token（batch=0）
        val preds = IntArray(seqLen)
        for (i in 0 until seqLen) {
            var best = 0
            var bestV = Float.NEGATIVE_INFINITY
            val base = i * 9
            for (j in 0 until 9) {
                val v = if (base + j < logits.limit()) logits.get(base + j) else Float.NEGATIVE_INFINITY
                if (v > bestV) { bestV = v; best = j }
            }
            preds[i] = best
        }
        // 反转词表 id→token
        val inv = HashMap<Int, String>(map.size * 2)
        for ((k, v) in map) inv[v] = k

        val tokens = ArrayList<String>()
        val labels = ArrayList<String>()
        for (i in ids.indices) {
            if (mask[i] == 0L || i == 0) continue
            if (i >= seqLen) break
            val t = inv[ids[i].toInt()] ?: ""
            if (t == "[SEP]" || t == "[CLS]" || t == "[PAD]" || t.isEmpty()) continue
            tokens.add(t)
            labels.add(if (preds[i] in LABELS.indices) LABELS[preds[i]] else "O")
        }
        // BIO 解码 PER span
        val spans = ArrayList<String>()
        var cur = StringBuilder()
        var inPer = false
        for (i in tokens.indices) {
            val lbl = labels[i]
            when {
                lbl == "O" -> { if (inPer) { spans.add(cur.toString()); cur = StringBuilder(); inPer = false } }
                lbl == "B-PER" -> { if (inPer) { spans.add(cur.toString()); cur = StringBuilder() }; cur = StringBuilder(tokens[i]); inPer = true }
                lbl == "I-PER" -> { if (inPer) cur.append(tokens[i]) else { cur = StringBuilder(tokens[i]); inPer = true } }
                else -> { if (inPer) { spans.add(cur.toString()); cur = StringBuilder(); inPer = false } }
            }
        }
        if (inPer) spans.add(cur.toString())
        return spans
    }
}