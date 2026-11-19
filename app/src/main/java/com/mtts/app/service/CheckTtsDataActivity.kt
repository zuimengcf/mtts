package com.mtts.app.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** 向 Android TTS 框架报告当前音色缓存里真实存在的 locale。 */
class CheckTtsDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val availableVoices = TtsVoiceInventory.actualLocaleData()
        val resultData = Intent().apply {
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableVoices)
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
        }
        val resultCode = if (availableVoices.isEmpty()) {
            TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL
        } else {
            TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
        }
        setResult(resultCode, resultData)
        finish()
    }
}
