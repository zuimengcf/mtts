package com.mtts.app.util

import android.content.Context
import android.net.Uri
import java.io.File
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
        val all: Map<String, Any?> = p?.all ?: emptyMap()
        val replaces = TextPreprocessor.loadReplacesFromVoice()

        val sb = StringBuilder(8192)
        sb.append("{\n")
        sb.append("  \"version\": 1,\n")
        sb.append("  \"exportedAt\": ").append(jsonStr(System.currentTimeMillis().toString())).append(",\n")
        sb.append("  \"prefs\": {\n")
        val keys = all.keys.toList().sorted()
        keys.forEachIndexed { i, k ->
            sb.append("    ").append(jsonStr(k)).append(": ").append(prefsValueJson(all[k]))
            if (i < keys.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  },\n")
        sb.append("  \"replaces\": [\n")
        replaces.forEachIndexed { i, r ->
            sb.append("    {")
            sb.append("\"name\":").append(jsonStr(r.name)).append(",")
            sb.append("\"source\":").append(jsonStr(r.source)).append(",")
            sb.append("\"target\":").append(jsonStr(r.target)).append(",")
            sb.append("\"regex\":").append(r.regex).append(",")
            sb.append("\"activate\":").append(r.activate).append(",")
            sb.append("\"group\":").append(jsonStr(r.group)).append(",")
            sb.append("\"pauseMs\":").append(r.pauseMs)
            sb.append("}")
            if (i < replaces.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
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
        val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = p.edit()

        val prefs = parsePrefsSection(text)
        prefs.entries.forEach { e ->
            val k: String = e.key
            val type: String = e.value.first
            val value: String? = e.value.second
            try {
                when (type) {
                    "string"  -> editor.putString(k, value ?: "")
                    "int"     -> editor.putInt(k, value?.toIntOrNull() ?: 0)
                    "long"    -> editor.putLong(k, value?.toLongOrNull() ?: 0L)
                    "float"   -> editor.putFloat(k, value?.toFloatOrNull() ?: 0f)
                    "boolean" -> editor.putBoolean(k, value == "true")
                    else -> {}
                }
            } catch (_: Exception) {}
        }
        editor.apply()

        val replaces = parseReplacesSection(text)
        if (replaces.isNotEmpty()) {
            val old = TextPreprocessor.loadReplacesFromVoice()
            val merged = old.filterNot { existing ->
                replaces.any { it.source == existing.source && it.regex == existing.regex }
            } + replaces
            TextPreprocessor.saveReplacesToVoice(merged)
            return ok("已导入 ${prefs.size} 项设置 + ${replaces.size} 条规则（合并去重）")
        }
        return ok("已导入 ${prefs.size} 项设置")
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

    // ============== 内部工具 ==============

    private fun ok(msg: String): Result<String> = Result.success(msg)
    private fun fail(msg: String): Result<String> = Result.failure(RuntimeException(msg))

    private fun jsonStr(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code))
                    else sb.append(c)
                }
            }
        }
        return sb.append('"').toString()
    }

    private fun prefsValueJson(v: Any?): String = when (v) {
        null -> "null"
        is String -> "{ \"type\": \"string\", \"value\": " + jsonStr(v) + " }"
        is Int -> "{ \"type\": \"int\", \"value\": " + v + " }"
        is Long -> "{ \"type\": \"long\", \"value\": " + v + " }"
        is Float -> "{ \"type\": \"float\", \"value\": " + v + " }"
        is Double -> "{ \"type\": \"float\", \"value\": " + v + " }"
        is Boolean -> "{ \"type\": \"boolean\", \"value\": " + v + " }"
        is FloatArray -> "{ \"type\": \"string\", \"value\": " + jsonStr(java.util.Arrays.toString(v)) + " }"
        is Set<*> -> "{ \"type\": \"string\", \"value\": " + jsonStr(v.toString()) + " }"
        else -> "{ \"type\": \"string\", \"value\": " + jsonStr(v.toString()) + " }"
    }

    private fun parsePrefsSection(text: String): Map<String, Pair<String, String?>> {
        val out = HashMap<String, Pair<String, String?>>()
        val marker = "\"prefs\""
        val start = text.indexOf(marker)
        if (start < 0) return out
        val open = text.indexOf('{', start + marker.length)
        if (open < 0) return out
        var depth = 0
        var end = -1
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        if (end < 0) return out
        val section = text.substring(open + 1, end)
        var i = 0
        while (i < section.length) {
            val q1 = section.indexOf('"', i)
            if (q1 < 0) break
            val q2 = section.indexOf('"', q1 + 1)
            if (q2 < 0) break
            val key = unescape(section.substring(q1 + 1, q2))
            val brace = section.indexOf('{', q2)
            if (brace < 0) break
            var d2 = 0
            var bEnd = -1
            for (j in brace until section.length) {
                when (section[j]) {
                    '{' -> d2++
                    '}' -> { d2--; if (d2 == 0) { bEnd = j; break } }
                }
            }
            if (bEnd < 0) break
            val obj = section.substring(brace, bEnd + 1)
            val typeIdx = obj.indexOf("\"type\"")
            val valIdx = obj.indexOf("\"value\"")
            if (typeIdx >= 0 && valIdx >= 0) {
                val (t, v) = extractTypedValue(obj, typeIdx, valIdx)
                out[key] = t to v
            }
            i = bEnd + 1
        }
        return out
    }

    private fun extractTypedValue(obj: String, typeIdx: Int, valIdx: Int): Pair<String, String?> {
        val tColon = obj.indexOf(':', typeIdx)
        val tQ1 = obj.indexOf('"', tColon)
        val tQ2 = obj.indexOf('"', tQ1 + 1)
        val type = if (tQ1 >= 0 && tQ2 > tQ1) obj.substring(tQ1 + 1, tQ2) else "string"
        val vColon = obj.indexOf(':', valIdx)
        val vStart = vColon + 1
        val vTrim = obj.substring(vStart).trimStart()
        return if (vTrim.startsWith("\"")) {
            val vEnd = vTrim.indexOf('"', 1)
            type to (if (vEnd > 0) unescape(vTrim.substring(1, vEnd)) else null)
        } else {
            val vRaw = vTrim.takeWhile { !it.isWhitespace() && it != ',' && it != '}' }
            type to vRaw
        }
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> { sb.append('\\'); sb.append(s[i + 1]) }
                }
                i += 2
            } else {
                sb.append(s[i]); i++
            }
        }
        return sb.toString()
    }

    private fun parseReplacesSection(text: String): List<TextPreprocessor.ReplaceRule> {
        val marker = "\"replaces\""
        val start = text.indexOf(marker)
        if (start < 0) return emptyList()
        val open = text.indexOf('[', start)
        if (open < 0) return emptyList()
        var depth = 0
        var end = -1
        for (i in open until text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        if (end < 0) return emptyList()
        val section = text.substring(open + 1, end)
        val out = ArrayList<TextPreprocessor.ReplaceRule>()
        var i = 0
        while (i < section.length) {
            val brace = section.indexOf('{', i)
            if (brace < 0) break
            var d2 = 0
            var bEnd = -1
            for (j in brace until section.length) {
                when (section[j]) {
                    '{' -> d2++
                    '}' -> { d2--; if (d2 == 0) { bEnd = j; break } }
                }
            }
            if (bEnd < 0) break
            val obj = section.substring(brace, bEnd + 1)
            val name = extractStringField(obj, "name") ?: ""
            val source = extractStringField(obj, "source") ?: ""
            val target = extractStringField(obj, "target") ?: ""
            val regex = extractBoolField(obj, "regex") ?: false
            val activate = extractBoolField(obj, "activate") ?: true
            val group = extractStringField(obj, "group") ?: "默认分组"
            val pauseMs = extractIntField(obj, "pauseMs") ?: 0
            if (source.isNotBlank()) {
                out.add(TextPreprocessor.ReplaceRule(
                    name, group, activate, regex, source, target, pauseMs))
            }
            i = bEnd + 1
        }
        return out
    }

    private fun extractStringField(obj: String, name: String): String? {
        val p = obj.indexOf("\"$name\"")
        if (p < 0) return null
        val colon = obj.indexOf(':', p)
        if (colon < 0) return null
        val s = obj.substring(colon + 1).trimStart()
        if (!s.startsWith("\"")) return null
        val end = s.indexOf('"', 1)
        return if (end > 0) unescape(s.substring(1, end)) else null
    }

    private fun extractBoolField(obj: String, name: String): Boolean? {
        val p = obj.indexOf("\"$name\"")
        if (p < 0) return null
        val colon = obj.indexOf(':', p)
        if (colon < 0) return null
        val v = obj.substring(colon + 1).trimStart().takeWhile { it != ',' && it != '}' }.trim()
        return when (v.lowercase()) { "true" -> true; "false" -> false; else -> null }
    }

    private fun extractIntField(obj: String, name: String): Int? {
        val p = obj.indexOf("\"$name\"")
        if (p < 0) return null
        val colon = obj.indexOf(':', p)
        if (colon < 0) return null
        val v = obj.substring(colon + 1).trimStart().takeWhile { it.isDigit() || it == '-' }.trim()
        return v.toIntOrNull()
    }
}