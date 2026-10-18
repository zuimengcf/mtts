package com.baidu.tts.jni

/**
 * 百度嵌入式 TTS 引擎 JNI 桥 —— 绑定 libbd_etts.so（模型 code 6082724）。
 *
 * 命名式 JNI：类名 `com.baidu.tts.jni.EmbeddedSynthesizerEngine` + 本类 override external
 * 方法名，组合为 `Java_com_baidu_tts_jni_EmbeddedSynthesizerEngine_<方法名>`，
 * 与 .so 导出符号精确匹配（方法名不得加后缀）。
 *
 * 由 OfflineBaiduEngine 先把 .so 复制到内部 filesDir/lib 并 System.load。
 */
class EmbeddedSynthesizerEngine : IBaiduTtsEngine {

    private var listener: OnNewDataListener? = null

    override fun setOnNewDataListener(listener: OnNewDataListener) {
        this.listener = listener
    }

    /** native 音频回调（native 线程 → Java） */
    fun newAudioDataCallback(data: ByteArray, len: Int): Int =
        listener?.onNewData(data, len) ?: 0

    // ============ 与 libbd_etts.so 导出符号精确匹配的 override external 方法 ============
    override external fun bdTTSGetDatParam(path: String): String
    override external fun bdTTSGetEngineLibVersion(): String
    override external fun bdTTSGetEngineParam(): String
    override external fun bdTTSSetNativeLogLevel(level: Int): Int
    override external fun bdTTSVerifyLicense(
        appCode: String, type: Int, appKey: String, appSecret: String,
        modelDir: String, licensePath: String, outBuf: ByteArray
    ): LicenseInfo
    override external fun bdTTSBindCore(path: String): Int
    override external fun bdTTSEngineInit(
        appid: String, textData: ByteArray, speechData: ByteArray,
        speechExtData: ByteArray?, extraData: ByteArray, authorize: String,
        handle: LongArray
    ): ETtsError
    override external fun loadSuitedEngine(
        appid: String, textData: ByteArray, speechData: ByteArray,
        speechExtData: ByteArray?, extraData: ByteArray, authorize: String,
        handle: Long
    ): ETtsError
    override external fun bdTTSDomainDataInit(data: ByteArray, handle: Long): Int
    override external fun bdTTSEnginePggInit(pggAppid: String, subganData: ByteArray, handle: Long): Int
    override external fun bdTTSSetParam(handle: Long, param: Int, value: Long): Int
    override external fun bdTTSSetParamFloat(handle: Long, param: Int, value: Float): Int
    override external fun bdTTSSetParamString(handle: Long, param: String, value: String): Int
    override external fun bdTTSGetParam(handle: Long, param: Int): Long
    override external fun bdTTSSynthesis(handle: Long, text: ByteArray, len: Int, outLen: Int): ETtsError
    override external fun bdTTSEngineUninit(handle: Long): Int
}
