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

    // ★ 源卡死快速识别：connect 短（换源快），read 适中（兼顾慢速稳定源）
    private const val CONNECT_TIMEOUT_MS = 8000L
    private const val READ_TIMEOUT_MS = 60_000L
    // ★ 探测用超短超时：HEAD/断点探测几秒即可判定，别拖累切源
    private const val PROBE_TIMEOUT_MS = 8000L

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
    // ★ 探测客户端（downloadComplete / 源可达性探测），connect/read 都短，快速判定可用性
    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    data class RemoteFile(
        val url: String,               // GitHub release 地址（tar.bz2 或单文件）
        val asIsName: String? = null,  // 非空=直接落 name；空=按 tar.bz2 解压
        val hfUrl: String? = null      // HF 镜像直链（优先快速通道，失败回落 GitHub）
    )

    data class ModelItem(
        val id: String,
        val label: String,
        val desc: String,
        val family: String,
        val files: List<RemoteFile>,
        val verify: List<String>,
        // ★ HF 国内镜像逐文件下载：repo内路径 → 本地目标文件名（优先快源，失败回落 GitHub tar.bz2）
        val hfFiles: List<Pair<String, String>> = emptyList()
    )

    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    // ★ HF 国内镜像（hf-mirror.com，实测 206 断点续传 + ~1.7MB/s），优先快速通道
    private const val HF = "https://hf-mirror.com"

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
            verify = listOf("zh_CN-chaowen-medium.onnx", "lexicon.txt", "tokens.txt"),
            hfFiles = listOf(
                // piper 超文: csukuangfj2/vits-piper-zh_CN-chaowen-medium（HF 快源）
                "csukuangfj2/vits-piper-zh_CN-chaowen-medium/zh_CN-chaowen-medium.onnx" to "zh_CN-chaowen-medium.onnx",
                "csukuangfj2/vits-piper-zh_CN-chaowen-medium/zh_CN-chaowen-medium.onnx.json" to "zh_CN-chaowen-medium.onnx.json",
                "csukuangfj2/vits-piper-zh_CN-chaowen-medium/tokens.txt" to "tokens.txt",
                "csukuangfj2/vits-piper-zh_CN-chaowen-medium/lexicon.txt" to "lexicon.txt",
                "csukuangfj2/vits-piper-zh_CN-chaowen-medium/date.fst" to "date.fst",
                "csukuangfj2/vits-piper-zh_CN-chaowen-medium/number.fst" to "number.fst",
                "csukuangfj2/vits-piper-zh_CN-chaowen-medium/phone.fst" to "phone.fst"
            )
        ),
        ModelItem(
            id = "piper-xiaoya",
            label = "Piper 小雅（中文）",
            desc = "22050Hz 单音色 · 纯中文（小雅 Non-commercial 数据集）· 约 58MB",
            family = "vits",
            files = listOf(
                RemoteFile("$BASE/tts-models/vits-piper-zh_CN-xiao_ya-medium.tar.bz2")
            ),
            verify = listOf("zh_CN-xiao_ya-medium.onnx", "lexicon.txt", "tokens.txt"),
            hfFiles = listOf(
                // piper 小雅: csukuangfj2/vits-piper-zh_CN-xiao_ya-medium（HF 快源）
                "csukuangfj2/vits-piper-zh_CN-xiao_ya-medium/zh_CN-xiao_ya-medium.onnx" to "zh_CN-xiao_ya-medium.onnx",
                "csukuangfj2/vits-piper-zh_CN-xiao_ya-medium/zh_CN-xiao_ya-medium.onnx.json" to "zh_CN-xiao_ya-medium.onnx.json",
                "csukuangfj2/vits-piper-zh_CN-xiao_ya-medium/tokens.txt" to "tokens.txt",
                "csukuangfj2/vits-piper-zh_CN-xiao_ya-medium/lexicon.txt" to "lexicon.txt",
                "csukuangfj2/vits-piper-zh_CN-xiao_ya-medium/date.fst" to "date.fst",
                "csukuangfj2/vits-piper-zh_CN-xiao_ya-medium/number.fst" to "number.fst",
                "csukuangfj2/vits-piper-zh_CN-xiao_ya-medium/phone.fst" to "phone.fst"
            )
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
            verify = listOf("model-steps-3.onnx", "vocos-22khz-univ.onnx", "tokens.txt"),
            hfFiles = listOf(
                // matcha: csukuangfj/matcha-icefall-zh-baker
                "csukuangfj/matcha-icefall-zh-baker/model-steps-3.onnx" to "model-steps-3.onnx",
                "csukuangfj/matcha-icefall-zh-baker/tokens.txt" to "tokens.txt",
                "csukuangfj/matcha-icefall-zh-baker/lexicon.txt" to "lexicon.txt",
                // vocos 声码器: k2-fsa/sherpa-onnx-models
                "k2-fsa/sherpa-onnx-models/vocoder-models/vocos-22khz-univ.onnx" to "vocos-22khz-univ.onnx"
            )
        ),
        ModelItem(
            id = "kokoro-multi-lang-v1_1",
            label = "Kokoro v1.1（103 音色）",
            desc = "24000Hz · 中英多说话人 · int8 量化版",
            family = "kokoro",
            files = listOf(
                RemoteFile("$BASE/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2")
            ),
            verify = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "G_kokoro.json"),
            hfFiles = listOf(
                // kokoro: csukuangfj/kokoro-int8-multi-lang-v1_1
                "csukuangfj/kokoro-int8-multi-lang-v1_1/model.int8.onnx" to "model.int8.onnx",
                "csukuangfj/kokoro-int8-multi-lang-v1_1/voices.bin" to "voices.bin",
                "csukuangfj/kokoro-int8-multi-lang-v1_1/tokens.txt" to "tokens.txt",
                "csukuangfj/kokoro-int8-multi-lang-v1_1/lexicon-zh.txt" to "lexicon-zh.txt",
                "csukuangfj/kokoro-int8-multi-lang-v1_1/lexicon-us-en.txt" to "lexicon-us-en.txt"
            )
        ),
        ModelItem(
            id = "vits-melo-tts-zh_en",
            label = "MeloTTS 中英",
            desc = "44100Hz 单音色 · 中英混读 · MIT 许可",
            family = "vits",
            files = listOf(
                RemoteFile("$BASE/tts-models/vits-melo-tts-zh_en.tar.bz2")
            ),
            verify = listOf("lexicon.txt", "tokens.txt"),
            hfFiles = listOf(
                // melo-zh_en: csukuangfj/vits-melo-tts-zh_en
                "csukuangfj/vits-melo-tts-zh_en/model.int8.onnx" to "model.int8.onnx",
                "csukuangfj/vits-melo-tts-zh_en/tokens.txt" to "tokens.txt",
                "csukuangfj/vits-melo-tts-zh_en/lexicon.txt" to "lexicon.txt"
            )
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
        // ★ OOM 防护：下载前释放全部常驻模型（Kokoro/Matcha/Melo 的 ORT session + 词典 + voices），
        //   腾出堆空间给下载缓冲与 OkHttp HTTP/2 SegmentPool，避免叠爆堆导致 UNCAUGHT OutOfMemoryError。
        SherpaOnnxEngine.releaseAllModels()
        val dir = modelDir(ctx, item)
        dir.mkdirs()

        // ★ 全部模型已配置 HF 国内镜像逐文件下载（快源，实测 ~1.7MB/s + 206 断点续传）。
        //   已彻底移除 GitHub 下载回退：GitHub 及其所有代理镜像在本机网络均 DNS 解析失败
        //   （日志铁证 Unable to resolve host），反复探测只会白等超时并叠爆堆引发 OOM。
        //   若某模型未配置 hfFiles，直接抛清晰异常提示，不再静默回落死源。
        if (item.hfFiles.isEmpty()) {
            MttsLog.w("SherpaDl", "model ${item.id} has no HF mapping, cannot download")
            throw RuntimeException("${item.label} 未配置下载源（HF 仓库缺失）")
        }
        if (!installFromHf(ctx, item, dir, onProgress)) {
            throw RuntimeException("${item.label} 从 HF 镜像下载失败，请检查网络后重试")
        }
        return installFinish(ctx, item)
    }

    /** HF 逐文件下载到目标目录；全部成功返回 true，任一失败删除已下文件返回 false。 */
    private fun installFromHf(ctx: Context, item: ModelItem, dir: File, onProgress: (Float) -> Unit): Boolean {
        MttsLog.d("SherpaDl", "HF mirror download start: ${item.id} (${item.hfFiles.size} files)")
        val cacheDir = File(ctx.cacheDir, "sherpa_dl").apply { mkdirs() }
        val n = item.hfFiles.size
        var doneFiles = 0
        for ((repoPath, targetName) in item.hfFiles) {
            // repoPath 形如 "owner/repo/sub/file.onnx" → resolve 直链（首两段是 owner/repo）
            val segs = repoPath.split('/')
            val resolver = if (segs.size >= 3) {
                "${segs[0]}/${segs[1]}/resolve/main/${segs.drop(2).joinToString("/")}"
            } else repoPath
            val hfUrl = "$HF/$resolver"
            val localName = targetName.substringAfterLast('/')
            val tmp = File(cacheDir, "hf_$localName.part")
            try {
                var p0 = doneFiles.toFloat() / n
                // 已完整 → 直接复用
                if (tmp.isFile && tmp.length() > 0 && !downloadComplete(hfUrl, tmp)) {
                    MttsLog.d("SherpaDl", "HF resume ${tmp.length()}B: $localName")
                    download(hfUrl, tmp, append = true) { p ->
                        onProgress(p0 + p / n)
                    }
                } else if (!tmp.isFile || tmp.length() <= 0) {
                    MttsLog.d("SherpaDl", "HF fresh: $localName")
                    download(hfUrl, tmp, append = false) { p ->
                        onProgress(p0 + p / n)
                    }
                } else {
                    MttsLog.d("SherpaDl", "HF reuse: $localName (${tmp.length()}B)")
                    onProgress(p0 + 1f / n)
                }
                val dest = File(dir, targetName)
                dest.parentFile?.mkdirs()
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
                doneFiles++
                onProgress(doneFiles.toFloat() / n)
            } catch (t: Throwable) {
                MttsLog.w("SherpaDl", "HF download failed for $localName: ${t.message}")
                try { tmp.delete() } catch (_: Throwable) {}
                // 删除 HF 已下文件，回落 GitHub
                try { dir.deleteRecursively() } catch (_: Throwable) {}
                return false
            }
        }
        return true
    }

    /** 收尾：Kokoro 自动生成名册 + 校验缺失回滚。 */
    private fun installFinish(ctx: Context, item: ModelItem): String {
        val dir = modelDir(ctx, item)
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

    /** 判断 part 文件是否已下载完整（HEAD 请求对比 Content-Length）。遍历镜像源，任一源确认完整即复用。 */
    private fun downloadComplete(url: String, part: File): Boolean {
        for (cand in mirrorCandidates(url)) {
            try {
                val req = Request.Builder().url(cand).head().build()
                probeClient.newCall(req).execute().use { resp ->
                    val total = resp.header("Content-Length")?.toLongOrNull()
                    if (total != null && total > 0 && part.length() == total) return true
                }
            } catch (_: Throwable) {}
        }
        return false
    }

    /** 源健康快速探测：短超时 GET Range 试一个字节，确认该源可达（返回 2xx/206）才保留下载。
     *  ★ 避免一个个源串行等满超时才切，先一次性挑出可达源。 */
    private fun probeSource(url: String): Boolean {
        return try {
            val req = Request.Builder().url(url)
                .header("Range", "bytes=0-3")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                resp.isSuccessful  // 2xx/3xx(206) 都算可达
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 下载候选列表：仅返回原始 URL。
     *  ★ 已移除 GitHub 及所有加速代理：当前所有模型均走 HF 国内镜像（hf-mirror.com），
     *    GitHub 系在本机网络 DNS 解析失败（日志铁证），加候选只会白探死源并叠爆堆酿成 OOM。 */
    private fun mirrorCandidates(url: String): List<String> = listOf(url)

    /** OkHttp 流式下载到文件，回调 0..1 进度。遍历镜像候选源，任意源成功即完成。
     *  ★ 先并行探测各源可达性，只对可达源做真实下载，避免失效源拖满超时。
     *  ★ append=true 时支持断点续传：从已有 part 末尾继续，发 Range 头。 */
    private fun download(url: String, to: File, append: Boolean, onProgress: (Float) -> Unit) {
        val allCandidates = mirrorCandidates(url)
        // 一次性探测所有候选源可达性（官方源优先保留；探测只花几个短超时）
        val reachable = allCandidates.filter { probeSource(it) }
        MttsLog.d("SherpaDl", "sources reachable: ${reachable.size}/${allCandidates.size}")
        if (reachable.isEmpty()) {
            // 全不可达 → 仍按原候选串行尝试一次，避免探测误判漏掉可用源
            fallbackDownload(allCandidates, to, append, onProgress)
            return
        }
        fallbackDownload(reachable, to, append, onProgress)
    }

    /** 串行尝试候选源列表，失败自动切下一个；全部失败抛异常。 */
    private fun fallbackDownload(candidates: List<String>, to: File, append: Boolean, onProgress: (Float) -> Unit) {
        var lastErr: Throwable? = null
        for ((i, cand) in candidates.withIndex()) {
            if (i > 0) MttsLog.d("SherpaDl", "mirror fallback → $cand")
            try {
                downloadOnce(cand, to, append, onProgress)
                return
            } catch (t: Throwable) {
                lastErr = t
                MttsLog.w("SherpaDl", "download failed from $cand: ${t.message}")
            }
        }
        throw RuntimeException("所有下载源均失败: ${lastErr?.message ?: "未知错误"}")
    }

    /** 单源下载实现（含断点续传）；失败抛异常由上层切下一个镜像源。 */
    private fun downloadOnce(url: String, to: File, append: Boolean, onProgress: (Float) -> Unit) {
        // 渐进式真实的已下载基线（续传起始点）
        val baseLen = if (append) to.length().coerceAtLeast(0L) else 0L
        val attempt = Request.Builder().url(url)
            .apply { if (append && baseLen > 0) header("Range", "bytes=$baseLen-") }
            .build()
        client.newCall(attempt).execute().use { resp ->
            // 206 = 服务端接受续传；200 = 不支持 Range（覆盖全量重新下载）
            val rangeOk = resp.code == 206 && append && baseLen > 0
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} 下载失败")
            val total = resp.body?.contentLength() ?: -1L
            val offset = if (rangeOk) baseLen else 0L
            // 续传 → 追加写；服务端返回 200 全量 → 覆盖写
            val writeAppend = rangeOk
            FileOutputStream(to, writeAppend).use { out ->
                resp.body?.byteStream()?.use { src ->
                    val buf = ByteArray(64 * 1024)
                    var written = offset
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val denom = if (rangeOk && total > 0) baseLen + total else total
                        if (denom > 0) onProgress((written.toFloat() / denom).coerceIn(0f, 1f))
                    }
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