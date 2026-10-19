package com.mtts.app.engine

import android.content.Context
import com.mtts.app.util.MttsLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * sherpa-onnx 模型下载管理器：
 *  Piper（chaowen / xiaoya）、Matcha、Kokoro、MeloTTS 均从
 *  k2-fsa/sherpa-onnx 官方 release 下载 tar.bz2，解压到 voice/sherpa/<id>/。
 *  不再内置任何模型（开源仓库只含代码，模型按需在线安装）。
 */
object SherpaModelDownload {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    data class RemoteFile(val url: String, val asIsName: String? = null)

    data class ModelItem(
        val id: String,
        val label: String,
        val desc: String,
        val family: String,
        val files: List<RemoteFile>,
        val verify: List<String>
    )

    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    /** 可下载模型目录（按 id 排序稳定） */
    val models: List<ModelItem> = listOf(
        ModelItem(
            id = "piper-chaowen",
            label = "Piper 超文（中文）",
            desc = "22050Hz 单音色 · 纯中文（超文 CC0 数据集）· 约 58MB",
            family = "vits",
            files = listOf(
                RemoteFile("$BASE/tts-models/vits-piper-zh_CN-chaowen-medium.tar.bz2")
            ),
            verify = listOf("zh_CN-chaowen-medium.onnx", "lexicon.txt", "tokens.txt")
        ),
        ModelItem(
            id = "piper-xiaoya",
            label = "Piper 小雅（中文）",
            desc = "22050Hz 单音色 · 纯中文（小雅 Non-commercial 数据集）· 约 58MB",
            family = "vits",
            files = listOf(
                RemoteFile("$BASE/tts-models/vits-piper-zh_CN-xiao_ya-medium.tar.bz2")
            ),
            verify = listOf("zh_CN-xiao_ya-medium.onnx", "lexicon.txt", "tokens.txt")
        ),
        ModelItem(
            id = "matcha-zh-baker",
            label = "Matcha 中文",
            desc = "22050Hz 单音色 · 声学 75MB + 声码器 54MB · 纯中文（英文 OOV 丢弃）",
            family = "matcha",
            files = listOf(
                RemoteFile("$BASE/tts-models/matcha-icefall-zh-baker.tar.bz2"),
                RemoteFile("$BASE/vocoder-models/vocos-22khz-univ.onnx", "vocos-22khz-univ.onnx")
            ),
            verify = listOf("model-steps-3.onnx", "vocos-22khz-univ.onnx", "tokens.txt")
        ),
        ModelItem(
            id = "kokoro-multi-lang-v1_1",
            label = "Kokoro v1.1（103 音色）",
            desc = "24000Hz · 中英多说话人 · int8 量化版",
            family = "kokoro",
            files = listOf(
                RemoteFile("$BASE/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2")
            ),
            verify = listOf("model.onnx", "voices.bin", "tokens.txt", "G_kokoro.json")
        ),
        ModelItem(
            id = "vits-melo-tts-zh_en",
            label = "MeloTTS 中英",
            desc = "44100Hz 单音色 · 中英混读 · MIT 许可",
            family = "vits",
            files = listOf(
                RemoteFile("$BASE/tts-models/vits-melo-tts-zh_en.tar.bz2")
            ),
            verify = listOf("lexicon.txt", "tokens.txt")
        )
    )

    /** Kokoro v1.1 103 说话人名册（sid 顺序，官方模型页核对） */
    private val kokoroSpeakersJson: String by lazy {
        val names = listOf(
            "af_maple", "af_sol", "bf_vale",
            "zf_001", "zf_002", "zf_003", "zf_004", "zf_005", "zf_006", "zf_007", "zf_008",
            "zf_017", "zf_018", "zf_019", "zf_021", "zf_022", "zf_023", "zf_024", "zf_026",
            "zf_027", "zf_028", "zf_032", "zf_036", "zf_038", "zf_039", "zf_040", "zf_042",
            "zf_043", "zf_044", "zf_046", "zf_047", "zf_048", "zf_049", "zf_051", "zf_059",
            "zf_060", "zf_067", "zf_070", "zf_071", "zf_072", "zf_073", "zf_074", "zf_075",
            "zf_076", "zf_077", "zf_078", "zf_079", "zf_083", "zf_084", "zf_085", "zf_086",
            "zf_087", "zf_088", "zf_090", "zf_092", "zf_093", "zf_094", "zf_099",
            "zm_009", "zm_010", "zm_011", "zm_012", "zm_013", "zm_014", "zm_015", "zm_016",
            "zm_020", "zm_025", "zm_029", "zm_030", "zm_031", "zm_033", "zm_034", "zm_035",
            "zm_037", "zm_041", "zm_045", "zm_050", "zm_052", "zm_053", "zm_054", "zm_055",
            "zm_056", "zm_057", "zm_058", "zm_061", "zm_062", "zm_063", "zm_064", "zm_065",
            "zm_066", "zm_068", "zm_069", "zm_080", "zm_081", "zm_082", "zm_089", "zm_091",
            "zm_095", "zm_096", "zm_097", "zm_098", "zm_100"
        )
        check(names.size == 103) { "kokoro speakers must be 103, got ${names.size}" }
        val sb = StringBuilder("{\"speakers\":[")
        names.forEachIndexed { i, n ->
            if (i > 0) sb.append(',')
            sb.append('"').append(n).append('"')
        }
        sb.append("]}")
        sb.toString()
    }

    /** 目标模型目录：voice/sherpa/<id>/ */
    fun modelDir(ctx: Context, item: ModelItem): File {
        val root = VoicePackageManager.externalEngineDir("sherpa")
            ?: File(ctx.getExternalFilesDir(null), "voice/sherpa")
        return File(root, item.id)
    }

    /** 是否已完整安装（关键文件齐全） */
    fun isInstalled(ctx: Context, item: ModelItem): Boolean {
        val dir = modelDir(ctx, item)
        return item.verify.all { File(dir, it).isFile }
    }

    /** 已安装模型体积（MB） */
    fun installedSizeMB(ctx: Context, item: ModelItem): String {
        val dir = modelDir(ctx, item)
        val bytes = if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        return String.format(java.util.Locale.ROOT, "%.0f MB", bytes / 1024.0 / 1024.0)
    }

    /** 卸载（删除整个模型目录） */
    fun uninstall(ctx: Context, item: ModelItem): Boolean =
        modelDir(ctx, item).deleteRecursively()

    /** 下载并安装模型到 voice/sherpa/<id>/；成功后返回提示文本 */
    fun install(ctx: Context, item: ModelItem, onProgress: (Float) -> Unit): String {
        val dir = modelDir(ctx, item)
        dir.mkdirs()
        val cacheDir = File(ctx.cacheDir, "sherpa_dl").apply { mkdirs() }

        var done = 0f
        val step = 1f / item.files.size
        for (rf in item.files) {
            val name = rf.url.substringAfterLast('/')
            val tmp = File(cacheDir, "$name.part")
            download(rf.url, tmp) { p -> onProgress(done + p * step) }
            if (rf.asIsName != null) {
                File(dir, rf.asIsName).also {
                    it.parentFile?.mkdirs()
                    tmp.copyTo(it, overwrite = true)
                }
            } else {
                extractTarBz2(tmp, dir)
            }
            tmp.delete()
            done += step
        }

        // Kokoro：官方包无说话人名册 → 自动生成 G_kokoro.json
        if (item.family == "kokoro" && !File(dir, "G_kokoro.json").isFile) {
            File(dir, "G_kokoro.json").writeText(kokoroSpeakersJson)
        }

        // 校验，失败回滚
        val missing = item.verify.filter { !File(dir, it).isFile }
        if (missing.isNotEmpty()) {
            MttsLog.d("SherpaDl", "verify failed, rollback model=${item.id} missing=${missing.joinToString()}")
            dir.deleteRecursively()
            throw RuntimeException("模型文件缺失，已回滚：${missing.joinToString()}")
        }
        MttsLog.d("SherpaDl", "install ok model=${item.id} dir=${dir.path}")
        return "安装完成：${item.label}"
    }

    /** OkHttp 流式下载到文件，回调 0..1 进度 */
    private fun download(url: String, to: File, onProgress: (Float) -> Unit) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} 下载失败")
            val total = resp.body?.contentLength() ?: -1L
            FileOutputStream(to).use { out ->
                val src = resp.body?.byteStream() ?: throw RuntimeException("响应体为空")
                val buf = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
    }

    /** 解压 tar.bz2 到目标目录，自动剥离共同顶层目录 */
    private fun extractTarBz2(tarFile: File, outDir: File) {
        val tmpDir = File(outDir.parentFile, "${outDir.name}.tmp").apply { deleteRecursively(); mkdirs() }
        try {
            var topLevel: String? = null
            val tmpCanonical = tmpDir.canonicalFile
            TarArchiveInputStream(BZip2CompressorInputStream(tarFile.inputStream().buffered())).use { tin ->
                var entry: TarArchiveEntry? = tin.nextTarEntry
                while (entry != null) {
                    val entryName = entry.name
                    require(entryName.isNotEmpty() && !entryName.contains('\\')) { "非法 TAR 路径：$entryName" }
                    require(!File(entryName).isAbsolute &&
                        !entryName.matches(Regex("^[A-Za-z]:/.*"))) { "非法 TAR 绝对路径：$entryName" }
                    val parts = entryName.split('/')
                    require(parts.none { it == ".." }) { "非法 TAR 路径穿越：$entryName" }
                    require(parts.drop(1).dropLastWhile { it.isEmpty() }.none { it.isEmpty() }) {
                        "非法 TAR 路径：$entryName"
                    }
                    require(!entry.isSymbolicLink && !entry.isLink) { "TAR 不允许链接条目：$entryName" }
                    require(entry.isDirectory || entry.isFile) { "不支持的 TAR 条目：$entryName" }

                    val top = parts.first()
                    if (topLevel == null) topLevel = top
                    require(top == topLevel) { "TAR 包含不同顶层目录：$entryName" }
                    val relParts = parts.drop(1).dropLastWhile { it.isEmpty() }
                    if (relParts.isNotEmpty()) {
                        val dest = File(tmpDir, relParts.joinToString(File.separator))
                        require(dest.canonicalFile.path == tmpCanonical.path ||
                            dest.canonicalFile.path.startsWith(tmpCanonical.path + File.separator)) {
                            "非法 TAR 目标路径：$entryName"
                        }
                        require(!Files.isSymbolicLink(dest.toPath())) { "TAR 目标不允许符号链接：$entryName" }
                        if (entry.isDirectory) dest.mkdirs()
                        else {
                            dest.parentFile?.mkdirs()
                            FileOutputStream(dest).use { out -> tin.copyTo(out) }
                        }
                    }
                    entry = tin.nextTarEntry
                }
            }
            // 搬到目标目录；递归复制，避免顶层目录的 copyTo 只复制目录元数据。
            val outCanonical = outDir.canonicalFile
            tmpDir.listFiles()?.forEach { copyTreeSafely(it, File(outDir, it.name), outCanonical) }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun copyTreeSafely(source: File, target: File, outCanonical: File) {
        require(!Files.isSymbolicLink(source.toPath())) { "不允许搬运符号链接：${source.path}" }
        require(!Files.isSymbolicLink(target.toPath())) { "不允许覆盖符号链接：${target.path}" }
        require(target.canonicalFile.path == outCanonical.path ||
            target.canonicalFile.path.startsWith(outCanonical.path + File.separator)) {
            "非法 TAR 目标路径：${target.path}"
        }
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { copyTreeSafely(it, File(target, it.name), outCanonical) }
        } else if (source.isFile) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        } else {
            throw IllegalArgumentException("不支持的 TAR 条目：${source.path}")
        }
    }
}