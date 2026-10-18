package com.mtts.app.engine

import android.content.Context
import android.net.Uri
import com.mtts.app.util.MttsLog
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 语音包导入器 —— 通过 SAF 选择的 zip 压缩包解压到应用外部私有目录
 * （Android/data/com.mtts.app/files/voice，即 [VoicePackageManager.root]）。
 *
 * 解压目标结构（与引擎约定一致）：
 *   voice/microsoft/<voiceShortName>/...   微软嵌入式语音模型
 *   voice/sougou/lib/arm64-v8a/...         搜狗等外部引擎 .so + 资源
 *
 * 安全：
 *   - 仅使用正规安卓路径（getExternalFilesDir），不触碰 /sdcard
 *   - 校验 zip 条目路径，防 Zip Slip（路径穿越）
 */
object VoiceImportManager {

    /** 解压根目录：Android/data/<pkg>/files/voice */
    fun importRoot(context: Context): File {
        val root = VoicePackageManager.root
            ?: File(context.getExternalFilesDir(null), "voice")
        root.mkdirs()
        return root
    }

    /**
     * 把一个 zip 语音包解压到 voice 根目录。
     *
     * 多引擎语音包共用一个 voice 根。zip 内的顶层 config.yaml / engines.yaml
     * 是"引擎级元数据"，为避免不同引擎相互覆盖，把它们归属到对应引擎目录：
     *   voice/<engineCode>/config.yaml、voice/<engineCode>/engines.yaml
     * （引擎 code 从 zip 内 engines.yaml 的 engines[].code 解析）。
     * 其余资源（模型/acoustic/dict/lib/avatar 等）照常解压到 voice 根。
     *
     * @param context 应用上下文
     * @param uri 通过 SAF OpenDocument 选择的 zip 的 content:// URI
     * @return 成功解压的文件数
     * @throws IllegalArgumentException 非法路径（Zip Slip）
     */
    fun importZip(context: Context, uri: Uri): Int {
        val root = importRoot(context)
        MttsLog.d("VoiceImp", "importZip", mapOf("uri" to uri.toString()))

        // 预扫描 zip 内 engines.yaml，确定本语音包归属的引擎 code
        val engineCode = findEngineCode(context, uri)

        var count = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.isBlank()) { entry = zis.nextEntry; continue }
                    val clean = name.removePrefix("/")

                    // 顶层元数据文件归属到引擎目录
                    val relocated: String? = if (engineCode != null && !clean.contains('/')) {
                        when (clean) {
                            "config.yaml", "engines.yaml" -> "$engineCode/$clean"
                            else -> null
                        }
                    } else null
                    val destRel = relocated ?: clean

                    // Zip Slip 防护：规范化后仍须位于 root 内
                    val target = File(root, destRel).normalize()
                    if (!target.absolutePath.startsWith(root.absolutePath + File.separator)) {
                        throw IllegalArgumentException("非法语音包路径: $name")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> zis.copyTo(out) }
                        count++
                        MttsLog.d("VoiceImp", "  -> $destRel")
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        MttsLog.d("VoiceImp", "importZip done", mapOf("files" to count, "engineCode" to engineCode))
        return count
    }

    /**
     * 预扫描 zip 内的 engines.yaml，解析其 engines[].code（首个）作为引擎归属。
     * 无法解析时返回 null（元数据仍解压到 voice 根，兼容旧行为）。
     */
    private fun findEngineCode(context: Context, uri: Uri): String? {
        var code: String? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null && code == null) {
                        if (!entry.isDirectory && entry.name.removePrefix("/").endsWith("engines.yaml")) {
                            val content = zis.readBytes().toString(Charsets.UTF_8)
                            code = VoicePackageConfig.parseEngineCodesFromString(content).firstOrNull()
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            MttsLog.w("VoiceImp", "findEngineCode failed: ${e.message}")
        }
        return code
    }

    /** 已导入语音包信息 */
    data class PackInfo(
        val name: String,
        val path: String,
        val fileCount: Int,
        val sizeMB: String
    )

    /** 扫描 voice 根目录下已导入的引擎/语音包目录（顶层子目录名） */
    fun importedPacks(context: Context): List<PackInfo> {
        val root = importRoot(context)
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.map { dir ->
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            val sizeBytes = files.sumOf { it.length() }
            PackInfo(
                name = dir.name,
                path = dir.absolutePath,
                fileCount = files.size,
                sizeMB = String.format(java.util.Locale.ROOT, "%.1f", sizeBytes / 1024.0 / 1024.0)
            )
        }.sortedBy { it.name }
    }

    /** 删除一个已导入的语音包目录 */
    fun deletePack(context: Context, name: String): Boolean {
        val root = importRoot(context)
        val target = File(root, name)
        if (!target.isDirectory) return false
        val ok = target.deleteRecursively()
        MttsLog.d("VoiceImp", "deletePack", mapOf("name" to name, "ok" to ok))
        return ok
    }
}
