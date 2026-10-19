package com.mtts.app.util

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
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
            return ok("已导入 ${parsed.prefs.size} 项设置 + ${parsed.replaces.size} 条规则（合并去重）")
        }
        return ok("已导入 ${parsed.prefs.size} 项设置")
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

    private data class ParsedBackup(
        val prefs: Map<String, Pair<String, Any>>,
        val replaces: List<TextPreprocessor.ReplaceRule>
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
        return ParsedBackup(prefs, replaces)
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
