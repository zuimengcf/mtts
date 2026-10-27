package com.mtts.app.util

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build

/**
 * 音效管理 —— 长音频 BGM 循环混音 + 短音效触发。
 *
 * 设计：
 *  - BGM：用户从设备选择的音频文件（mp3/ogg/wav...），解码为 24kHz/16bit/单声道 PCM 并缓存，
 *    在 TtsService PCM 输出层循环混入，音量独立可调。
 *  - 音效：短音频文件，文本预处理阶段按标记 ⟨sfx:名字⟩ 触发，也支持设置页试听。
 *    音效在标记位置与语音 PCM 混合。
 *
 * 解码：MediaExtractor + MediaCodec，输出重采样到 24000Hz/16bit/单声道（与 TTS 输出一致）。
 * 全部结果缓存：同一文件不重复解码。
 */
object SoundEffectManager {

    private const val TAG = "SoundFx"
    const val TARGET_RATE = 24000

    /** BGM 当前选择（文件路径），空 = 未启用 */
    @Volatile var bgmPath: String = ""
    /** BGM 音量 0f..1f */
    @Volatile var bgmVolume: Float = 0.3f
    /** 音效音量 0f..1f */
    @Volatile var sfxVolume: Float = 0.8f
    /** 音效总开关 */
    @Volatile var sfxEnabled: Boolean = true
    /** BGM 总开关 */
    @Volatile var bgmEnabled: Boolean = false

    // ---------------- 解码缓存 ----------------

    private val bgmCache = java.util.concurrent.ConcurrentHashMap<String, ShortArray>()
    private val sfxCache = java.util.concurrent.ConcurrentHashMap<String, ShortArray>()

    /** 音效注册表：标记名 → 文件路径（由 AppPrefs 加载） */
    @Volatile var sfxMap: Map<String, String> = emptyMap()

    /** BGM 最大解码时长（秒）：只取前 60 秒循环使用。
     *  内存上界：60s × 24kHz × 2B ≈ 2.9MB（源采样率上拼接最高 ~5.8MB@48kHz，可接受）。
     *  注意截断必须按「源采样率 × 秒数」换算，写死 24000×60 会在 48kHz 源上只截出 30 秒。 */
    private const val MAX_BGM_SECONDS = 60

    /**
     * 解码音频文件到 24kHz/16bit/单声道 PCM。
     * 支持 mp3 / ogg / wav / m4a / flac 等系统 MediaCodec 可解格式。
     * @param maxDurationSec 解码时长上限（秒，0 = 不限制）；BGM 传 60，按源采样率换算后截断，
     *        保证无论源是 44.1k/48k 都能完整解码出一分钟
     */
    fun decodeToPcm24k(ctx: Context, path: String, maxDurationSec: Int = 0): ShortArray? {
        if (path.isBlank()) return null
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(path)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIdx = i
                    format = f
                    break
                }
            }
            if (trackIdx < 0 || format == null) {
                extractor.release()
                return null
            }
            extractor.selectTrack(trackIdx)
            val srcMime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // ★ 上限按源采样率换算（60s × 48kHz = 2,880,000 样本），重采样回 24k 后仍是完整 60 秒
            val maxSrcSamples = if (maxDurationSec > 0) srcRate * maxDurationSec else 0
            val codec = MediaCodec.createDecoderByType(srcMime)
            codec.configure(format, null, null, 0)
            codec.start()

            // 逐块累积到可变缓冲（避免 ArrayList<Short> 每元素装箱 + addAll 拷贝的双重开销）：
            //   之前用 ArrayList<Short> + mono.toList()，一首 4 分钟的歌在装箱阶段就 OOM。
            val chunks = ArrayList<ShortArray>(64)
            var totalSamples = 0
            var earlyEof = false
            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var timeout = 10_000L

            while (!sawOutputEOS && timeout > 0) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(timeout)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, timeout)
                when {
                    outIdx >= 0 -> {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        // PCM16 交错 → 混单声道
                        val shorts = ob.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = info.size / 2 / srcChannels
                        var mono = ShortArray(0)
                        if (frames > 0) {
                            mono = ShortArray(frames)
                            var sIdx = 0
                            for (f in 0 until frames) {
                                var acc = 0
                                for (c in 0 until srcChannels) {
                                    acc += shorts.get(sIdx + f * srcChannels + c)
                                }
                                mono[f] = (acc / srcChannels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            }
                        }
                        // 达到上限：截断并提前结束解码（上限已在源采样率域换算，60s 语义不随采样率漂移）
                        if (maxSrcSamples > 0 && totalSamples + mono.size >= maxSrcSamples) {
                            chunks.add(mono.copyOf(maxSrcSamples - totalSamples))
                            totalSamples = maxSrcSamples
                            earlyEof = true
                            sawOutputEOS = true
                        } else {
                            if (mono.isNotEmpty()) chunks.add(mono)
                            totalSamples += mono.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (!earlyEof && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> timeout -= 100
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                }
            }
            try { codec.stop() } catch (_: Throwable) {}
            try { codec.release() } catch (_: Throwable) {}
            extractor.release()

            // 拼接 chunks（重采样前在源采样率上拼接，体量最小）
            var pcm = ShortArray(totalSamples)
            var off = 0
            for (c in chunks) { System.arraycopy(c, 0, pcm, off, c.size); off += c.size }
            chunks.clear()

            // 重采样到 24kHz
            if (srcRate != TARGET_RATE && pcm.isNotEmpty()) {
                val bytes = ByteArray(pcm.size * 2)
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().put(pcm)
                val resampled = PcmResampler.resample16BitMono(bytes, srcRate, TARGET_RATE)
                val rb = java.nio.ByteBuffer.wrap(resampled).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val rs = ShortArray(resampled.size / 2)
                rb.asShortBuffer().get(rs)
                pcm = rs
            }
            MttsLog.i(TAG, "decoded $path → ${pcm.size} samples ($srcRate Hz → $TARGET_RATE Hz)")
            return pcm
        } catch (t: Throwable) {
            MttsLog.e(TAG, "decode failed $path: ${t.message}")
            return null
        }
    }

    /** 获取（并缓存）BGM PCM。失败返回 null。
     *  ★ 整首解码（不限时长）；若长音频导致 OOM，自动回退为只解码前 60 秒 */
    fun getBgmPcm(ctx: Context, path: String): ShortArray? {
        if (path.isBlank()) return null
        val decoded = bgmCache.getOrPut(path) {
            try {
                decodeToPcm24k(ctx, path) ?: ShortArray(0)
            } catch (t: Throwable) {
                MttsLog.e(TAG, "bgm full decode failed, fallback to 60s: ${t.message}")
                decodeToPcm24k(ctx, path, MAX_BGM_SECONDS) ?: ShortArray(0)
            }
        }
        return decoded.takeIf { it.isNotEmpty() }
    }

    /** 获取（并缓存）音效 PCM。失败返回 null。 */
    fun getSfxPcm(ctx: Context, name: String): ShortArray? {
        val path = sfxMap[name] ?: return null
        return sfxCache.getOrPut(path) { decodeToPcm24k(ctx, path) ?: ShortArray(0) }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * 把 BGM 循环混入语音 PCM（都是 24kHz/16bit/单声道）。
     * @param startSample BGM 起始样本偏移（跨段连续推进），超出 bgm 长度则取模循环。
     */
    fun mixBgm(voice: ShortArray, bgm: ShortArray, startSample: Int = 0, bgmGain: Float = 0.3f): ShortArray {
        if (bgm.isEmpty()) return voice
        val out = ShortArray(voice.size)
        var b = ((startSample % bgm.size) + bgm.size) % bgm.size
        for (i in voice.indices) {
            if (b >= bgm.size) b = 0
            val mixed = voice[i] + (bgm[b] * bgmGain).toInt()
            out[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            b++
        }
        return out
    }

    /**
     * 在语音 PCM 的指定样本位置混入音效 PCM。
     * 音效超出语音长度则截断（不扩展输出长度，避免 TTS 时长不可控）。
     */
    fun mixSfx(voice: ShortArray, atSample: Int, sfx: ShortArray, sfxGain: Float): ShortArray {
        if (sfx.isEmpty()) return voice
        val out = voice.copyOf()
        var sIdx = 0
        var pos = atSample
        while (pos < out.size && sIdx < sfx.size) {
            val mixed = out[pos] + (sfx[sIdx] * sfxGain).toInt()
            out[pos] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pos++
            sIdx++
        }
        return out
    }

    /** 清空全部缓存（文件变更/内存压力时调用） */
    fun clearCache() {
        bgmCache.clear()
        sfxCache.clear()
    }

    /** 试听音效：用 MediaPlayer 播放文件（独立于 TTS 管线） */
    fun preview(ctx: Context, path: String, onDone: () -> Unit = {}): MediaPlayer? {
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.setOnCompletionListener {
                it.release()
                onDone()
            }
            mp.prepare()
            mp.setVolume(sfxVolume, sfxVolume)
            mp.start()
            mp
        } catch (t: Throwable) {
            MttsLog.e(TAG, "preview failed $path: ${t.message}")
            onDone()
            null
        }
    }
}
