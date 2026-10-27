package com.mtts.app.util

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 全局设置导入导出 & 语音包导出
 *
 * 全局设置：除【语音包本体资源】外的所有 SharedPreferences + 替换规则。
 * 语音包导出：把某个已导入语音包目录打成 zip。
 */
object SettingsBackup {

    private const val PREFS_NAME = "mtts_settings"

    // ============== 全局设置：导出 ==============

    fun exportTo(uri: Uri): Result<String> {
        val ctx = com.mtts.app.MttsApplication.ctx() ?: return fail("上下文不可用")
        val text = buildBackupText()
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(text.toByteArray(Charsets.UTF_8))
            } ?: return fail("无法打开输出流")
            ok("导出成功")
        } catch (e: Exception) {
            fail("导出失败: ${e.message}")
        }
    }

    fun exportToFile(dest: File): Result<String> {
        val text = buildBackupText()
        return try {
            dest.parentFile?.mkdirs()
            dest.writeText(text)
            ok("已导出到 ${dest.absolutePath}")
        } catch (e: Exception) {
            fail("导出失败: ${e.message}")
        }
    }

    private fun buildBackupText(): String {
        val ctx = com.mtts.app.MttsApplication.ctx()
        val p = ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val root = JsonObject()
        root.addProperty("version", 1)
        root.addProperty("exportedAt", System.currentTimeMillis().toString())

        val prefsJson = JsonObject()
        val all: Map<String, Any?> = p?.all ?: emptyMap()
        all.keys.toList().sorted().forEach { key ->
            val value = JsonObject()
            when (val v = all[key]) {
                null -> {
                    value.addProperty("type", "string")
                    value.add("value", JsonNull.INSTANCE)
                }
                is String -> { value.addProperty("type", "string"); value.addProperty("value", v) }
                is Int -> { value.addProperty("type", "int"); value.addProperty("value", v) }
                is Long -> { value.addProperty("type", "long"); value.addProperty("value", v) }
                is Float -> { value.addProperty("type", "float"); value.addProperty("value", v) }
                is Double -> { value.addProperty("type", "float"); value.addProperty("value", v) }
                is Boolean -> { value.addProperty("type", "boolean"); value.addProperty("value", v) }
                is FloatArray -> { value.addProperty("type", "string"); value.addProperty("value", java.util.Arrays.toString(v)) }
                is Set<*> -> { value.addProperty("type", "string"); value.addProperty("value", v.toString()) }
                else -> { value.addProperty("type", "string"); value.addProperty("value", v.toString()) }
            }
            prefsJson.add(key, value)
        }
        root.add("prefs", prefsJson)

        // 音效注册表路径在总备份里做「跨设备友好」处理：注册表/关键词文本直接带上；
        // 音频文件本体由 importFromFile/importTo 的 zip 模式携带（见 files/soundfx 打包）。
        val sfxMapNames = JsonArray()
        val prefsNn = p
        if (prefsNn != null) {
            for ((k, v) in loadSfxMapList(prefsNn)) {
                val e = JsonObject()
                e.addProperty("name", k)
                e.addProperty("file", java.io.File(v).name)
                sfxMapNames.add(e)
            }
        }
        root.add("sfxRegistry", sfxMapNames)

        val replacesJson = com.google.gson.JsonArray()
        TextPreprocessor.loadReplacesFromVoice().forEach { r ->
            val item = JsonObject()
            item.addProperty("name", r.name)
            item.addProperty("source", r.source)
            item.addProperty("target", r.target)
            item.addProperty("regex", r.regex)
            item.addProperty("activate", r.activate)
            item.addProperty("group", r.group)
            item.addProperty("pauseMs", r.pauseMs)
            replacesJson.add(item)
        }
        root.add("replaces", replacesJson)
        return Gson().toJson(root) + "\n"
    }

    // ============== 全局设置：导入 ==============

    fun importFrom(uri: Uri): Result<String> {
        val ctx = com.mtts.app.MttsApplication.ctx() ?: return fail("上下文不可用")
        val text = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            return fail("读取失败: ${e.message}")
        } ?: return fail("无法打开输入流")
        return importFromText(text)
    }

    fun importFromText(text: String): Result<String> {
        val ctx = com.mtts.app.MttsApplication.ctx() ?: return fail("上下文不可用")
        val parsed = try { parseBackup(text) } catch (e: Exception) {
            return fail("导入失败: ${e.message ?: "JSON格式无效"}")
        }
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = p.edit()
        parsed.prefs.forEach { (key, typed) ->
            when (typed.first) {
                "string" -> editor.putString(key, typed.second as String)
                "int" -> editor.putInt(key, typed.second as Int)
                "long" -> editor.putLong(key, typed.second as Long)
                "float" -> editor.putFloat(key, typed.second as Float)
                "boolean" -> editor.putBoolean(key, typed.second as Boolean)
            }
        }
        // Parsing and validation are complete before either destination is written.
        if (!editor.commit()) return fail("设置写入失败")
        // 音效注册表：总备份里 prefs 的 sfx_map 是绝对路径，跨设备会失效；
        // 若备份带 sfxRegistry（标记名→文件名），按 files/soundfx/<文件名> 重定位存在的条目。
        var sfxSuffix = ""
        val reg = parsed.sfxRegistry
        if (reg.isNotEmpty()) {
            val mapText = p.getString("sfx_map", "") ?: ""
            val dir = File(ctx.filesDir, "soundfx")
            val out = StringBuilder()
            var fixed = 0
            for (line in mapText.lines()) {
                val t = line.trim(); if (t.isEmpty()) continue
                val s = t.split('|', limit = 2)
                if (s.size < 2) continue
                val path = s[1].trim()
                if (File(path).isFile) { out.append(t).append('\n') }
                else {
                    val f = File(dir, File(path).name)
                    if (f.isFile) { out.append(s[0]).append('|').append(f.absolutePath).append('\n'); fixed++ }
                }
            }
            if (fixed > 0) p.edit().putString("sfx_map", out.toString()).commit()
            SoundEffectManager.clearCache()
            sfxSuffix = "，音效路径重定位 $fixed 条"
        }
        if (parsed.replaces.isNotEmpty()) {
            val old = TextPreprocessor.loadReplacesFromVoice()
            val merged = old.filterNot { existing ->
                parsed.replaces.any { it.source == existing.source && it.regex == existing.regex }
            } + parsed.replaces
            try {
                TextPreprocessor.saveReplacesToVoice(merged)
            } catch (e: Exception) {
                return fail("规则写入失败: ${e.message}")
            }
            return ok("已导入 ${parsed.prefs.size} 项设置 + ${parsed.replaces.size} 条规则（合并去重）$sfxSuffix")
        }
        return ok("已导入 ${parsed.prefs.size} 项设置$sfxSuffix")
    }

    // ============== 语音包导出（每个 pack 单独 zip） ==============

    fun exportPack(path: String, uri: Uri): Result<String> {
        val ctx = com.mtts.app.MttsApplication.ctx() ?: return fail("上下文不可用")
        val root = File(path)
        if (!root.isDirectory) return fail("目录不存在: $path")
        val files = root.walkTopDown().filter { it.isFile }.toList()
        val baseName = root.name.ifBlank { "voicepack" }
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                ZipOutputStream(out.buffered()).use { zos ->
                    for (f in files) {
                        val rel = f.absolutePath.removePrefix(root.absolutePath).removePrefix(File.separator)
                        zos.putNextEntry(ZipEntry("$baseName/$rel"))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            } ?: return fail("无法打开输出流")
            ok("已导出 ${files.size} 个文件")
        } catch (e: Exception) {
            fail("导出失败: ${e.message}")
        }
    }

    fun exportPackToFile(path: String, dest: File): Result<String> {
        val root = File(path)
        if (!root.isDirectory) return fail("目录不存在: $path")
        val files = root.walkTopDown().filter { it.isFile }.toList()
        val baseName = root.name.ifBlank { "voicepack" }
        return try {
            dest.parentFile?.mkdirs()
            ZipOutputStream(dest.outputStream().buffered()).use { zos ->
                for (f in files) {
                    val rel = f.absolutePath.removePrefix(root.absolutePath).removePrefix(File.separator)
                    zos.putNextEntry(ZipEntry("$baseName/$rel"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            ok("已导出 ${files.size} 个文件到 ${dest.absolutePath}")
        } catch (e: Exception) {
            fail("导出失败: ${e.message}")
        }
    }

    // ============== 音效包导出/导入（zip：map + keywords + meta + 音频文件，可部分） ==============

    private const val SFX_ENTRY_MAP = "sfx_map.txt"
    private const val SFX_ENTRY_KW = "sfx_keywords.txt"
    private const val SFX_ENTRY_META = "sfx_meta.json"
    private const val SFX_FILES_DIR = "files/"
    private const val SFX_BGM_BASE = "__bgm__"

    /** 音效导出：注册表 + 关键词表 + 配置 + 全部音频文件打成一个 zip */
    fun exportSfxTo(uri: Uri): Result<String> {
        val ctx = com.mtts.app.MttsApplication.ctx() ?: return fail("上下文不可用")
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mapText = p.getString("sfx_map", "") ?: ""
        val kwText = p.getString("sfx_keywords", "") ?: ""
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                java.util.zip.ZipOutputStream(out.buffered()).use { zos ->
                    fun putText(name: String, content: String) {
                        zos.putNextEntry(ZipEntry(name))
                        zos.write(content.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                    putText(SFX_ENTRY_MAP, mapText)
                    putText(SFX_ENTRY_KW, kwText)
                    val meta = JsonObject()
                    meta.addProperty("version", 1)
                    meta.addProperty("sfxEnabled", p.getBoolean("sfx_sfx_enabled", true))
                    meta.addProperty("sfxVolume", p.getFloat("sfx_sfx_volume", 0.8f).toDouble())
                    meta.addProperty("bgmEnabled", p.getBoolean("sfx_bgm_enabled", false))
                    meta.addProperty("bgmVolume", p.getFloat("sfx_bgm_volume", 0.3f).toDouble())
                    val bgmPath = p.getString("sfx_bgm_path", "") ?: ""
                    if (bgmPath.isNotBlank()) {
                        val f = File(bgmPath)
                        if (f.isFile) {
                            val entry = "$SFX_FILES_DIR$SFX_BGM_BASE.${f.extension.ifBlank { "mp3" }}"
                            zos.putNextEntry(ZipEntry(entry))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                            meta.addProperty("bgmFile", entry)
                        }
                    }
                    // 音频文件（按注册表里的路径逐个入包）
                    for (line in mapText.lines()) {
                        val t = line.trim(); if (t.isEmpty()) continue
                        val path = t.split('|').getOrNull(1)?.trim() ?: continue
                        val f = File(path)
                        if (f.isFile) {
                            zos.putNextEntry(ZipEntry("$SFX_FILES_DIR${f.name}"))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    putText(SFX_ENTRY_META, Gson().toJson(meta))
                }
            } ?: return fail("无法打开输出流")
            ok("音效包导出成功")
        } catch (e: Exception) {
            fail("导出失败: ${e.message}")
        }
    }

    /** 音效导入：支持完整 zip（三件套+音频）或仅含部分条目的 zip，或裸 map/keywords 文本 */
    fun importSfxFrom(uri: Uri): Result<String> {
        val ctx = com.mtts.app.MttsApplication.ctx() ?: return fail("上下文不可用")
        val bytes = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            return fail("读取失败: ${e.message}")
        } ?: return fail("无法打开输入流")
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return if (isZip) importSfxZip(ctx, bytes)
        else importSfxTextFile(ctx, String(bytes, Charsets.UTF_8))
    }

    private fun importSfxZip(ctx: Context, bytes: ByteArray): Result<String> {
        var mapText = ""
        var kwText = ""
        var metaJson: JsonObject? = null
        val audio = LinkedHashMap<String, ByteArray>()
        try {
            java.util.zip.ZipInputStream(bytes.inputStream().buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    when {
                        e.name == SFX_ENTRY_MAP -> mapText = zis.readBytes().toString(Charsets.UTF_8)
                        e.name == SFX_ENTRY_KW -> kwText = zis.readBytes().toString(Charsets.UTF_8)
                        e.name == SFX_ENTRY_META -> metaJson = JsonParser.parseString(
                            zis.readBytes().toString(Charsets.UTF_8)).asJsonObject
                        e.name.startsWith(SFX_FILES_DIR) && !e.isDirectory ->
                            audio[e.name.removePrefix(SFX_FILES_DIR).substringAfterLast('/')] =
                                zis.readBytes()
                    }
                    e = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return fail("zip 解析失败: ${e.message}")
        }
        if (mapText.isBlank() && kwText.isBlank() && metaJson == null && audio.isEmpty()) {
            return fail("压缩包内没有音效数据")
        }
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dir = File(ctx.filesDir, "soundfx").apply { mkdirs() }
        val editor = p.edit()

        // 1) 音频落盘
        val written = HashMap<String, String>() // entryName -> absPath
        for ((name, data) in audio) {
            try {
                val safe = name.replace(Regex("[/\\\\]"), "_")
                val f = File(dir, safe)
                f.writeBytes(data)
                written[safe] = f.absolutePath
            } catch (_: Exception) {}
        }

        // 2) 注册表：路径优先用包内文件（同名 basename），包内没有且原路径存在则保留，否则丢弃该条
        var mapCount = 0
        var mapNames = loadSfxMapList(p).map { it.first }.toSet()
        if (mapText.isNotBlank()) {
            val old = loadSfxMapList(p)
            val out = LinkedHashMap<String, String>()
            for (line in mapText.lines()) {
                val t = line.trim(); if (t.isEmpty()) continue
                val parts = t.split('|', limit = 2)
                if (parts.size < 2) continue
                val name = parts[0].trim(); val path = parts[1].trim()
                if (name.isEmpty()) continue
                val base = File(path).name
                val newPath = written[base]
                    ?: (if (File(path).isFile) path else null)
                    ?: continue
                out[name] = newPath
            }
            if (out.isNotEmpty()) {
                // 保留包里没覆盖到的旧条目（合并语义：包内同名优先）
                for ((k, v) in old) if (!out.containsKey(k)) out[k] = v
                editor.putString("sfx_map", out.entries.joinToString("") { "${it.key}|${it.value}\n" })
                mapCount = out.size
                mapNames = out.keys.toSet()
            }
        }

        // 3) 关键词表：合并（同关键词覆盖），且只保留注册表里存在的标记
        var kwCount = 0
        if (kwText.isNotBlank()) {
            val merged = LinkedHashMap<String, String>()
            val oldKw = loadSfxKwList(p)
            for ((k, v) in oldKw) merged[k] = v
            for (line in kwText.lines()) {
                val t = line.trim(); if (t.isEmpty()) continue
                val parts = t.split('|', limit = 2)
                if (parts.size < 2) continue
                val kw = parts[0].trim(); val name = parts[1].trim()
                if (kw.isNotEmpty() && name.isNotEmpty()) merged[kw] = name
            }
            val finalKw = merged.filterValues { name -> name in mapNames }
            editor.putString("sfx_keywords", finalKw.entries.joinToString("") { "${it.key}|${it.value}\n" })
            kwCount = finalKw.size
        }

        // 4) meta：开关/音量/BGM
        var bgmRestored = false
        metaJson?.let { m ->
            fun bool(k: String, d: Boolean) = m.get(k)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: d
            fun flt(k: String, d: Float) = m.get(k)?.takeIf { it.isJsonPrimitive }?.asDouble?.toFloat() ?: d
            editor.putBoolean("sfx_sfx_enabled", bool("sfxEnabled", true))
            editor.putFloat("sfx_sfx_volume", flt("sfxVolume", 0.8f))
            editor.putBoolean("sfx_bgm_enabled", bool("bgmEnabled", false))
            editor.putFloat("sfx_bgm_volume", flt("bgmVolume", 0.3f))
            val bgmEntry = m.get("bgmFile")?.takeIf { it.isJsonPrimitive }?.asString
            if (bgmEntry != null) {
                val base = bgmEntry.removePrefix(SFX_FILES_DIR).substringAfterLast('/')
                written[base]?.let { editor.putString("sfx_bgm_path", it); bgmRestored = true }
            }
        }
        if (!editor.commit()) return fail("写入失败")
        SoundEffectManager.clearCache()
        val parts = mutableListOf<String>()
        if (mapCount > 0) parts.add("$mapCount 条音效")
        if (kwCount > 0) parts.add("$kwCount 条关键词")
        if (audio.isNotEmpty()) parts.add("${written.size} 个音频文件")
        if (bgmRestored) parts.add("BGM")
        return if (parts.isEmpty()) ok("没有可导入的音效数据")
        else ok("已导入 " + parts.joinToString(" + "))
    }

    /** 裸文本导入：第二列以 / 开头视为注册表，否则视为关键词表 */
    private fun importSfxTextFile(ctx: Context, text: String): Result<String> {
        val lines = text.lines().filter { it.trim().isNotEmpty() }
        if (lines.isEmpty()) return fail("文件为空")
        val looksLikeMap = lines.first().split('|').getOrNull(1)?.trim()?.startsWith("/") == true
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = p.edit()
        var n = 0
        if (looksLikeMap) {
            val out = LinkedHashMap<String, String>()
            for ((k, v) in loadSfxMapList(p)) out[k] = v
            for (line in lines) {
                val parts = line.trim().split('|', limit = 2)
                if (parts.size < 2) continue
                val path = parts[1].trim()
                if (File(path).isFile) { out[parts[0].trim()] = path; n++ }
            }
            editor.putString("sfx_map", out.entries.joinToString("") { "${it.key}|${it.value}\n" })
        } else {
            val out = LinkedHashMap<String, String>()
            for ((k, v) in loadSfxKwList(p)) out[k] = v
            val mapNames = loadSfxMapList(p).map { it.first }.toSet()
            for (line in lines) {
                val parts = line.trim().split('|', limit = 2)
                if (parts.size < 2) continue
                if (parts[1].trim() in mapNames) { out[parts[0].trim()] = parts[1].trim(); n++ }
            }
            editor.putString("sfx_keywords", out.entries.joinToString("") { "${it.key}|${it.value}\n" })
        }
        if (!editor.commit()) return fail("写入失败")
        SoundEffectManager.clearCache()
        return ok("已导入 $n 条${if (looksLikeMap) "音效" else "关键词"}")
    }

    private fun loadSfxMapList(p: android.content.SharedPreferences): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (line in (p.getString("sfx_map", "") ?: "").lines()) {
            val t = line.trim(); if (t.isEmpty()) continue
            val s = t.split('|', limit = 2)
            if (s.size >= 2 && s[0].isNotBlank() && s[1].isNotBlank()) out.add(s[0].trim() to s[1].trim())
        }
        return out
    }

    private fun loadSfxKwList(p: android.content.SharedPreferences): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (line in (p.getString("sfx_keywords", "") ?: "").lines()) {
            val t = line.trim(); if (t.isEmpty()) continue
            val s = t.split('|', limit = 2)
            if (s.size >= 2 && s[0].isNotBlank() && s[1].isNotBlank()) out.add(s[0].trim() to s[1].trim())
        }
        return out
    }

    // ============== 内部工具 ==============

    private fun ok(msg: String): Result<String> = Result.success(msg)
    private fun fail(msg: String): Result<String> = Result.failure(RuntimeException(msg))

    private data class ParsedBackup(
        val prefs: Map<String, Pair<String, Any>>,
        val replaces: List<TextPreprocessor.ReplaceRule>,
        val sfxRegistry: List<Pair<String, String>> = emptyList()
    )

    private fun parseBackup(text: String): ParsedBackup {
        val reader = JsonReader(StringReader(text))
        reader.strictness = Strictness.STRICT
        val root = JsonParser.parseReader(reader)
        if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
            throw IllegalArgumentException("JSON末尾存在多余内容")
        }
        if (!root.isJsonObject) throw IllegalArgumentException("根节点必须是对象")
        val obj = root.asJsonObject
        val version = obj.get("version")
        if (version == null || !version.isJsonPrimitive || !version.asJsonPrimitive.isNumber ||
            version.asJsonPrimitive.asString != "1") {
            throw IllegalArgumentException("仅支持 version 1 备份")
        }
        val prefsElement = obj.get("prefs")
        if (prefsElement == null || !prefsElement.isJsonObject) throw IllegalArgumentException("prefs必须是对象")
        val prefs = LinkedHashMap<String, Pair<String, Any>>()
        prefsElement.asJsonObject.entrySet().forEach { (key, element) ->
            if (!element.isJsonObject) throw IllegalArgumentException("设置 $key 格式无效")
            val typed = element.asJsonObject
            val type = typed.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: throw IllegalArgumentException("设置 $key 缺少类型")
            val value = typed.get("value")
            prefs[key] = type to parseSettingValue(key, type, value)
        }
        val replacesElement = obj.get("replaces")
        if (replacesElement == null || !replacesElement.isJsonArray) throw IllegalArgumentException("replaces必须是数组")
        val replaces = replacesElement.asJsonArray.map { parseRule(it) }.filter { it.source.isNotBlank() }
        // 音效注册表（可选字段，旧备份没有）
        val sfxRegElement = obj.get("sfxRegistry")
        val sfxRegistry = if (sfxRegElement != null && sfxRegElement.isJsonArray) {
            sfxRegElement.asJsonArray.mapNotNull { e ->
                if (!e.isJsonObject) null
                else {
                    val o = e.asJsonObject
                    val n = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                    val f = o.get("file")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                    n to f
                }
            }
        } else emptyList()
        return ParsedBackup(prefs, replaces, sfxRegistry)
    }

    private fun parseSettingValue(key: String, type: String, value: JsonElement?): Any {
        if (type == "string" && (value == null || value.isJsonNull)) return ""
        if (value == null || value.isJsonNull || !value.isJsonPrimitive) throw IllegalArgumentException("设置 $key 的值无效")
        val primitive = value.asJsonPrimitive
        return try {
            when (type) {
                "string" -> if (primitive.isString) primitive.asString else throw IllegalArgumentException()
                "int" -> if (primitive.isNumber) primitive.asString.toLong().let {
                    if (it !in Int.MIN_VALUE..Int.MAX_VALUE) throw IllegalArgumentException() else it.toInt()
                } else throw IllegalArgumentException()
                "long" -> if (primitive.isNumber) primitive.asString.toLong() else throw IllegalArgumentException()
                "float" -> if (primitive.isNumber) primitive.asString.toFloat().also {
                    if (!it.isFinite()) throw IllegalArgumentException()
                } else throw IllegalArgumentException()
                "boolean" -> if (primitive.isBoolean) primitive.asBoolean else throw IllegalArgumentException()
                else -> throw IllegalArgumentException("设置 $key 的类型不支持: $type")
            }
        } catch (_: Exception) { throw IllegalArgumentException("设置 $key 的值与类型不匹配") }
    }

    private fun parseRule(element: JsonElement): TextPreprocessor.ReplaceRule {
        if (!element.isJsonObject) throw IllegalArgumentException("规则格式无效")
        val o = element.asJsonObject
        return TextPreprocessor.ReplaceRule(
            stringField(o, "name", ""),
            stringField(o, "group", "默认分组"),
            boolField(o, "activate", true),
            boolField(o, "regex", false),
            stringField(o, "source", ""),
            stringField(o, "target", ""),
            intField(o, "pauseMs", 0)
        )
    }

    private fun stringField(o: JsonObject, name: String, default: String): String {
        val e = o.get(name) ?: return default
        if (e.isJsonNull) return default
        if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString) throw IllegalArgumentException("规则字段 $name 无效")
        return e.asString
    }

    private fun boolField(o: JsonObject, name: String, default: Boolean): Boolean {
        val e = o.get(name) ?: return default
        if (!e.isJsonPrimitive || !e.asJsonPrimitive.isBoolean) throw IllegalArgumentException("规则字段 $name 无效")
        return e.asBoolean
    }

    private fun intField(o: JsonObject, name: String, default: Int): Int {
        val e = o.get(name) ?: return default
        if (!e.isJsonPrimitive) throw IllegalArgumentException("规则字段 $name 无效")
        return e.asJsonPrimitive.asString.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }
            ?: throw IllegalArgumentException("规则字段 $name 无效")
    }

}
