package com.mtts.app.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM 重采样工具 —— 将任意采样率的 16bit 单声道 PCM 重采样到目标采样率。
 *
 * 多引擎对话时，各引擎输出采样率不同（微软 24kHz / 搜狗 16kHz），
 * 需统一到同一采样率后拼接进单个 AudioTrack 连续播放。
 * 采用线性插值（interpolated linear），质量对语音足够，开销极低。
 */
object PcmResampler {

    /**
     * 把 16bit 单声道 LE PCM 从 srcRate 重采样到 dstRate。
     * @param pcm 原始 PCM 字节
     * @param srcRate 原始采样率（Hz）
     * @param dstRate 目标采样率（Hz）
     * @return 重采样后的 16bit 单声道 LE PCM 字节
     */
    fun resample16BitMono(pcm: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        if (pcm.isEmpty()) return pcm
        if (srcRate <= 0 || dstRate <= 0) return pcm
        if (srcRate == dstRate) return pcm
        if (pcm.size % 2 != 0) {
            // 保护：丢弃末尾半个样本，避免解析错位
            return resample16BitMono(pcm.copyOfRange(0, pcm.size - 1), srcRate, dstRate)
        }

        val src = ShortArray(pcm.size / 2)
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().get(src)

        // 输出样本数 = 源样本数 * dst/src，向上取整保证覆盖全时长
        val outCount = (src.size.toLong() * dstRate / srcRate).toInt() + 1
        val out = ShortArray(outCount)

        val step = srcRate.toDouble() / dstRate.toDouble()   // 每个输出样本对应源位置增量
        var pos = 0.0
        for (i in 0 until outCount) {
            val idx = pos.toInt()
            val frac = pos - idx
            val s0 = src[idx.coerceAtMost(src.size - 1)]
            val s1 = if (idx + 1 < src.size) src[idx + 1] else s0
            out[i] = (s0 + ((s1 - s0) * frac).toInt()).toShort()
            pos += step
        }

        val outBuf = ByteBuffer.allocate(out.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        outBuf.asShortBuffer().put(out)
        return outBuf.array()
    }

    /**
     * 对 16bit 单声道 LE PCM 施加线性增益（音量放大/缩小）。
     * @param pcm 原始 PCM 字节
     * @param gain 增益系数（1.0=不变；>1 放大；<1 缩小）
     * @return 增益后的 PCM 字节（长度不变）
     */
    fun gain16BitMono(pcm: ByteArray, gain: Float): ByteArray {
        if (pcm.isEmpty() || gain <= 0f || gain == 1f) return pcm
        if (pcm.size % 2 != 0) return gain16BitMono(pcm.copyOfRange(0, pcm.size - 1), gain)

        val src = ShortArray(pcm.size / 2)
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().get(src)

        val out = ShortArray(src.size)
        for (i in src.indices) {
            // 16bit 有符号范围 -32768..32767，钳制防削波
            val v = (src[i].toInt() * gain).toInt().coerceIn(-32768, 32767)
            out[i] = v.toShort()
        }

        val outBuf = ByteBuffer.allocate(out.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        outBuf.asShortBuffer().put(out)
        return outBuf.array()
    }

    /**
     * 对 16bit 单声道 LE PCM 施加「不变速变调」：音高倍率 ratio（>1 升高、<1 降低），时长不变。
     *
     * 原理（标准两步重采样）：
     *   ① 把 PCM 以 srcRate 重采样到 srcRate×ratio —— 音高升 ratio 倍，时长缩为 1/ratio
     *   ② 再重采样回 srcRate —— 时长恢复，音高保持 ×ratio
     *
     * 适用场景：引擎合成层不支持 pitch（如搜狗 SDK），用 PCM 后处理补偿音高。
     * 代价：两次线性插值引入轻微质量损失，对语音可接受。
     *
     * @param pcm 原始 PCM 字节（16bit/单声道/LE）
     * @param srcRate 原始采样率（Hz）
     * @param ratio 音高倍率（1.0=不变；建议范围 [0.5, 2.0]）
     * @return 变调后的 PCM 字节（时长与输入一致，样本数可能 ±1 差异）
     */
    fun pitchShift16BitMono(pcm: ByteArray, srcRate: Int, ratio: Float): ByteArray {
        if (pcm.isEmpty() || ratio <= 0f || ratio == 1f) return pcm
        if (srcRate <= 0) return pcm
        val r = ratio.coerceIn(0.5f, 2.0f)
        if (r == 1f) return pcm
        // ① 升采样/降采样到 srcRate×r（音高偏移）
        val mid = (srcRate.toFloat() * r).toInt().coerceAtLeast(1000)
        val shifted = resample16BitMono(pcm, srcRate, mid)
        // ② 重采样回 srcRate（时长恢复）
        return resample16BitMono(shifted, mid, srcRate)
    }

    /**
     * 峰值归一化：把 PCM 峰值缩放到 targetPeak（防削波，带增益钳制）。
     * 用于跨引擎音量均衡 —— 让不同引擎在 volume=1.0 时输出统一听感。
     *
     * @param pcm 16bit 单声道 LE PCM
     * @param targetPeak 目标峰值（0..32767，实际钳到 32000）
     * @param maxGain 最大允许增益（防噪声爆炸），默认 4.0（约 12dB）
     * @param minGain 最小允许增益（防过度衰减，即原本已经很响的引擎不再压更响），默认 0.3
     */
    fun normalizeToPeak16BitMono(pcm: ByteArray, targetPeak: Int, maxGain: Float = 4.0f, minGain: Float = 0.3f): ByteArray {
        if (pcm.isEmpty() || pcm.size % 2 != 0 || targetPeak <= 0) return pcm
        var peak = 0
        var i = 0
        while (i < pcm.size) {
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            i += 2
        }
        // 太静（静音/接近静音），不做放大，避免噪声爆炸
        if (peak < 128) return pcm
        val target = targetPeak.coerceIn(1, 32000)
        val gain = (target.toFloat() / peak).coerceIn(minGain, maxGain)
        if (gain == 1f) return pcm
        return gain16BitMono(pcm, gain)
    }

    /**
     * 跨引擎音量均衡（推荐封装）：
     * 引擎合成时**始终传 volume=1.0**（拿引擎的"基准声"），
     * 再由本函数按用户 volume 倍率做峰值归一化，保证各引擎在相同 volume 下听感一致。
     *
     * @param pcm 引擎原始输出（volume=1.0 时的基准）
     * @param volume 用户音量倍率（1.0 = 基准；对应 UI 50）
     * @param targetPeak 基准峰值（volume=1.0 时归一化到该值，默认 18000 ≈ -5.2dBFS）
     * @param maxGain 最大允许增益（防噪声爆炸）
     */
    fun balanceVolume16BitMono(
        pcm: ByteArray,
        volume: Float,
        targetPeak: Int = 18000,
        maxGain: Float = 4.0f
    ): ByteArray {
        if (pcm.isEmpty()) return pcm
        val v = volume.coerceIn(0f, 2.5f)
        if (v <= 0.001f) return ByteArray(pcm.size)  // 静音
        val target = ((targetPeak * v).toInt()).coerceIn(1, 32000)
        return normalizeToPeak16BitMono(pcm, target, maxGain)
    }
}
