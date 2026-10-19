package com.mtts.app.engine

import android.content.Context
import android.net.Uri
import com.mtts.app.util.MttsLog
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
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
 *   - 先在与发布目录同一文件系统的临时目录中完整解压，再发布
 *
 * 发布不是跨多个目录的全局原子操作；但发布过程失败时会尽力回滚本次已经发布的
 * 顶层目录/文件，并保留原安装。进程崩溃恢复不由此类保证。
 */
object VoiceImportManager {
    private val importLock = Any()

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
     * 是“引擎级元数据”，为避免不同引擎相互覆盖，把它们归属到对应引擎目录：
     *   voice/<engineCode>/config.yaml、voice/<engineCode>/engines.yaml
     * （引擎 code 从 zip 内 engines.yaml 的 engines[].code 解析）。
     * 其余资源（模型/acoustic/dict/lib/avatar 等）照常解压到 voice 根。
     *
     * @return 成功解压并发布的文件数
     * @throws IllegalArgumentException 非法路径（Zip Slip）
     */
    fun importZip(context: Context, uri: Uri): Int = synchronized(importLock) {
        val root = importRoot(context).absoluteFile
        MttsLog.d("VoiceImp", "importZip", mapOf("uri" to uri.toString()))

        // 预扫描 zip 内 engines.yaml，确定本语音包归属的引擎 code。
        // 平铺微软/百度等旧包仍保留原有的元数据归位和根目录兼容语义。
        val engineCode = findEngineCode(context, uri)
        val temp = createTempDirectory(root)
        var count = 0
        var preserveTemp = false
        try {
            count = extractToTemp(context, uri, temp, engineCode)
            if (count == 0) throw IllegalArgumentException("语音包为空（不含文件）")
            publishWholeTopLevelEntries(root, temp)
            MttsLog.d("VoiceImp", "importZip done", mapOf("files" to count, "engineCode" to engineCode))
            count
        } catch (t: Throwable) {
            // publishWholeTopLevelEntries 已经在失败时回滚；这里负责清掉未发布的暂存内容。
            preserveTemp = (t as? PublishException)?.preserveBackup == true
            MttsLog.w("VoiceImp", "importZip failed: ${t.message}")
            throw t
        } finally {
            // 回滚恢复失败时，备份是唯一可恢复的旧包，必须连同其目录保留。
            if (!preserveTemp && temp.exists()) temp.deleteRecursively()
        }
    }

    private fun createTempDirectory(root: File): File {
        // root 的父目录与 root 同文件系统，避免 rename 发布时跨设备失败。
        val parent = root.parentFile ?: throw IllegalStateException("语音包根目录无父目录")
        parent.mkdirs()
        repeat(10) {
            val candidate = File(parent, ".voice-import-${UUID.randomUUID()}")
            if (candidate.mkdir()) return candidate
        }
        throw IllegalStateException("无法创建语音包暂存目录")
    }

    private fun extractToTemp(context: Context, uri: Uri, temp: File, engineCode: String?): Int {
        var count = 0
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取语音包")
        input.use {
            ZipInputStream(it.buffered()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry!!.name
                    if (name.isBlank()) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    if (name.startsWith("/") || name.startsWith("\\") || name.contains('\\')) {
                        throw IllegalArgumentException("非法语音包路径: $name")
                    }
                    val parts = name.split('/')
                    if (parts.any { it == "." || it == ".." || it == ".backups" }) {
                        throw IllegalArgumentException("非法或保留语音包路径: $name")
                    }
                    val clean = name
                    val relocated = if (engineCode != null && !clean.contains('/')) {
                        when (clean) {
                            "config.yaml", "engines.yaml" -> "$engineCode/$clean"
                            else -> null
                        }
                    } else null
                    val destRel = relocated ?: clean
                    if (destRel.isBlank() || destRel.split('/').any { it == ".backups" }) {
                        throw IllegalArgumentException("非法或保留语音包路径: $name")
                    }
                    val target = File(temp, destRel).normalize()
                    val tempPath = temp.absolutePath
                    if (target.absolutePath != tempPath &&
                        !target.absolutePath.startsWith(tempPath + File.separator)
                    ) {
                        throw IllegalArgumentException("非法语音包路径: $name")
                    }
                    if (entry!!.isDirectory) {
                        if (!target.mkdirs() && !target.isDirectory) {
                            throw IllegalStateException("无法创建语音包目录: $name")
                        }
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
        return count
    }

    /**
     * 按暂存目录的顶层条目整包替换（非增量合并）：每个顶层目录/文件整体替换，
     * 不把新旧同名目录内容混合。每个条目先备份旧值，再以同文件系统 rename 发布；
     * 任一步失败都删除本次新值并恢复已备份值。
     */
    private fun publishWholeTopLevelEntries(root: File, temp: File) {
        val entries = temp.listFiles()?.sortedBy { it.name }
            ?: throw IllegalStateException("无法读取语音包暂存目录")
        if (entries.any { it.name == ".backups" }) {
            throw IllegalArgumentException("语音包包含保留路径: .backups")
        }
        if (entries.isEmpty()) throw IllegalArgumentException("语音包为空（不含文件）")
        val backupDir = File(temp, ".backups")
        val states = mutableListOf<PublishedEntry>()
        try {
            for (staged in entries) {
                val destination = File(root, staged.name)
                val backup = File(backupDir, staged.name)
                val state = PublishedEntry(destination, backup, staged, false, false)
                states += state
                backupDir.mkdirs()
                val hadOld = destination.exists() || destination.isSymbolicLinkCompat()
                state.hadOld = hadOld
                if (hadOld) {
                    if (!destination.renameTo(backup)) {
                        throw IllegalStateException("无法备份原语音包: ${destination.absolutePath}")
                    }
                    state.backedUp = true
                }
                if (!staged.renameTo(destination)) {
                    throw IllegalStateException("无法发布语音包: ${destination.absolutePath}")
                }
                state.published = true
            }
        } catch (t: Throwable) {
            val rollbackErrors = mutableListOf<String>()
            for (item in states.asReversed()) {
                if (item.published && item.destination.exists() && !item.destination.deleteRecursively()) {
                    rollbackErrors += "删除新值失败: ${item.destination.absolutePath}"
                }
            }
            for (item in states.asReversed()) {
                if (item.backedUp) {
                    if (!item.backup.exists() || !item.backup.renameTo(item.destination)) {
                        rollbackErrors += "恢复旧值失败，备份: ${item.backup.absolutePath}"
                    }
                }
            }
            if (rollbackErrors.isNotEmpty()) {
                throw PublishException(
                    "发布失败，回滚失败；请保留备份目录 ${backupDir.absolutePath}：${rollbackErrors.joinToString()}",
                    true,
                    t
                )
            }
            if (backupDir.exists() && !backupDir.deleteRecursively()) {
                throw PublishException("发布失败且无法清理备份目录: ${backupDir.absolutePath}", true, t)
            }
            throw t
        }
        // 已全部发布成功。备份清理可能只完成一部分，不能再据此回滚新包。
        // 清理失败仅告警，外层 finally 会再次尝试删除暂存目录。
        if (backupDir.exists() && !backupDir.deleteRecursively()) {
            MttsLog.w("VoiceImp", "无法清理旧包备份: ${backupDir.absolutePath}")
        }
    }

    private class PublishException(message: String, val preserveBackup: Boolean, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    private data class PublishedEntry(
        val destination: File,
        val backup: File,
        val staged: File,
        var hadOld: Boolean,
        var backedUp: Boolean,
        var published: Boolean = false
    )

    // File.isSymbolicLink 在部分 Android API 级别不可用；导入根本身不应含链接，
    // 这里只把链接视作已有目标，避免覆盖时留下不可预期内容。
    private fun File.isSymbolicLinkCompat(): Boolean = try {
        java.nio.file.Files.isSymbolicLink(toPath())
    } catch (_: Throwable) {
        false
    }

    /** 预扫描 zip 内的 engines.yaml，解析其 engines[].code（首个）作为引擎归属。 */
    private fun findEngineCode(context: Context, uri: Uri): String? {
        var code: String? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null && code == null) {
                        if (!entry!!.isDirectory && entry!!.name.removePrefix("/").endsWith("engines.yaml")) {
                            code = VoicePackageConfig.parseEngineCodesFromString(
                                zis.readBytes().toString(Charsets.UTF_8)
                            ).firstOrNull()
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
    fun deletePack(context: Context, name: String): Boolean = synchronized(importLock) {
        val root = importRoot(context)
        val target = File(root, name)
        if (!target.isDirectory) return false
        val ok = target.deleteRecursively()
        MttsLog.d("VoiceImp", "deletePack", mapOf("name" to name, "ok" to ok))
        return ok
    }
}
