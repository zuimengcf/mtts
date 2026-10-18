package com.mtts.app.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import com.mtts.app.util.MttsLog
import java.util.Locale

/**
 * 系统 TTS 调用链路诊断入口（仅调试用）。
 *
 * 以标准 App 的方式创建 TextToSpeech（默认引擎=mtts），
 * 实测系统对 mtts 引擎的语言识别、音色枚举与真实合成，
 * 结果写入 logcat（tag mtts/Probe）并显示在页面，以确认"其他 App 能否调用 mtts"。
 */
class TtsProbeActivity : Activity(), TextToSpeech.OnInitListener {

    private val TAG = "Probe"
    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 简单诊断 UI：把测试结果显示在页面，避免"白屏"误判为卡死
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
        }
        val title = TextView(this).apply {
            text = "MTTS 系统 TTS 诊断"
            textSize = 20f
        }
        logView = TextView(this).apply {
            textSize = 14f
            text = "正在初始化系统 TTS…"
        }
        root.addView(title)
        root.addView(logView)
        setContentView(root)

        MttsLog.i(TAG, "probe start, creating TextToSpeech (default engine)")
        Log.i("mtts/$TAG", "creating TextToSpeech default engine")
        // 不指定 engine，使用系统默认引擎（当前为 com.mtts.app）
        tts = TextToSpeech(this, this)
    }

    private fun logResult(msg: String) {
        MttsLog.i(TAG, "RESULT $msg")
        mainHandler.post { logView.append("\n$msg") }
    }

    override fun onInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            MttsLog.e(TAG, "onInit failed status=$status")
            logResult("onInit: FAILED status=$status")
            return
        }
        MttsLog.i(TAG, "onInit SUCCESS")
        logResult("onInit SUCCESS")

        // 语言可用性
        val langCode = t.setLanguage(Locale.CHINESE)
        MttsLog.i(TAG, "setLanguage(zh) -> $langCode (0=lang_available)")
        logResult("setLanguage(zh)=$langCode (LANG_AVAILABLE=0)")

        // 音色列表
        val voices = t.voices
        MttsLog.i(TAG, "getVoices count=${voices?.size}")
        logResult("getVoices count=${voices?.size}")
        voices?.take(5)?.forEach { v ->
            MttsLog.i(TAG, "  voice=${v.name} locale=${v.locale} net=${v.isNetworkConnectionRequired}")
        }

        // 尝试合成（引擎 mtts 离线合成）
        val utteranceId = "probe_utt"
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                MttsLog.i(TAG, "speak onStart $utteranceId")
            }
            override fun onDone(utteranceId: String?) {
                MttsLog.i(TAG, "speak onDone $utteranceId")
                logResult("speak: onDone")
            }
            override fun onError(utteranceId: String?) {
                MttsLog.i(TAG, "speak onError $utteranceId")
                logResult("speak: onError")
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?, errorCode: Int) {
                MttsLog.i(TAG, "speak onError code=$errorCode")
                logResult("speak: onError code=$errorCode")
            }
        })
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        val speakCode = t.speak("系统语音合成调用测试，你好世界", TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        MttsLog.i(TAG, "speak() -> $speakCode (SUCCESS=0)")
        logResult("speak()=$speakCode (SUCCESS=0)")

        mainHandler.postDelayed({ t.shutdown() }, 20000L)
    }

    override fun onDestroy() {
        try { tts?.shutdown() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
