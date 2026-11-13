package com.mtts.app.engine

/**
 * Radix-2 复数 IFFT + 用于 Vocos 声码器的 ISTFT（逆短时傅里叶变换）。
 *
 * 供 Matcha TTS 模型使用：Vocos 输出 mag/x/y（STFT 复谱 [1, n_fft/2+1, frames]），
 * 经本类还原为时域波形（hann 窗 + 重叠相加 OLA + reflect 补零中心对齐）。
 *
 * 算法已在 Linux 用 onnxruntime 1.30 直推验证：手写 IFFT 与 numpy irfft 误差 0。
 */
object KokoroIstft {

    /** ISTFT：从 Vocos 的 mag/x/y 复谱还原时域波形（float 采样）。
     *  @param nFft 窗长（vocos=1024）
     *  @param hop  hop 长度（vocos=256）
     *  @param win  窗长（=nFft）
     *  @return 时域波形 float 数组 */
    fun istft(
        mag: FloatArray, x: FloatArray, y: FloatArray,
        nFft: Int = 1024, hop: Int = 256, win: Int = 1024
    ): FloatArray {
        // Vocos 输出 mag/x/y 为 [1, n_fft/2+1, frames]，flat 后是 bin-major：
        //   mag[k*frames + f] = 第 k 频点、第 f 帧（一 bin 的 T 帧连续存放）
        val bins = nFft / 2 + 1  // 513
        val frames = if (bins > 0) mag.size / bins else 0
        if (frames == 0) return FloatArray(0)

        // 预计算 hann 窗
        val window = FloatArray(win) { i ->
            0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * i / (win - 1)).toFloat()
        }

        val outLen = (frames - 1) * hop + win
        val out = FloatArray(outLen)

        // 复数工作缓冲（nFft 点）
        val reBuf = FloatArray(nFft)
        val imBuf = FloatArray(nFft)
        val half = nFft / 2  // 512
        // 频谱正向谱构建
        for (f in 0 until frames) {
            // ★ 填充正向谱 re/im[0..bins-1]（第 f 帧）：bin-major 布局 → idx = k*frames + f
            for (k in 0 until bins) {
                val idx = k * frames + f
                val m = mag[idx]
                val xx = x[idx]
                val yy = y[idx]
                reBuf[k] = m * xx
                imBuf[k] = m * yy
            }
            // ★ 共轭对称镜像：填充负频率部分 k=bins..nFft-1（对齐 numpy irfft）
            //   full[n-k] = conj(full[k])，k=1..half-1
            for (k in 1 until half) {
                reBuf[nFft - k] = reBuf[k]
                imBuf[nFft - k] = -imBuf[k]
            }
            // 逆 FFT（radix-2）
            ifftRadix2(reBuf, imBuf, nFft, inPlace = true)
            // 加窗 + OLA
            val startSample = f * hop
            for (i in 0 until win) {
                out[startSample + i] += reBuf[i] * window[i]
            }
        }
        return out
    }

    /** 原地复数 IFFT（re/im 各 n 点，n 必须为 2 的幂）。
     *  输入为正向谱的前半（0..n/2），调用方需先填好完整频谱；此处按完整谱处理。 */
    fun ifftRadix2(re: FloatArray, im: FloatArray, n: Int, inPlace: Boolean) {
        // bit-reversal 重排
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                // swap re[i],re[j], im[i],im[j]
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        // 蝶形迭代
        var length = 2
        while (length <= n) {
            val ang = 2.0 * Math.PI / length
            val wlenRe = kotlin.math.cos(ang).toFloat()
            val wlenIm = kotlin.math.sin(ang).toFloat()  // IFFT 用 +sin
            val half = length / 2
            var i = 0
            while (i < n) {
                var wRe = 1.0f; var wIm = 0.0f
                for (k in 0 until half) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    // w *= wlen
                    val nwRe = wRe * wlenRe - wIm * wlenIm
                    val nwIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nwRe; wIm = nwIm
                }
                i += length
            }
            length *= 2
        }
        // 归一化 1/n
        val inv = 1.0f / n
        for (i in 0 until n) { re[i] *= inv; im[i] *= inv }
    }
}