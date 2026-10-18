package com.baidu.tts.jni

/**
 * 百度嵌入式 TTS 引擎统一接口 —— 三个变体类方法签名完全相同，
 * 仅类名不同（对应不同 .so 的 JNI 绑定符号）。
 */
interface IBaiduTtsEngine {

    fun setOnNewDataListener(listener: OnNewDataListener)

    // ---- 静态工具 ----
    fun bdTTSGetDatParam(path: String): String
    fun bdTTSGetEngineLibVersion(): String
    fun bdTTSGetEngineParam(): String
    fun bdTTSSetNativeLogLevel(level: Int): Int

    // ---- License 验证 ----
    fun bdTTSVerifyLicense(
        appCode: String, type: Int, appKey: String, appSecret: String,
        modelDir: String, licensePath: String, outBuf: ByteArray
    ): LicenseInfo

    fun bdTTSBindCore(path: String): Int

    // ---- 初始化/加载（path 字段均为 C 字符串字节：路径+NUL） ----
    fun bdTTSEngineInit(
        appid: String, textData: ByteArray, speechData: ByteArray,
        speechExtData: ByteArray?, extraData: ByteArray, authorize: String,
        handle: LongArray
    ): ETtsError

    fun loadSuitedEngine(
        appid: String, textData: ByteArray, speechData: ByteArray,
        speechExtData: ByteArray?, extraData: ByteArray, authorize: String,
        handle: Long
    ): ETtsError

    // ---- 域数据（BDETTSDOMAIN 域文件，如 <speaker>.res.dat）----
    fun bdTTSDomainDataInit(data: ByteArray, handle: Long): Int
    // ---- PGG/GAN 声码器初始化（tacotron 6082724 系：engineInit 成功、synthesis 前必须调用）----
    // 复刻 O1/b.a() cond_11：bdTTSEnginePggInit(appid, l(subganFilePath), handle)
    //   第一参=appid(String, map.get(modelCode))，第二参=subgan(voc 声码器)路径 C 字节，第三参=handle
    fun bdTTSEnginePggInit(pggAppid: String, subganData: ByteArray, handle: Long): Int

    // ---- 参数 ----
    fun bdTTSSetParam(handle: Long, param: Int, value: Long): Int
    fun bdTTSSetParamFloat(handle: Long, param: Int, value: Float): Int
    fun bdTTSSetParamString(handle: Long, param: String, value: String): Int
    fun bdTTSGetParam(handle: Long, param: Int): Long

    // ---- 合成 ----
    fun bdTTSSynthesis(handle: Long, text: ByteArray, len: Int, outLen: Int): ETtsError
    fun bdTTSEngineUninit(handle: Long): Int
}