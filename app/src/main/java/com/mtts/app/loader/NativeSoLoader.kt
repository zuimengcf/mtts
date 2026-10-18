package com.mtts.app.loader

import android.content.Context
import android.util.Log
import com.mtts.app.util.MttsLog
import java.io.File

/**
 * 离线引擎 .so 动态加载器 —— 不内置任何 SDK。
 *
 * App 自身不打包厂商 .so，运行时从【外部导入目录】扫描 SDK，
 * 复制到私有 nativeLibraryDir 后按依赖顺序 System.load 加载。
 *
 * 目录约定：
 *  - 外部导入根：/sdcard/TTSVoice  (可通过 [setExternalRoot] 覆盖)
 *  - 语音包结构：<外部根>/<engineCode>/lib/arm64-v8a 目录下的 .so 库 + 资源...
 *
 * 加载原理：
 *  Android 原生库必须在可执行内存映射的位置加载，且多数 SDK 的 JNI 桥
 *  依赖"类名/包名匹配 so 导出的 Java_* 符号"。因此：
 *   1) 把外部 .so 复制到 app 私有 nativeLibraryDir（或 filesDir/lib）
 *   2) 按依赖顺序 System.load(绝对路径)
 *   3) 引擎桥类（如 com.sogou.speech.proxy.NativeProxy）已内置，so 就绪后绑定
 */
object NativeSoLoader {

    private var appContext: Context? = null

    /** 外部导入根目录（可被用户导入流程改写） */
    @Volatile
    var externalRoot: File? = null

    /** 引擎 so 应加载到的私有目录 */
    val privateLibDir: File?
        get() = appContext?.let { File(it.filesDir, "lib") }

    /** 已加载的 so（防止重复加载） */
    private val loaded = mutableSetOf<String>()

    fun init(context: Context) {
        appContext = context.applicationContext
        // 导入根 = 应用外部私有目录（Android/data/com.mtts.app/files/voice），
        // 与 VoicePackageManager.root（getExternalFilesDir）完全一致，保证导入/读取同源。
        if (externalRoot == null) {
            externalRoot = File(appContext!!.getExternalFilesDir(null), "voice")
        }
    }

    /** 重置（外部根变化时调用） */
    fun reset() {
        synchronized(loaded) { loaded.clear() }
    }

    /**
     * 扫描一个引擎包，把其 lib 下的 arm64-v8a 目录中的 .so 库复制到私有目录。
     * @param engineDir 外部语音包引擎目录（如 /sdcard/TTSVoice/sougou）
     * @return 复制到私有目录的 .so 绝对路径列表
     */
    fun stageSoFiles(engineDir: File): List<File> {
        val staged = ArrayList<File>()
        val libDir = privateLibDir ?: return staged
        libDir.mkdirs()
        // 兼容多种布局：<engine>/lib/arm64-v8a、<engine>/libs/arm64-v8a、<engine>/arm64-v8a、<engine> 下的 .so 库
        val candidates = listOf(
            File(engineDir, "lib/arm64-v8a"),
            File(engineDir, "libs/arm64-v8a"),
            File(engineDir, "arm64-v8a"),
            engineDir,
        )
        val soFiles = LinkedHashMap<String, File>() // name -> src
        for (dir in candidates) {
            if (!dir.isDirectory) continue
            dir.listFiles { f -> f.extension == "so" }?.forEach { f ->
                soFiles[f.name] = f
            }
        }
        for ((name, src) in soFiles) {
            try {
                val dst = File(libDir, name)
                src.copyTo(dst, overwrite = true)
                staged.add(dst)
                MttsLog.d("SoLoader", "staged $name -> $dst")
            } catch (t: Throwable) {
                MttsLog.w("SoLoader", "stage $name failed: ${t.message}")
            }
        }
        return staged
    }

    /**
     * 按依赖顺序加载一组 .so。
     * 依赖顺序由 [order] 提供；未列出的库按 [fallbackOrder]（文件名）排在后面。
     * 已在 [engineDir] 目录下加载过的库（同 libomp 等共享运行时）会跳过。
     *
     * @param soPaths 待加载的绝对路径（由 [stageSoFiles] 返回）
     * @param order   指定加载优先顺序的关键名（如 listOf("omp","sgproxy_lib","sgsplit","sgtts","sgtts_lib")）
     */
    fun load(soPaths: List<File>, order: List<String>): Map<String, Boolean> {
        val result = LinkedHashMap<String, Boolean>()
        if (soPaths.isEmpty()) return result
        // 记录 lib<name> 与 name 两种键，便于按顺序匹配
        fun keyOf(name: String) = when {
            name.startsWith("lib") -> name.substring(3).removeSuffix(".so")
            else -> name.removeSuffix(".so")
        }
        // 按 order 顺序优先
        val ordered = ArrayList<File>()
        val rest = ArrayList<File>()
        for (path in soPaths) {
            val key = keyOf(path.name)
            if (order.contains(key)) ordered.add(path) else rest.add(path)
        }
        ordered.sortBy { order.indexOf(keyOf(it.name)) }
        val all = ordered + rest
        for (path in all) {
            val name = path.name
            val key = keyOf(name)
            var alreadyLoaded: Boolean
            synchronized(loaded) {
                alreadyLoaded = loaded.contains(key)
            }
            if (alreadyLoaded) continue
            try {
                System.load(path.absolutePath)
                synchronized(loaded) { loaded.add(key) }
                result[name] = true
                MttsLog.d("SoLoader", "loaded $name")
            } catch (t: Throwable) {
                result[name] = false
                MttsLog.w("SoLoader", "load $name failed: ${t.message}")
            }
        }
        return result
    }

    /** 便捷：复制 + 加载一步完成 */
    fun stageAndLoad(engineDir: File, order: List<String>): Map<String, Boolean> {
        val staged = stageSoFiles(engineDir)
        return load(staged, order)
    }
}
