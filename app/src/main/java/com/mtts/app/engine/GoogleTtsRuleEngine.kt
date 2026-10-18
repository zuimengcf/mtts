package com.mtts.app.engine

import com.mtts.app.util.MttsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Google TTS 规则引擎 —— 纯 HTTP GET 合成 MP3，无需登录。
 *
 * 复刻 Python httptts 层 gtts.yaml 规则：
 *   origin: https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=zh-CN&q=<js>...
 */
class GoogleTtsRuleEngine : TtsEngine {

    override val code = "gtts"
    override val name = "Google TTS"
    override val category = EngineCategory.RULE
    override val status = EngineStatus.READY

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val voices = listOf(
        Voice("zh-CN", "中文", "zh-CN", "Female"),
        Voice("en-US", "English", "en-US", "Male"),
        Voice("ja-JP", "日本語", "ja-JP", "Female"),
        Voice("ko-KR", "한국어", "ko-KR", "Female")
    )

    override suspend fun listVoices(): List<Voice> = voices

    override suspend fun synthesize(
        text: String, voice: String,
        speed: Float, volume: Float, pitch: Float
    ): ByteArray {
        val lang = voice.ifBlank { "zh-CN" }
        val encoded = java.net.URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.google.com/translate_tts" +
            "?ie=UTF-8&client=tw-ob&tl=$lang&q=$encoded"

        MttsLog.http("Gtts", "GET", url)

        val req = Request.Builder().url(url)
            .addHeader("User-Agent", "Mozilla/5.0 Chrome/125.0.0.0")
            .addHeader("Referer", "https://translate.google.com/")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                MttsLog.w("Gtts", "http ${resp.code}")
                throw RuntimeException("GoogleTTS: HTTP ${resp.code}")
            }
            val data = resp.body?.bytes() ?: throw RuntimeException("GoogleTTS: empty body")
            MttsLog.d("Gtts", "synthesize ok", mapOf("bytes" to data.size))
            return data
        }
    }

    override fun synthesizeStream(text: String, voice: String): Flow<AudioEvent> = flow {
        try {
            val data = synthesize(text, voice)
            if (data.isNotEmpty()) emit(AudioEvent.Chunk(data))
            emit(AudioEvent.TurnEnd)
        } catch (e: Exception) {
            emit(AudioEvent.Error(e.message ?: "合成失败"))
        }
    }.flowOn(Dispatchers.IO)
}