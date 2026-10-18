package com.mtts.app.engine

import kotlinx.coroutines.flow.Flow

/** 引擎类别 */
enum class EngineCategory(val label: String) {
    ONLINE("在线引擎"),
    RULE("规则引擎"),
    OFFLINE("离线语音包")
}

/** 引擎状态 */
enum class EngineStatus(val label: String) {
    READY("已就绪"),
    NEEDS_COOKIE("需Cookie"),
    NEEDS_CONFIG("需配置"),
    STALE("不可用")
}

/** 统一语音数据结构 */
data class Voice(
    val shortName: String,
    val name: String = "",
    val locale: String = "",
    val gender: String = "",
    val param: String = "",
    val avatar: String = "",     // 头像文件名（voice/<engine>/avatar/<avatar>）
    val desc: String = "",       // 音色描述
    val sampleRate: Int = 0,
    val speed: Float = 1.0f,
    val volume: Float = 1.0f,
    val pitch: Float = 0.0f
)

/** 统一引擎接口 —— 复刻 Python BaseEngine 契约 */
interface TtsEngine {
    val code: String
    val name: String
    val category: EngineCategory
    val status: EngineStatus

    /** 拉取语音列表 */
    suspend fun listVoices(): List<Voice>

    /** 文本 → 音频字节 */
    suspend fun synthesize(
        text: String,
        voice: String,
        speed: Float = 1.0f,
        volume: Float = 1.0f,
        pitch: Float = 0.0f
    ): ByteArray

    /** 流式合成（可播放） */
    fun synthesizeStream(text: String, voice: String): Flow<AudioEvent>
}

/** 流式音频事件 */
sealed class AudioEvent {
    data class Chunk(val data: ByteArray) : AudioEvent()
    object TurnEnd : AudioEvent()
    data class Error(val message: String) : AudioEvent()
}
