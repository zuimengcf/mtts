package com.baidu.tts.jni

/** 百度 TTS 错误信息类（复刻 MultiTTS ETtsError.smali） */
class ETtsError {
    private var ret: Int = 0
    private var message: String? = null

    fun getRet(): Int = ret
    fun getMessage(): String? = message

    override fun toString(): String = "ETtsError{ret=$ret, message=$message}"
}