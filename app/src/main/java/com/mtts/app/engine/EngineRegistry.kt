package com.mtts.app.engine

import com.mtts.app.util.MttsLog
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 引擎注册表 —— 管理与提供所有引擎实例。
 *
 * 多引擎架构：
 *  - 内置引擎（微软嵌入式）随 App 常驻注册。
 *  - 外部语音包引擎（搜狗等，不内置 .so/资源）通过扫描 voice/ 下已导入目录动态注册。
 *  - [factory] 是"引擎 code → 引擎实例"的工厂，新增引擎在此登记即可被导入检测识别。
 *
 * 侧边栏从 [all] 获取分组后的引擎列表。
 */
object EngineRegistry {

    private val engines: MutableMap<String, TtsEngine> = LinkedHashMap()
    private val _registeredEngines = MutableStateFlow<List<TtsEngine>>(emptyList())
    /** 注册、覆盖、移除或目录重扫后的快照，供页面订阅而非缓存启动时的列表。 */
    val registeredEngines: StateFlow<List<TtsEngine>> = _registeredEngines.asStateFlow()

    /**
     * 引擎工厂：code → 引擎实例。
     * 扩展第三方引擎时，在此登记 code 与构造逻辑即可。
     * 内置引擎（microsoft）不依赖外部语音包，始终可用。
     */
    private val factory: Map<String, () -> TtsEngine> = mapOf(
        "microsoft" to { OfflineMicrosoftEngine() },
        "sougou" to { OfflineSougouEngine() },
        "bdetts" to { OfflineBaiduEngine() },
        "sherpa" to { SherpaOnnxEngine() }
    )

    /** 注册一个引擎（同名覆盖） */
    fun register(engine: TtsEngine): TtsEngine {
        engines[engine.code] = engine
        _registeredEngines.value = all()
        return engine
    }

    fun get(code: String): TtsEngine? = engines[code]

    fun all(): List<TtsEngine> = engines.values.toList()

    /** 按类别分组，供左侧边栏展示 */
    fun grouped(): Map<EngineCategory, List<TtsEngine>> =
        engines.values.groupBy { it.category }

    fun availableCodes(): List<String> = engines.keys.toList()

    /** 预注册所有内置引擎 */
    fun init() {
        // 内置微软嵌入式离线引擎（官方 SDK + 本地模型，离线可商用托底）常驻。
        register(OfflineMicrosoftEngine())
        // 动态检测已导入的外部引擎（不内置 .so/资源，仅在导入后出现）
        refreshImported()
    }

    /**
     * 扫描 voice 根目录下已导入的引擎目录，动态注册对应引擎。
     *
     * 判定规则：
     *  - 内置引擎（microsoft）：始终注册。
     *  - 外部引擎（sougou 等）：voice/<code> 目录存在即注册；否则移除。
     *
     * 调用时机：App 启动、语音包导入/删除之后（由 ViewModel.refreshEngines 触发）。
     */
    fun refreshImported() {
        val root = VoicePackageManager.root ?: return

        // 内置引擎恒在
        if (!engines.containsKey("microsoft")) {
            register(factory["microsoft"]?.invoke() ?: OfflineMicrosoftEngine())
        }

        // 外部引擎：按 voice/ 顶层子目录 + 工厂匹配动态增删
        for ((code, create) in factory) {
            if (code == "microsoft") continue   // 内置已在上面处理
            val dir = File(root, code)
            if (dir.isDirectory) {
                if (!engines.containsKey(code)) {
                    engines[code] = create()
                    MttsLog.d("EngineReg", "$code engine registered (voice/$code)")
                }
            } else {
                if (engines.remove(code) != null) {
                    MttsLog.d("EngineReg", "$code engine removed (voice/$code deleted)")
                }
            }
        }
        // ★ 引擎集合可能已变化 → 清空音色列表缓存（下次请求重新枚举）
        com.mtts.app.util.TtsVoicesCache.clear()
        _registeredEngines.value = all()
    }

    /** 移除一个已动态注册的引擎（语音包被删除时） */
    fun unregister(code: String): Boolean {
        val removed = engines.remove(code) != null
        if (removed) _registeredEngines.value = all()
        return removed
    }
}
