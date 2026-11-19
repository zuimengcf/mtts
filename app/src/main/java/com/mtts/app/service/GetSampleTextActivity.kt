package com.mtts.app.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * Completes Android Settings' GET_SAMPLE_TEXT request.
 *
 * Leaving EXTRA_SAMPLE_TEXT unset lets Settings choose its own sample sentence for
 * the engine's current locale, while still delivering the activity result that
 * Settings needs before it can call TextToSpeech.speak().
 *
 * ★ 试听标志位：系统试听链路 GET_SAMPLE_TEXT → speak()，外部 App 朗读永远不触发 GET_SAMPLE_TEXT。
 *   在此处设标志位 + 时间戳，TtsService.onSynthesizeText 检查标志位精确区分试听 vs 外部朗读，
 *   试听完成立即停 BGM（系统试听后不回调 onStop()，否则 BGM 会延迟到 onDestroy 才停）。
 */
class GetSampleTextActivity : Activity() {

    companion object {
        @Volatile
        @JvmStatic
        var previewPending = false
            private set

        @Volatile
        @JvmStatic
        var previewTimestamp = 0L
            private set

        /** 标记一次系统试听开始（GET_SAMPLE_TEXT 触发后系统很快调 speak()） */
        fun markPreview() {
            previewPending = true
            previewTimestamp = android.os.SystemClock.elapsedRealtime()
        }

        /** 清除试听标志（试听合成完成后调用） */
        fun clearPreview() {
            previewPending = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        markPreview()
        setResult(TextToSpeech.LANG_AVAILABLE, Intent())
        finish()
    }
}
