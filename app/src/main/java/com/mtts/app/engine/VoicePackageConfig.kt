package com.mtts.app.engine

import com.mtts.app.util.MttsLog
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * MultiTTS 语音包 config.yaml / engines.yaml 解析器。
 *
 * 语音包为 MultiTTS 标准格式：
 *   ├── engines.yaml       引擎声明（code/name/type）
 *   └── config.yaml        引擎 -> [Speaker] 音色列表
 *       microsoft:
 *       - !!org.nobody.multitts.tts.speaker.Speaker
 *         code: zh-CN-YunxiNeural
 *         name: 云希
 *         locale: zh-CN
 *         gender: 1
 *         param: Microsoft Yunxi (Natural) - Chinese (Simplified, China)
 *         type: 0
 *
 * SnakeYAML 默认会对 `!!` 全局标签尝试加载对应 Java 类（app 内不存在），
 * 故解析前先剥离多行标量意外的 `!!org.nobody...` 标签行，再按 Map/List 解析。
 */
object VoicePackageConfig {

    private val TAG = "VoiceCfg"

    /**
     * 解析语音包 config.yaml，返回该引擎的所有音色。
     * @param root 语音包根目录（含 config.yaml）
     * @param engineCode 要读取的引擎 code（如 "microsoft"）
     */
    fun parseSpeakers(configFile: File, engineCode: String): List<Voice> {
        if (!configFile.isFile) {
            MttsLog.d(TAG, "no config.yaml, engine=$engineCode")
            return emptyList()
        }
        return try {
            val clean = stripTags(configFile.readText())
            val rootMap = Yaml().load<Any?>(clean)
            val engineMap = readMap(rootMap) ?: run {
                MttsLog.w(TAG, "config.yaml 顶层非 Map 或为空")
                return emptyList()
            }
            val speakersRaw = engineMap[engineCode] ?: run {
                MttsLog.d(TAG, "config.yaml 无引擎段: $engineCode (keys=${engineMap.keys})")
                return emptyList()
            }
            val speakerList = when (speakersRaw) {
                is List<*> -> speakersRaw
                else -> listOf(speakersRaw)
            }
            val voices = speakerList.mapNotNull { sp ->
                val map = readMap(sp) ?: return@mapNotNull null
                val code = str(map["code"]).ifBlank { return@mapNotNull null }
                val gender = genderLabel(map["gender"])
                Voice(
                    shortName = code,
                    name = str(map["name"]).ifBlank { code },
                    locale = str(map["locale"]),
                    gender = gender,
                    param = str(map["param"]),
                    avatar = str(map["avatar"]),
                    desc = str(map["desc"]),
                    sampleRate = map["sampleRate"]?.let { str(it).toIntOrNull() } ?: 0,
                    speed = map["speed"]?.let { str(it).toFloatOrNull() } ?: 1.0f,
                    volume = map["volume"]?.let { str(it).toFloatOrNull() } ?: 1.0f,
                    // ★ MultiTTS config 的 pitch 是「倍率」语义（1.0=中性原声，2.0=两倍音调）；
                    //   本工程参数体系用「偏移」语义（0=基准，-1..1，UI 25=基准）。
                    //   解析时统一转换：internal = multiplier - 1.0（1.0 → 0 = UI 25 基准），
                    //   否则默认可显示成 50、且合成时 SSML pitch=100% 导致声音变怪。
                    pitch = map["pitch"]?.let { str(it).toFloatOrNull()?.minus(1.0f) } ?: 0.0f
                )
            }
            MttsLog.d(TAG, "parseSpeakers engine=$engineCode count=${voices.size}")
            voices
        } catch (e: Exception) {
            MttsLog.e(TAG, "parseSpeakers failed: ${configFile.path}", e)
            emptyList()
        }
    }

    /**
     * 解析语音包 engines.yaml，返回引擎 code 列表。
     */
    fun parseEngineCodes(enginesFile: File): List<String> {
        if (!enginesFile.isFile) return emptyList()
        return try {
            val clean = stripTags(enginesFile.readText())
            val root = Yaml().load<Any?>(clean)
            val map = readMap(root) ?: return emptyList()
            val enginesRaw = map["engines"]
            val engines = when (enginesRaw) {
                is List<*> -> enginesRaw
                else -> emptyList<Any?>()
            }
            val codes = engines.mapNotNull { readMap(it)?.let { m -> str(m["code"]) } }
                .filter { it.isNotBlank() }
            MttsLog.d(TAG, "parseEngineCodes count=${codes.size}")
            codes
        } catch (e: Exception) {
            MttsLog.e(TAG, "parseEngineCodes failed", e)
            emptyList()
        }
    }

    /**
     * 解析语音包 engines.yaml（字符串形式），返回引擎 code 列表。
     * 供 zip 预扫描使用（不必落盘到文件）。
     */
    fun parseEngineCodesFromString(content: String): List<String> {
        if (content.isBlank()) return emptyList()
        return try {
            val clean = stripTags(content)
            val root = Yaml().load<Any?>(clean)
            val map = readMap(root) ?: return emptyList()
            val enginesRaw = map["engines"]
            val engines = when (enginesRaw) {
                is List<*> -> enginesRaw
                else -> emptyList<Any?>()
            }
            engines.mapNotNull { readMap(it)?.let { m -> str(m["code"]) } }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            MttsLog.e(TAG, "parseEngineCodesFromString failed", e)
            emptyList()
        }
    }

    // ---------- helpers ----------

    /** 剥离 `!!org.nobody...` 全局标签（可能在行首或 `- ` 序列项后），仅去掉标签，不影响其余内容 */
    private fun stripTags(raw: String): String {
        val fixed = Regex("!!org\\.nobody[\\.\\w]*")
        return raw.lines().joinToString("\n") { line ->
            if (line.contains("!!org.nobody")) line.replace(fixed, "").trimEnd() else line
        }
    }

    private fun readMap(v: Any?): Map<Any?, Any?>? =
        if (v is Map<*, *>) v.entries.associate { (k, value) -> k to value } else null

    private fun str(v: Any?): String = when (v) {
        is String -> v
        is Number -> v.toString()
        else -> v?.toString() ?: ""
    }

    private fun genderLabel(v: Any?): String = when (v) {
        is Number -> if (v.toInt() == 1) "Male" else "Female"
        is String -> when (v) {
            "1", "Male", "male" -> "Male"
            "0", "Female", "female" -> "Female"
            else -> "Female"
        }
        else -> "Female"
    }
}