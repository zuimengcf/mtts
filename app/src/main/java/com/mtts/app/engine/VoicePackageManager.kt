package com.mtts.app.engine

import android.content.Context
import com.mtts.app.loader.NativeSoLoader
import com.mtts.app.util.MttsLog
import java.io.File

/**
 * 语音包资源管理器 —— 维护离线语音包在应用私有目录中的根路径，并支持外部导入。
 *
 * 目录约定：
 *  - 私有资源根：{filesDir}/voice/<engineCode>/...   (运行时解压/复制到此处)
 *  - 外部导入根：/sdcard/TTSVoice/<engineCode>/...  (用户导入，App 不内置)
 *
 * 加载流程：
 *  1. 扫描外部导入根（[importedEngines]）识别已导入的引擎
 *  2. 把该引擎的 .so 经 NativeSoLoader 复制到私有目录并动态加载
 *  3. 把该引擎的资源（dict/acoustic 等）复制到 {filesDir}/voice/<engineCode>
 *
 * App 默认不内置任何 .so 与语音资源；全部由外部导入目录提供。
 */
object VoicePackageManager {

    private var appContext: Context? = null

    /** 私有语音包根目录（{getExternalFilesDir(null)}/voice，即 Android/data 外部私有目录） */
    val root: File?
        get() = appContext?.getExternalFilesDir(null)?.let { File(it, "voice") }

    /** 外部导入根目录（与 NativeSoLoader 一致） */
    val externalRoot: File?
        get() = NativeSoLoader.externalRoot

    /** 初始化（在 Application.onCreate 调用） */
    fun init(context: Context) {
        appContext = context.applicationContext
        NativeSoLoader.init(context)
        root?.mkdirs()
    }

    /** 某引擎的私有资源目录：{root}/<engineCode> */
    fun engineDir(engineCode: String): File? =
        root?.let { File(it, engineCode) }

    /** 某引擎的外部导入目录：{externalRoot}/<engineCode> */
    fun externalEngineDir(engineCode: String): File? =
        externalRoot?.let { File(it, engineCode) }

    /** 扫描外部导入根，返回已导入的引擎 code 列表 */
    fun importedEngines(): List<String> =
        externalRoot?.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()

    /**
     * 把一个外部引擎包"暂存"到私有目录：
     *  1. NativeSoLoader 复制并加载该引擎的 .so
     *  2. 复制语音资源（dict/acoustic/...）到 {filesDir}/voice/<engineCode>
     * @return 暂存是否成功（.so 至少加载成功一个）
     */
    fun stageExternalEngine(engineCode: String, soOrder: List<String>): Boolean {
        val extDir = externalEngineDir(engineCode) ?: return false
        if (!extDir.isDirectory) return false
        val loadResult = NativeSoLoader.stageAndLoad(extDir, soOrder)
        val anyLoaded = loadResult.values.any { it }
        copyResources(extDir, engineCode)
        MttsLog.d("VoicePkg", "stage $engineCode so=$loadResult anyLoaded=$anyLoaded")
        return anyLoaded
    }

    /** 复制一个外部引擎目录下的非 .so 资源到私有 voice/<engineCode> */
    private fun copyResources(extDir: File, engineCode: String) {
        val dstRoot = engineDir(engineCode) ?: return
        dstRoot.mkdirs()
        val skipExt = setOf("so")
        extDir.walkTopDown().forEach { f ->
            if (f.isFile && f.extension !in skipExt) {
                val rel = f.relativeTo(extDir)
                val dst = File(dstRoot, rel.path)
                try {
                    dst.parentFile?.mkdirs()
                    f.copyTo(dst, overwrite = true)
                } catch (t: Throwable) {
                    MttsLog.w("VoicePkg", "copy ${rel.path} failed: ${t.message}")
                }
            }
        }
    }

    /**
     * 将 assets 内置的语音包解压到外部私有目录（首次启动执行一次）。
     * 目标 {getExternalFilesDir(null)}/voice —— 与引擎约定的外部根一致
     * （SherpaOnnxEngine/OfflineSougouEngine 等都先查 externalEngineDir）。
     * 模型不再内置（sherpa 模型通过模型下载页在线安装）；
     * 该函数仅解压仓库里仍保留在 assets 中的其它资源（若有）。
     *
     * 已存在的引擎目录跳过解压（避免重复 IO 与覆盖用户数据）。
     * assets 路径：assets/voice/<engine>/...
     */
    fun unpackAssets(context: Context, assetsRoot: String = "voice") {
        val ctx = context.applicationContext
        val outRoot = File(ctx.getExternalFilesDir(null), assetsRoot)
        outRoot.mkdirs()
        unpackDir(ctx, assetsRoot, outRoot, skipExisting = true)
    }

    private fun unpackDir(
        ctx: Context, assetDir: String, outDir: File, skipExisting: Boolean
    ) {
        try {
            val names = ctx.assets.list(assetDir) ?: return
            outDir.mkdirs()
            for (name in names) {
                val assetPath = if (assetDir.isEmpty()) name else "$assetDir/$name"
                val childOut = File(outDir, name)
                // 目录存在且非空 → 跳过（已解压过）
                if (skipExisting && childOut.exists() && childOut.listFiles()?.isNotEmpty() == true) continue
                try {
                    // 用 size 区分文件/目录：assets.open 成功则为文件
                    ctx.assets.open(assetPath).use { input ->
                        childOut.parentFile?.mkdirs()
                        childOut.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: java.io.IOException) {
                    // 文件不存在于 assets（目录情况），递归
                    unpackDir(ctx, assetPath, childOut, skipExisting)
                } catch (e: java.io.FileNotFoundException) {
                    unpackDir(ctx, assetPath, childOut, skipExisting)
                }
            }
        } catch (t: Throwable) {
            // 忽略
        }
    }
}
