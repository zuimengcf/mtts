package com.mtts.app.util

import com.mtts.app.engine.TtsEngine
import com.mtts.app.engine.Voice
import kotlinx.coroutines.runBlocking

/**
 * 引擎音色列表内存缓存。
 *
 * 背景：日志实证，外部阅读器连续朗读时，每次 TTS 请求都会对全部 4 个引擎
 * 重新 parseSpeakers + listVoices（解析 config.yaml / 扫描目录，单次可达几百 ms~1s）。
 * 框架线程被拖住 → 外部 App speak() 超时 → 整套重发 → 队列积压 → 听感"循环读"。
 *
 * 这里按 (引擎类名:code) 缓存音色列表；语音包导入/删除后由 EngineRegistry 主动 clear()。
 */
object TtsVoicesCache {

    @Volatile
    private var map: Map<String, List<Voice>> = HashMap()

    private fun key(e: TtsEngine): String =
        "${e.javaClass.simpleName}:${e.code}"

    /** 取引擎音色列表（已缓存直接返回；否则同步初始化一次） */
    fun get(e: TtsEngine): List<Voice> {
        val k = key(e)
        map[k]?.let { return it }
        val vs = runCatching { runBlocking { e.listVoices() } }.getOrDefault(emptyList())
        synchronized(this) {
            map = map + (k to vs)
        }
        return vs
    }

    /** 语音包/引擎变动后清空全部缓存（EngineRegistry.refreshImported 末尾调用） */
    @Synchronized
    fun clear() {
        map = HashMap()
    }
}