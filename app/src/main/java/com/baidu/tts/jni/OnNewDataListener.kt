package com.baidu.tts.jni

/** 百度 TTS 新数据监听（native 音频回调 → Java，复刻 OnNewDataListener.smali） */
fun interface OnNewDataListener {
    /** native 合成产出音频块时回调 */
    fun onNewData(data: ByteArray, len: Int): Int
}