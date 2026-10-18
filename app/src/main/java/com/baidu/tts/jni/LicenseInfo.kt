package com.baidu.tts.jni

/**
 * 百度 TTS license 校验结果（复刻 LicenseInfo.smali）。
 * 字段名必须与 native 写入一致：ret/time/packageName/appDesc/appId。
 */
class LicenseInfo {
    var ret: Int = 0
    var time: String? = null
    var packageName: String? = null
    var appDesc: String? = null
    var appId: String? = null

    override fun toString(): String =
        "LicenseInfo{ret=$ret, time=$time, packageName=$packageName, appDesc=$appDesc, appId=$appId}"
}