package com.mtts.app.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mtts.app.engine.EngineRegistry
import com.mtts.app.engine.TtsEngine
import java.io.File
import com.mtts.app.engine.Voice
import com.mtts.app.engine.VoicePackageManager
import com.mtts.app.util.TextPreprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 预处理主页 —— 三个功能卡片入口（各进独立二级页），与设置主页同套视觉 */
@Composable
fun PreprocessHome(vm: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "朗读文本进入 TTS 前的统一处理层。各设置独立开关、独立配置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 文字替换
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            SettingsRowItem(
                icon = Icons.Default.Edit,
                title = "文字替换",
                desc = "把指定词或正则命中的内容替换为其他文字，可附加停顿",
                onClick = { vm.openPreprocessPage(PreprocessSubPage.REPLACE) }
            )
        }
        // 多音字
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            SettingsRowItem(
                icon = Icons.Default.Spellcheck,
                title = "多音字纠正",
                desc = "按上下文纠正多音字读音，避免 TTS 误读",
                onClick = { vm.openPreprocessPage(PreprocessSubPage.POLYPHONIC) }
            )
        }
        // 表情符号处理
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            SettingsRowItem(
                icon = Icons.Default.SentimentSatisfied,
                title = "表情符号",
                desc = "把无法朗读的 emoji 转为文字，标点符号可控制停顿",
                onClick = { vm.openPreprocessPage(PreprocessSubPage.EMOJI) }
            )
        }
        // 智能识别
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            SettingsRowItem(
                icon = Icons.Default.AutoAwesome,
                title = "智能识别",
                desc = "识别口语与情绪关键词，自动调节停顿、音量与音调",
                onClick = { vm.openPreprocessPage(PreprocessSubPage.SMART) }
            )
        }
    }
}

/** 共同开关行 */
@Composable
internal fun ToggleRow(title: String, desc: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.secondary)
        }
        AppSwitch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** 预处理统一开关 —— 标准 Material3 默认浅色样式 */
@Composable
internal fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier)
}

/**
 * 构造默认试听句子：把规则名/原词放进一句自然提示语，
 * 避免正则源码（如 \d+、[.、$1 等）被 TTS 逐字符朗读。
 * 非正则规则原词嵌在句子里，替换照样生效，能听出处理效果。
 */
private fun defaultRuleSample(r: TextPreprocessor.ReplaceRule): String {
    val special = Regex("[\\\\^$.|?*+()\\[\\]{}]")
    val label = when {
        r.name.isNotBlank() && !special.containsMatchIn(r.name) -> r.name
        r.source.isNotBlank() && !special.containsMatchIn(r.source) -> r.source
        else -> "该规则"
    }
    return "正在试听预处理「$label」的句子"
}

/** 折叠态可读标签：优先用规则名；正则规则绝不露出正则源码（\d、[、$ 等），无规则名时退化为简短「正则」。 */
private fun friendlyRuleLabel(r: TextPreprocessor.ReplaceRule): String {
    val special = Regex("[\\\\^$.|?*+()\\[\\]{}]")
    if (r.regex) {
        return if (r.name.isNotBlank() && !special.containsMatchIn(r.name)) r.name else "正则"
    }
    // 非正则：优先规则名，其次原词（原词即 source，非正则时安全）
    return if (r.name.isNotBlank()) r.name else r.source
}

/**
 * 替换规则编辑卡片。
 * 折叠态：一行「A → B」（+停顿/正则标记），右侧 ▶ 试听（默认播 A 的处理结果，即整条规则效果）。
 * 点击整条 → 展开编辑框修改规则。
 */
@Composable
private fun RuleCard(
    index: Int,
    rule: TextPreprocessor.ReplaceRule,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onChange: (TextPreprocessor.ReplaceRule) -> Unit,
    onDelete: () -> Unit,
    onPlay: (String) -> Unit,          // ★ 试听（默认传入 A 的处理结果）
    showPause: Boolean = true
) {
    // 试听示例文本：非正则用原词 A；正则用自然提示句（避免正则源码 \d+ [] 等被 TTS 逐字符朗读）
    var lastDefault by remember {
        mutableStateOf(if (rule.regex) defaultRuleSample(rule) else rule.source)
    }
    var sample by remember { mutableStateOf(lastDefault) }
    // 记录上次默认值：若用户没自定义过试听文本（sample 仍等于旧默认），source/正则变化时跟随
    LaunchedEffect(rule.source, rule.regex) {
        val newDefault = if (rule.regex) defaultRuleSample(rule) else rule.source
        if (sample == lastDefault) sample = newDefault
        lastDefault = newDefault
    }
    val processed = TextPreprocessor.applySingleRule(sample, rule)

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // ===== 折叠态（默认）=====：A → B + 试听，点击整卡展开
            if (!expanded) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onToggleExpand).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // A → B 主体：A 右对齐 / 箭头固定在行中部 / B 左对齐 → 行行箭头对齐
                    Text(friendlyRuleLabel(rule).ifBlank { "…" }, style = MaterialTheme.typography.bodyLarge,
                         color = MaterialTheme.colorScheme.onBackground, maxLines = 1,
                         textAlign = TextAlign.End,
                         modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text("→", style = MaterialTheme.typography.titleMedium,
                         color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    // B 区（标记内嵌，保证每行外层结构一致 → 箭头对齐）
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(rule.target.ifBlank { "…" }, style = MaterialTheme.typography.bodyLarge,
                             color = MaterialTheme.colorScheme.secondary, maxLines = 1,
                             modifier = Modifier.weight(1f, fill = false))
                        if (rule.pauseMs > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text("+${rule.pauseMs}ms", style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    // 试听按钮（默认播 A 的处理结果）
                    IconButton(onClick = {
                        // 未配置 source 时用 sample 默认文本，否则播 A 的处理结果
                        onPlay(if (rule.source.isBlank()) sample else processed)
                    }) {
                        Icon(Icons.Filled.PlayArrow, "试听：${rule.source} → ${rule.target}",
                             tint = MaterialTheme.colorScheme.primary)
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 规则启用开关（最右）
                    AppSwitch(
                        checked = rule.activate,
                        onCheckedChange = { onChange(rule.copy(activate = it)) }
                    )
                }
            } else {
                // ===== 展开态：编辑字段 =====
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#${index + 1}", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("编辑规则", style = MaterialTheme.typography.titleSmall,
                         color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    IconButton(onClick = onToggleExpand) {
                        Icon(Icons.Filled.KeyboardArrowUp, "收起", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(value = rule.name, onValueChange = { onChange(rule.copy(name = it)) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true)
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(value = rule.source, onValueChange = { onChange(rule.copy(source = it)) },
                    modifier = Modifier.fillMaxWidth(), label = { Text(if (rule.regex) "匹配(正则)" else "匹配(原词)") },
                    singleLine = true)
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(value = rule.target, onValueChange = { onChange(rule.copy(target = it)) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("替换为") }, singleLine = true)
                if (showPause) {
                    Spacer(Modifier.padding(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("停顿(ms)：", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(4.dp))
                        OutlinedTextField(
                            value = if (rule.pauseMs > 0) rule.pauseMs.toString() else "",
                            onValueChange = { onChange(rule.copy(pauseMs = it.toIntOrNull() ?: 0)) },
                            modifier = Modifier.width(110.dp).height(56.dp),
                            singleLine = true, label = { Text("ms") }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正则：", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(4.dp))
                        AppSwitch(
                            checked = rule.regex,
                            onCheckedChange = { onChange(rule.copy(regex = it)) }
                        )
                    }
                }
                // 启用开关
                Spacer(Modifier.padding(2.dp))
                ToggleRow("启用本规则", "关闭后这条规则不参与替换", rule.activate) { onChange(rule.copy(activate = it)) }
                // 试听区：示例文本（默认=正则自然句/原词A）→ 处理后 → 播放
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(
                    value = sample, onValueChange = { sample = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (rule.regex) "试听文本（默认=自然句，命中正则后替换）" else "试听文本（默认=原词A）") }, singleLine = true
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("处理后：", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(processed.take(120), style = MaterialTheme.typography.bodyMedium,
                         color = if (processed != sample) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.weight(1f))
                    IconButton(onClick = { onPlay(processed) }) {
                        Icon(Icons.Filled.PlayArrow, "试听处理后的结果",
                             tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/**
 * 收集某引擎的全部音色（供发声人多选列表）
 */
private suspend fun collectVoices(): List<Pair<TtsEngine, Voice>> {
    val out = ArrayList<Pair<TtsEngine, Voice>>()
    for (engine in EngineRegistry.all()) {
        val vs = withContext(Dispatchers.IO) {
            runCatching { engine.listVoices() }.getOrDefault(emptyList())
        }
        for (v in vs) out.add(engine to v)
    }
    return out
}

/** 文字替换二级页 —— 普通/正则替换 + 停顿（MultiTTS replaces.yaml 兼容） */
@Composable
fun ReplaceContent(
    vm: MainViewModel,
    searchOpen: Boolean = false,
    addOpen: Boolean = false,
    onCloseSearch: () -> Unit = {},
    onCloseAdd: () -> Unit = {}
) {
    var enabled by remember { mutableStateOf(TextPreprocessor.isReplaceEnabled()) }
    var rules by remember { mutableStateOf(TextPreprocessor.loadReplacesFromVoice()) }
    var groups by remember { mutableStateOf(TextPreprocessor.loadReplaceGroups()) }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
    var query by remember { mutableStateOf("") }
    var rulesCollapsed by remember { mutableStateOf(true) } // 大折叠：规则列表默认收起
    var testIn by remember { mutableStateOf("他520了，BOSS说：等一下……") }
    var testOut by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    // 正在编辑的规则（点行弹编辑框；保存直接写回 replaces.yaml）
    var editingRule by remember { mutableStateOf<TextPreprocessor.ReplaceRule?>(null) }

    // 落盘：把当前规则列表直接写回 replaces.yaml（单源，无附加）
    fun persist(list: List<TextPreprocessor.ReplaceRule>) {
        TextPreprocessor.saveReplacesToVoice(list)
        rules = list
        groups = TextPreprocessor.loadReplaceGroups()
    }

    fun reload() {
        rules = TextPreprocessor.loadReplacesFromVoice()
        groups = TextPreprocessor.loadReplaceGroups()
    }
    LaunchedEffect(reloadKey) { reload() }

    val q = query.trim().lowercase()
    fun match(r: TextPreprocessor.ReplaceRule): Boolean =
        q.isEmpty() || r.name.lowercase().contains(q) || r.source.lowercase().contains(q) || r.target.lowercase().contains(q)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 功能开关
        item {
            SectionCard(
                title = "文字替换",
                subtitle = "把指定词或正则命中的内容替换为其他文字，可附加停顿。当前共 ${rules.size} 条。"
            ) {
                ToggleRow("启用文字替换", "关闭后跳过所有文字替换", enabled) { enabled = it; TextPreprocessor.setReplaceEnabled(it) }
            }
        }

        // 3) 测试（位于规则上方）
        item {
            SectionCard(title = "测试", subtitle = "输入文本，预览替换结果并试听（停顿已生效）") {
                OutlinedTextField(value = testIn, onValueChange = { testIn = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("输入测试文本") })
                Spacer(Modifier.height(8.dp))
                Row {
                    AppOutlinedButton(text = "处理预览", onClick = {
                        testOut = TextPreprocessor.previewReplace(testIn)
                    }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    AppPrimaryButton(text = "试听", enabled = testOut.isNotEmpty(), onClick = {
                        vm.playText(testOut)
                    }, modifier = Modifier.weight(1f))
                }
                if (testOut.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("结果：", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(testOut, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // 4) 规则列表（大折叠置底，默认收起）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { rulesCollapsed = !rulesCollapsed }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (rulesCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                         null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("规则列表", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (rulesCollapsed) "${rules.size} 条规则 · ${groups.size} 个分组 · 点击展开"
                            else "${rules.size} 条规则 · ${groups.size} 个分组 · 点击收起",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (!rulesCollapsed) {
            // 每个分组：头（卡片式，含删除分组）+ 组内规则行
            groups.forEach { group ->
                val groupRules = rules.filter { it.group == group }.filter(::match)
                if (groupRules.isEmpty() && group != groups.firstOrNull()) return@forEach
                val expanded = group in expandedGroups
                item(key = "grp-$group") {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    expandedGroups = if (expanded) expandedGroups - group else expandedGroups + group
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(if (group.isBlank()) "默认分组" else group, Modifier.weight(1f),
                                 style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text("${groupRules.size} 条", style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.secondary)
                            // 删除分组（仅当分组内无规则时允许，避免误删规则）
                            if (groupRules.isEmpty() && rules.any { it.group == group }.not()) {
                                Spacer(Modifier.width(4.dp))
                                IconButton(onClick = {
                                    val reversed = rules.groupBy { it.group }
                                        .toSortedMap(compareByDescending<String> { it }.thenBy { it })
                                    // 只删空分组
                                    if (rules.none { it.group == group }) {
                                        groups = groups - group
                                    }
                                }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Delete, "删除分组", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                if (expanded) {
                    if (groupRules.isEmpty()) {
                        item(key = "grp-empty-$group") {
                            Text("该分组暂无规则，点击右上角 ＋ 添加", style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                                 modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp))
                        }
                    }
                    items(groupRules.size, key = { "grp-$group-$it" }) { i ->
                        val rule = groupRules[i]
                        val idx = rules.indexOfFirst { it === rule }
                        ReplaceRuleRow(rule = rule, ruleIndex = if (idx >= 0) idx else -1,
                            onEdit = { editingRule = rule },
                            onToggle = { on ->
                                val updated = rules.toMutableList()
                                if (idx >= 0) updated[idx] = updated[idx].copy(activate = on)
                                persist(updated)
                            },
                            onDelete = {
                                if (idx >= 0) {
                                    val updated = rules.toMutableList().also { it.removeAt(idx) }
                                    persist(updated)
                                }
                            },
                            onPlay = { processedText -> vm.playText(processedText) })
                    }
                }
            }
        }
    }

    // 规则编辑对话框：保存直接写回 replaces.yaml（含分组/正则/停顿/开关）
    editingRule?.let { rule ->
        val idx = rules.indexOfFirst { it === rule }
        ReplaceRuleEditDialog(
            initial = rule,
            title = "编辑替换规则",
            onDismiss = { editingRule = null },
            onPlay = { text -> vm.playText(text) },
            onSave = { newRule ->
                if (idx >= 0) {
                    val updated = rules.toMutableList().also { it[idx] = newRule.copy(group = rule.group) }
                    persist(updated)
                }
                editingRule = null
            }
        )
    }

    // 新建分组对话框（右上角 ＋ 始终为添加规则，分组在列表内新建）
    if (addOpen) {
        var name by remember { mutableStateOf("") }
        var source by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("") }
        var regex by remember { mutableStateOf(false) }
        var pauseMs by remember { mutableStateOf("") }
        var selectedGroup by remember { mutableStateOf((groups.firstOrNull() ?: "默认分组")) }
        // 新建分组输入（非空时优先用新分组加入）
        var newGroupName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { onCloseAdd() },
            title = { Text("添加替换规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true)
                    OutlinedTextField(value = source, onValueChange = { source = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text(if (regex) "匹配(正则)" else "匹配(原词)") }, singleLine = true)
                    OutlinedTextField(value = target, onValueChange = { target = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("替换为") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正则：", style = MaterialTheme.typography.bodySmall)
                        AppSwitch(checked = regex, onCheckedChange = { regex = it })
                        Spacer(Modifier.width(12.dp))
                        OutlinedTextField(value = pauseMs, onValueChange = { pauseMs = it.filter(Char::isDigit) },
                            modifier = Modifier.width(120.dp), label = { Text("停顿(ms)") }, singleLine = true)
                    }
                    // 分组：选择已有 OR 新建
                    Text("分组：", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        groups.forEach { g ->
                            val isSel = selectedGroup == g && newGroupName.isBlank()
                            FilterChip(selected = isSel,
                                onClick = { selectedGroup = g; newGroupName = "" },
                                label = { Text(g.ifBlank { "默认分组" }) })
                        }
                    }
                    OutlinedTextField(value = newGroupName, onValueChange = {
                        newGroupName = it
                        if (it.isNotBlank()) selectedGroup = it // 输入新分组名则改用新建
                    }, modifier = Modifier.fillMaxWidth(), label = { Text("或输入新分组名（新建分组）") },
                        singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (source.isNotBlank()) {
                        val groupName = if (newGroupName.isNotBlank()) newGroupName.trim()
                                       else selectedGroup.ifBlank { "默认分组" }
                        val rule = TextPreprocessor.ReplaceRule(
                            name = name.ifBlank { source },
                            source = source, target = target,
                            regex = regex,
                            pauseMs = pauseMs.toIntOrNull() ?: 0,
                            activate = true,
                            group = groupName
                        )
                        val updated = rules + rule
                        persist(updated)
                        importMsg = "已添加规则"
                        onCloseAdd()
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { onCloseAdd() }) { Text("取消") } }
        )
    }

    // 搜索规则弹窗（跨所有分组的规则）
    if (searchOpen) {
        var sq by remember { mutableStateOf("") }
        val sqTrim = sq.trim().lowercase()
        val hits = rules.filter { r ->
            sqTrim.isEmpty() || r.name.lowercase().contains(sqTrim) ||
                r.source.lowercase().contains(sqTrim) || r.target.lowercase().contains(sqTrim) ||
                r.group.lowercase().contains(sqTrim)
        }
        AlertDialog(
            onDismissRequest = { onCloseSearch() },
            title = { Text("搜索规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sq, onValueChange = { sq = it },
                        modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索 名称 / 原词 / 替换 / 分组") },
                        singleLine = true)
                    if (hits.isEmpty()) {
                        Text("无匹配规则", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(hits.size) { i ->
                                val r = hits[i]
                                val idx = rules.indexOfFirst { it === r }
                                ReplaceRuleRow(rule = r, ruleIndex = if (idx >= 0) idx else -1,
                                    onEdit = { editingRule = r; onCloseSearch() },
                                    onToggle = { on ->
                                        if (idx >= 0) { val u = rules.toMutableList(); u[idx] = u[idx].copy(activate = on); persist(u) }
                                    },
                                    onDelete = {
                                        if (idx >= 0) persist(rules.toMutableList().also { it.removeAt(idx) })
                                    },
                                    onPlay = { processedText -> vm.playText(processedText) })
                                Text("（${r.group.ifBlank { "默认分组" }}）", style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onCloseSearch() }) { Text("关闭") } }
        )
    }
}

/** 语音包规则的紧凑可读行 —— A → B（卡片式留白，无分隔线，可点击编辑） */
@Composable
private fun CompactRuleRow(r: TextPreprocessor.ReplaceRule, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A（右对齐，箭头固定在行中部）
            Text(friendlyRuleLabel(r).ifBlank { "…" }, style = MaterialTheme.typography.bodyLarge,
                 color = MaterialTheme.colorScheme.onBackground, maxLines = 1,
                 textAlign = TextAlign.End,
                 modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text("→", style = MaterialTheme.typography.titleSmall,
                 color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            // B 区（标记内嵌，保证每行外层结构一致 → 箭头对齐）
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(r.target.ifBlank { "…" }, style = MaterialTheme.typography.bodyLarge,
                     color = MaterialTheme.colorScheme.secondary, maxLines = 1,
                     modifier = Modifier.weight(1f, fill = false))
                if (r.pauseMs > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("+${r.pauseMs}ms", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.primary)
                }
            }
            // 编辑提示
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Edit, "编辑", tint = MaterialTheme.colorScheme.primary,
                 modifier = Modifier.size(16.dp))
        }
    }
}

/** 把规则列表序列化为 MultiTTS ReplaceConfig YAML */
private fun toYaml(rules: List<TextPreprocessor.ReplaceRule>): String {
    val sb = StringBuilder("!!org.nobody.multitts.tts.replace.ReplaceConfig\nreplaces:\n")
    for (r in rules) {
        sb.append("- name: ").append(quoteYaml(r.name)).append('\n')
        sb.append("  group: 默认分组\n")
        sb.append("  activate: ").append(r.activate).append('\n')
        sb.append("  regex: ").append(r.regex).append('\n')
        sb.append("  source: ").append(quoteYaml(r.source)).append('\n')
        sb.append("  target: ").append(quoteYaml(r.target)).append('\n')
        if (r.pauseMs > 0) sb.append("  pauseMs: ").append(r.pauseMs).append('\n')
    }
    return sb.toString()
}

private fun quoteYaml(s: String): String {
    if (s.isEmpty()) return "''"
    if (s.any { it in ":#\n\"'" }) return "\"" + s.replace("\"", "\\\"") + "\""
    return s
}

/** 多音字二级页 —— 主字 + 多条上下文规则（词→同音字），识别不同语境的读音 */
@Composable
fun PolyphonicContent(
    vm: MainViewModel,
    searchOpen: Boolean = false,
    addOpen: Boolean = false,
    onCloseSearch: () -> Unit = {},
    onCloseAdd: () -> Unit = {}
) {
    var enabled by remember { mutableStateOf(TextPreprocessor.isPolyEnabled()) }
    var builtin by remember { mutableStateOf(TextPreprocessor.loadBuiltinPolyEntries()) }
    var extra by remember { mutableStateOf(TextPreprocessor.loadExtraPolyEntries()) }
    var testIn by remember { mutableStateOf("长江的水很漫长，市长沿长城走来。") }
    var testOut by remember { mutableStateOf("") }
    // 新增主字的输入
    var newChar by remember { mutableStateOf("") }
    var newRulesText by remember { mutableStateOf("") }
    // 折叠：内置多音字默认展开主字列表，规则折叠（点击主字才展开该字规则，避免几百条全平铺）
    var expandedBuiltinChar by remember { mutableStateOf<String?>(null) }
    // 附加多音字展开项（-1=全收起）
    var expandedPoly by remember { mutableStateOf(-1) }
    // 内置列表搜索
    var searchQuery by remember { mutableStateOf("") }
    // 大折叠：规则列表整体默认收起（保护内层小折叠）
    var rulesCollapsed by remember { mutableStateOf(true) }
    // 正在编辑的内置规则（走覆盖机制持久化）
    var editingBuiltin by remember { mutableStateOf<BuiltinPolyEdit?>(null) }
    // 修改覆盖后重新加载内置列表（覆盖记录自动剔除/禁用/换 target）
    val refreshBuiltin = { builtin = TextPreprocessor.loadBuiltinPolyEntries() }

    // 搜索过滤：按主字 / 规则词 / 同音词
    val q = searchQuery.trim()
    val builtinFiltered = if (q.isEmpty()) builtin else builtin.filter { e ->
        e.char.contains(q) || e.rules.any { r -> r.word.contains(q) || r.target.contains(q) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1) 功能开关
        item {
            SectionCard(
                title = "多音字纠正",
                subtitle = "按上下文把多音字替换为正确读音，避免误读。当前内置 ${builtin.sumOf { it.rules.size }} 条、附加 ${extra.sumOf { it.rules.size }} 条。"
            ) {
                ToggleRow("启用多音字纠正", "关闭后跳过所有读音/同音字替换", enabled) { enabled = it; TextPreprocessor.setPolyEnabled(it) }
            }
        }

        // 3) 测试（预览 + 试听，位于规则上方）
        item {
            SectionCard(title = "测试", subtitle = "输入含多音字的句子，预览替换结果并试听") {
                OutlinedTextField(
                    value = testIn, onValueChange = { testIn = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("输入测试文本") }
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    AppOutlinedButton(text = "处理预览", onClick = {
                        testOut = TextPreprocessor.previewPoly(testIn)
                    }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    AppPrimaryButton(text = "试听", enabled = testOut.isNotEmpty(), onClick = {
                        vm.playText(testOut)
                    }, modifier = Modifier.weight(1f))
                }
                if (testOut.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("结果：", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(testOut, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // 4) 规则列表（大折叠置底，默认收起）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { rulesCollapsed = !rulesCollapsed }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (rulesCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                         null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("规则列表", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (rulesCollapsed) "内置 ${builtinFiltered.size} 个主字 / 附加 ${extra.size} 个主字 · 点击展开"
                            else "内置 ${builtinFiltered.size} 个主字 / 附加 ${extra.size} 个主字 · 点击收起",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        // 规则列表展开内容（每个区块独立成卡，避免双层背景）
        if (!rulesCollapsed) {
            // 内置多音字
            item {
                SectionCard(
                    title = "内置多音字（${builtinFiltered.size} 个主字 / ${builtinFiltered.sumOf { it.rules.size }} 条规则）",
                    subtitle = if (q.isEmpty()) "点击主字展开上下文规则（词→同音字），每条可开关 / 编辑 / 删除"
                               else "搜索「$q」命中 ${builtinFiltered.size} 个主字"
                ) {
                    if (builtinFiltered.isEmpty()) {
                        Text("没有匹配的规则", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        builtinFiltered.forEach { e ->
                            val isOpen = expandedBuiltinChar == e.char
                            BuiltinPolyCharCard(
                                entry = e,
                                expanded = isOpen,
                                onToggleOpen = { expandedBuiltinChar = if (isOpen) null else e.char },
                                onToggleRule = { builtinKey, target, on ->
                                    TextPreprocessor.setPolyOverride(e.char, builtinKey,
                                        TextPreprocessor.PolyOverride(target = target, enabled = on, deleted = false))
                                    refreshBuiltin()
                                },
                                onEditRule = { builtinKey, target, enabled ->
                                    val curWord = e.rules.firstOrNull { it.builtinKey == builtinKey }?.word ?: builtinKey
                                    editingBuiltin = BuiltinPolyEdit(e.char, builtinKey, curWord, target, enabled)
                                },
                                onDeleteRule = { builtinKey ->
                                    TextPreprocessor.setPolyOverride(e.char, builtinKey,
                                        TextPreprocessor.PolyOverride(deleted = true))
                                    refreshBuiltin()
                                },
                                onPlay = { text -> vm.playText(text) }
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
            // 附加多音字（改动即时保存）
            item {
                fun persist() { TextPreprocessor.setExtraPolyEntries(extra) }
                SectionCard(
                    title = "附加多音字（${extra.size} 个主字）",
                    subtitle = "每个主字可配多条上下文规则（词→同音字），点击卡片展开编辑规则"
                ) {
                    if (extra.isEmpty()) {
                        Text("暂无附加多音字。", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                    }
                    extra.forEachIndexed { i, e ->
                        PolyEntryCard(
                            entry = e,
                            expanded = expandedPoly == i,
                            onToggleExpand = { expandedPoly = if (expandedPoly == i) -1 else i },
                            onChange = { new ->
                                extra = extra.toMutableList().also { it[i] = new }
                                persist() // 即时保存
                            },
                            onDelete = {
                                extra = extra.toMutableList().also { it.removeAt(i) }
                                persist() // 即时保存
                            },
                            onPlay = { processedText -> vm.playText(processedText) }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    // 内置规则编辑对话框（保存走覆盖机制持久化；可改上下文词 + 同音词）
    editingBuiltin?.let { edit ->
        val builtinTarget = TextPreprocessor.loadRawBuiltinRule(edit.char, edit.builtinKey)
        BuiltinPolyRuleDialog(
            char = edit.char,
            word = edit.word,                  // 当前显示词
            builtinWord = edit.builtinKey,     // 内置原始词（覆盖定位 key）
            initialTarget = edit.target,
            initialEnabled = edit.enabled,
            builtinTarget = builtinTarget,
            onDismiss = { editingBuiltin = null },
            onPlay = { text -> vm.playText(text) },
            onSave = { newWord, newTarget, enabled ->
                // 覆盖 key 固定用内置原始词；改词存 newWord；与内置一致自动清理
                TextPreprocessor.setPolyOverride(edit.char, edit.builtinKey,
                    TextPreprocessor.PolyOverride(
                        target = newTarget,
                        newWord = if (newWord != edit.builtinKey) newWord else "",
                        enabled = enabled,
                        deleted = false))
                refreshBuiltin()
                editingBuiltin = null
            }
        )
    }

    // 添加多音字弹窗（右上角 ＋ 入口）
    if (addOpen) {
        var ch by remember { mutableStateOf("") }
        var rulesText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { onCloseAdd() },
            title = { Text("添加多音字") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = ch, onValueChange = { ch = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("主字（如 长）") }, singleLine = true)
                    OutlinedTextField(value = rulesText, onValueChange = { rulesText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("规则（词→同音词，用 / 分隔，如 长江→常江/市长→市掌）") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chTrim = ch.trim()
                    val parsed = parseNewRules(rulesText)
                    if (chTrim.isNotEmpty() && parsed.isNotEmpty()) {
                        extra = extra + TextPreprocessor.PolyEntry(char = chTrim, rules = parsed)
                        TextPreprocessor.setExtraPolyEntries(extra) // 即时保存
                        onCloseAdd()
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { onCloseAdd() }) { Text("取消") } }
        )
    }

    // 搜索多音字弹窗（右上角 🔍 入口；跨 内置 + 附加）
    if (searchOpen) {
        var sq by remember { mutableStateOf("") }
        val sqTrim = sq.trim()
        val allItems = buildList {
            addAll(extra.map { Pair("附加", it) })
            addAll(builtin.map { Pair("内置", it) })
        }
        val hits = allItems.filter { (_, e) ->
            sqTrim.isEmpty() || e.char.contains(sqTrim) ||
                e.rules.any { r -> r.word.contains(sqTrim) || r.target.contains(sqTrim) }
        }
        AlertDialog(
            onDismissRequest = { onCloseSearch() },
            title = { Text("搜索多音字") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sq, onValueChange = { sq = it },
                        modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索主字 / 规则词 / 同音词") },
                        singleLine = true)
                    if (hits.isEmpty()) {
                        Text("无匹配多音字", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(hits.size) { i ->
                                val (kind, e) = hits[i]
                                Card(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                                        // 展开规则列表并定位到该主字
                                        if (kind == "内置") {
                                            expandedBuiltinChar = e.char
                                        } else {
                                            val idx = extra.indexOfFirst { it.char == e.char }
                                            expandedPoly = idx
                                        }
                                        rulesCollapsed = false
                                        onCloseSearch()
                                    },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                        Text("${e.char}（$kind）", style = MaterialTheme.typography.bodyLarge,
                                             color = MaterialTheme.colorScheme.onBackground)
                                        Text("${e.rules.size} 条 · ${e.rules.joinToString("；") { "${it.word}→${it.target}" }}",
                                             style = MaterialTheme.typography.bodySmall,
                                             color = MaterialTheme.colorScheme.secondary, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onCloseSearch() }) { Text("关闭") } }
        )
    }
}

/** 解析新增多音字规则输入：词→同音词，多条用 / 分隔 */
private fun parseNewRules(text: String): List<TextPreprocessor.PolyRule> {
    val out = ArrayList<TextPreprocessor.PolyRule>()
    for (seg in text.split('/', '，', ',')) {
        val s = seg.trim()
        if (s.isEmpty()) continue
        val arrow = s.indexOf('→')
        if (arrow > 0) {
            val word = s.substring(0, arrow).trim()
            val target = s.substring(arrow + 1).trim()
            if (word.isNotEmpty() && target.isNotEmpty()) {
                out.add(TextPreprocessor.PolyRule(word = word, target = target, activate = true))
            }
        }
    }
    return out
}

/**
 * 多音字编辑卡片。
 * 折叠态：主字 + N 条上下文规则，右侧 ▶ 试听（默认播主字所在词的处理结果），点击整卡展开编辑。
 * 展开态：编辑主字、逐条编辑规则（词→同音词）、新增/删除规则、试听区。
 */
@Composable
private fun PolyEntryCard(
    entry: TextPreprocessor.PolyEntry,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onChange: (TextPreprocessor.PolyEntry) -> Unit,
    onDelete: () -> Unit,
    onPlay: (String) -> Unit
) {
    // 新增规则的输入
    var newWord by remember { mutableStateOf("") }
    var newTarget by remember { mutableStateOf("") }
    // 试听示例文本：默认取第一条规则的上下文词（含主字），让用户听到真实替换效果
    var sample by remember { mutableStateOf(entry.rules.firstOrNull()?.word ?: entry.char) }
    // 记录上次的默认样例：若用户没自定义过（sample 仍等于旧默认），主字/规则变化时跟随
    var lastDefault by remember { mutableStateOf(entry.rules.firstOrNull()?.word ?: entry.char) }
    LaunchedEffect(entry.char, entry.rules.size) {
        val newDefault = entry.rules.firstOrNull()?.word ?: entry.char
        if (sample == lastDefault) sample = newDefault
        lastDefault = newDefault
    }
    val processed = TextPreprocessor.applySinglePoly(sample, entry)

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // ===== 折叠态（默认）=====：主字 + 规则数 + 试听，点击整卡展开
            if (!expanded) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onToggleExpand).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(entry.char.ifBlank { "…" }, style = MaterialTheme.typography.titleLarge,
                         color = MaterialTheme.colorScheme.onBackground, maxLines = 1,
                         modifier = Modifier.weight(0.3f, fill = false))
                    Spacer(Modifier.width(12.dp))
                    // 规则摘要：前两条
                    val summary = entry.rules.take(2).joinToString("　") { "${it.word}→${it.target}" }
                    val more = if (entry.rules.size > 2) " 等${entry.rules.size}条" else ""
                    Text(summary + more, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                         modifier = Modifier.weight(1f))
                    // 试听按钮（默认播样例 → 该主字规则处理结果）
                    IconButton(onClick = { onPlay(sample) }) {
                        Icon(Icons.Filled.PlayArrow, "试听：$sample",
                             tint = MaterialTheme.colorScheme.primary)
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // ===== 展开态：编辑主字 + 规则列表 =====
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("编辑多音字", style = MaterialTheme.typography.titleSmall,
                         color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    IconButton(onClick = onToggleExpand) {
                        Icon(Icons.Filled.KeyboardArrowUp, "收起", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.padding(2.dp))
                // 主字 + 删除
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = entry.char,
                        onValueChange = { onChange(entry.copy(char = it)) },
                        modifier = Modifier.weight(1f),
                        label = { Text("主字（如 长）") }, singleLine = true
                    )
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 规则列表：词 → 同音词，可编辑/删除
                Text("上下文规则（词 → 同音词）：", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(2.dp))
                if (entry.rules.isEmpty()) {
                    Text("暂无规则，添加下方规则。", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                }
                entry.rules.forEachIndexed { idx, r ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 词（可编辑）
                            OutlinedTextField(
                                value = r.word,
                                onValueChange = { new -> onChange(entry.copy(rules = entry.rules.mapIndexed { i, rr -> if (i == idx) rr.copy(word = new) else rr })) },
                                modifier = Modifier.weight(0.5f),
                                label = { Text("词") }, singleLine = true
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("→", style = MaterialTheme.typography.titleSmall,
                                 color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            // 同音词（可编辑）
                            OutlinedTextField(
                                value = r.target,
                                onValueChange = { new -> onChange(entry.copy(rules = entry.rules.mapIndexed { i, rr -> if (i == idx) rr.copy(target = new) else rr })) },
                                modifier = Modifier.weight(0.5f),
                                label = { Text("同音词") }, singleLine = true
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = {
                                onChange(entry.copy(rules = entry.rules.filterIndexed { i, _ -> i != idx }))
                            }) {
                                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error,
                                     modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 新增规则：词 → 同音词
                Text("新增规则", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newWord, onValueChange = { newWord = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("上下文词（含主字，如 长江）") }, singleLine = true
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("→", style = MaterialTheme.typography.titleSmall,
                         color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(
                        value = newTarget, onValueChange = { newTarget = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("同音词（如 常江）") }, singleLine = true
                    )
                    Spacer(Modifier.width(6.dp))
                    AppTextButton(text = "添加", onClick = {
                        val w = newWord.trim()
                        val t = newTarget.trim()
                        if (w.isNotEmpty() && t.isNotEmpty()) {
                            onChange(entry.copy(rules = entry.rules + TextPreprocessor.PolyRule(word = w, target = t)))
                            newWord = ""; newTarget = ""
                        }
                    })
                }
                Spacer(Modifier.height(6.dp))
                // 试听区：样例文本（默认=主字首个规则词）→ 处理后 → 播放
                OutlinedTextField(
                    value = sample, onValueChange = { sample = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("试听文本（默认=首个规则的词）") }, singleLine = true
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("处理后：", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(processed.take(120), style = MaterialTheme.typography.bodyMedium,
                         color = if (processed != sample) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.weight(1f))
                    IconButton(onClick = { onPlay(processed) }) {
                        Icon(Icons.Filled.PlayArrow, "试听处理后的结果",
                             tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** 内置多音字主字卡片 —— 折叠：主字 + 规则数；展开：每条规则 开关[A→B 占满]编辑[删除]，无分隔线卡片留白 */
@Composable
private fun BuiltinPolyCharCard(
    entry: TextPreprocessor.PolyEntry,
    expanded: Boolean,
    onToggleOpen: () -> Unit,
    onToggleRule: (String, String, Boolean) -> Unit,   // builtinKey, target, enable
    onEditRule: (String, String, Boolean) -> Unit,     // builtinKey, target, enabled
    onDeleteRule: (String) -> Unit,
    onPlay: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        // 主字头：点击展开/收起（▸ 长 靠左，N 条规则 靠右，中间撑满）
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggleOpen).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(entry.char.ifBlank { "…" }, style = MaterialTheme.typography.titleMedium,
                 color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("${entry.rules.size} 条规则", style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        if (expanded) {
            Spacer(Modifier.height(2.dp))
            // 每条规则一行：词→同音词（占满整行）+ 试听 + 编辑 + 删除 + 开关（最右）
            entry.rules.forEachIndexed { _, r ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 词 → 同音词：word 右对齐 / 箭头固定在行中部 / target 左对齐（行行箭头对齐）
                        Text(r.word.ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium,
                             color = if (r.activate) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                             maxLines = 1, textAlign = TextAlign.End,
                             modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        Text("→", style = MaterialTheme.typography.labelLarge,
                             color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(r.target.ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium,
                             color = if (r.activate) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                             maxLines = 1, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        // 试听该规则（用当前显示词）
                        IconButton(onClick = { onPlay(r.word) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.PlayArrow, "试听${r.word}", tint = MaterialTheme.colorScheme.primary,
                                 modifier = Modifier.size(18.dp))
                        }
                        // 编辑（用内置原始词定位覆盖，改词后仍命中同一条）
                        IconButton(onClick = { onEditRule(r.builtinKey.ifBlank { r.word }, r.target, r.activate) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Edit, "编辑", tint = MaterialTheme.colorScheme.primary,
                                 modifier = Modifier.size(16.dp))
                        }
                        // 删除（用内置原始词定位覆盖）
                        IconButton(onClick = { onDeleteRule(r.builtinKey.ifBlank { r.word }) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error,
                                 modifier = Modifier.size(16.dp))
                        }
                        // 规则开关（最右，标准浅色样式；用内置原始词定位覆盖）
                        AppSwitch(
                            checked = r.activate,
                            onCheckedChange = { onToggleRule(r.builtinKey.ifBlank { r.word }, r.target, it) }
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/** 内置规则编辑对话框的数据（char 主字 / builtinKey 内置原始词 / word 当前显示词 / target 当前同音替换 / enabled 当前启用状态） */
private data class BuiltinPolyEdit(
    val char: String,
    val builtinKey: String,
    val word: String = "",
    val target: String = "",
    val enabled: Boolean = true
)

/** 内置多音字规则编辑对话框 —— 修改上下文词 + 同音词 + 启用开关；保存走覆盖机制持久化 */
@Composable
private fun BuiltinPolyRuleDialog(
    char: String,
    word: String,                  // 当前显示的上下文词（可能已是覆盖改词）
    builtinWord: String,           // 内置原始上下文词（覆盖定位 key）
    initialTarget: String,
    initialEnabled: Boolean,
    builtinTarget: String?,
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit,
    onSave: (String, String, Boolean) -> Unit    // (newWord, newTarget, enabled)
) {
    var newWord by remember { mutableStateOf(word) }
    var target by remember { mutableStateOf(initialTarget) }
    var enabled by remember { mutableStateOf(initialEnabled) }
    val processed = TextPreprocessor.applySingleRule(
        newWord,
        TextPreprocessor.ReplaceRule(name = "内置", source = newWord, target = target, activate = enabled)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑内置规则 · $char") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = newWord, onValueChange = { newWord = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("上下文词（可改）") }, singleLine = true)
                OutlinedTextField(value = target, onValueChange = { target = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("同音替换词") }, singleLine = true)
                if (builtinTarget != null && builtinTarget != target) {
                    Text("内置默认：$builtinTarget", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (builtinWord != word) {
                    Text("内置原始词：$builtinWord", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppSwitch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text("启用本规则", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("处理后：", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(processed.take(120), style = MaterialTheme.typography.bodyMedium,
                         color = if (processed != newWord) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.weight(1f))
                    IconButton(onClick = { onPlay(processed) }) {
                        Icon(Icons.Filled.PlayArrow, "试听", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    newWord.trim().ifBlank { builtinWord },
                    target.trim().ifBlank { builtinTarget.orEmpty() },
                    enabled
                )
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 对话识别二级页 —— 对白发言人 + 旁白发言人 + 分配模式 + 角色规则（对齐 MultiTTS DLG/NRT） */
@Composable
fun PreprocessDialogContent(vm: MainViewModel) {
    var enabled by remember { mutableStateOf(TextPreprocessor.isDialogEnabled()) }
    // 选中的对白发言人 key：engine:voice
    var selected by remember { mutableStateOf(loadSavedSelected()) }
    // 旁白发言人（多选；空=不配置，用默认音色）
    var narrationKeys by remember { mutableStateOf(loadSavedNarrationKeys()) }
    // 对白分配模式：cycle/same/off
    var roleMode by remember { mutableStateOf(TextPreprocessor.getDialogRoleMode()) }
    var testIn by remember { mutableStateOf("她说：“你快来呀！”他答道：「就来。」") }
    val rolePreview by remember { mutableStateOf(TextPreprocessor.previewRoleRules(testIn)) }

    // 全部引擎音色（suspend）
    var allVoices by remember { mutableStateOf(emptyList<Pair<TtsEngine, Voice>>()) }
    LaunchedEffect(Unit) { allVoices = collectVoices() }

    // 角色规则
    var rules by remember { mutableStateOf(TextPreprocessor.loadRoleRules()) }
    var editingRuleIdx by remember { mutableStateOf(Int.MIN_VALUE) }
    var editingRule by remember { mutableStateOf(TextPreprocessor.RoleRule()) }

    // 当前查看的引擎（默认第一个，用户可切换，避免几十个音色全平铺）
    val engineCodes = allVoices.map { it.first.code }.distinct()
    var activeEngine by remember { mutableStateOf(engineCodes.firstOrNull().orEmpty()) }
    // 当前引擎的音色
    val activeVoices = allVoices.filter { it.first.code == activeEngine }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ===== 开关 =====
        item {
            SectionCard(
                title = "对话识别",
                subtitle = "识别引号内对白（DLG）+ 引号外旁白（NRT），分别分配发言人"
            ) {
                ToggleRow("启用对话识别", "关闭后整段用当前引擎朗读", enabled) { enabled = it; TextPreprocessor.setDialogEnabled(it) }
            }
        }

        // ===== 引擎切换 =====
        item {
            SectionCard(title = "选择引擎", subtitle = "切换要配置发声人的引擎") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    engineCodes.forEach { ec ->
                        val eng = EngineRegistry.get(ec)
                        FilterChip(
                            selected = activeEngine == ec,
                            onClick = { activeEngine = ec },
                            label = { Text(eng?.name ?: ec) }
                        )
                    }
                }
            }
        }

        // ===== 发声人配置（对白多选 + 旁白多选）=====
        item {
            SectionCard(
                title = "发声人配置 · ${EngineRegistry.get(activeEngine)?.name ?: activeEngine}",
                subtitle = "☑ 对白 = 引号内台词  ☑ 旁白 = 引号外叙述（均多选，轮询分配）"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    activeVoices.forEach { (_, voice) ->
                        val key = "$activeEngine:${voice.shortName}"
                        val inDlg = key in selected
                        val isNrt = key in narrationKeys
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 对白复选框
                            Checkbox(checked = inDlg, onCheckedChange = {
                                selected = if (inDlg) selected - key else selected + key
                            })
                            Column(Modifier.weight(1f)) {
                                Text(voice.name.ifBlank { voice.shortName }, style = MaterialTheme.typography.bodyMedium)
                                Text(voice.shortName, style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.width(8.dp))
                            // 旁白复选框
                            Checkbox(checked = isNrt, onCheckedChange = {
                                narrationKeys = if (isNrt) narrationKeys - key else narrationKeys + key
                            })
                            Text("旁白", style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    if (activeVoices.isEmpty()) {
                        Text("（该引擎暂无音色）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("已选对白 ${selected.size} 个 · 旁白 ${narrationKeys.size} 个（未配置旁白用默认音色，多选则轮询）",
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        // ===== 对白分配模式 =====
        item {
            SectionCard(title = "对白分配模式", subtitle = "对白段在多个对白发言人之间如何分配") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("cycle" to "轮询", "same" to "同一人", "off" to "不分").forEach { (mode, label) ->
                        FilterChip(selected = roleMode == mode, onClick = { roleMode = mode }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("轮询=对白依次换发言人；同一人=全部用第一个；不分=对白不用独立发言人",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }

        // ===== 角色规则（MultiTTS RoleRule）=====
        item {
            SectionCard(
                title = "角色规则",
                subtitle = "正则匹配文本段 → 绑定发言人（对齐 MultiTTS：如「他说」→ 张三）。未命中 → 旁白"
            ) {
                if (rules.isEmpty()) {
                    Text("暂无规则，点击「+ 新增规则」。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(6.dp))
                }
                rules.forEachIndexed { idx, r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name.ifBlank { "未命名" }, style = MaterialTheme.typography.bodyMedium)
                            Text("正则: ${r.regex.ifBlank { "(空)" }}", style = MaterialTheme.typography.bodySmall,
                                 fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary)
                            val eng = EngineRegistry.get(r.engineCode)
                            Text("→ ${eng?.name ?: r.engineCode.ifBlank { "默认引擎" }} / ${r.voice.ifBlank { "默认音色" }}",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = if (r.engineCode.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                            if (r.isAbstract) Text("抽象规则（仅锚点）", style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.tertiary)
                        }
                        if (!r.activate) Text("停用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        TextButton(onClick = { editingRuleIdx = idx; editingRule = r }) { Text("编辑") }
                        TextButton(onClick = {
                            rules = rules.filterIndexed { i, _ -> i != idx }
                            TextPreprocessor.setRoleRules(rules)
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
                TextButton(onClick = {
                    editingRuleIdx = -1
                    editingRule = TextPreprocessor.RoleRule(name = "规则${rules.size + 1}")
                }) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Text(" 新增规则")
                }
            }
        }

        // ===== 保存 =====
        item {
            AppPrimaryButton(text = "保存全部设置", onClick = {
                val lines = selected.sorted().mapIndexed { i, key ->
                    val parts = key.split(':')
                    "发言人${i + 1}|${parts[0]}|${parts.drop(1).joinToString(":")}"
                }.joinToString("\n")
                TextPreprocessor.setDialogRolesText(lines)
                // 旁白多选保存
                val narrationRoles = narrationKeys.map { key ->
                    val parts = key.split(':')
                    TextPreprocessor.DialogRoleCfg(
                        label = "旁白",
                        engineCode = parts[0],
                        voice = parts.drop(1).joinToString(":")
                    )
                }
                TextPreprocessor.setNarrationRoles(narrationRoles)
                TextPreprocessor.setDialogRoleMode(roleMode)
            }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(2.dp))
            Text("保存后：对白段按模式用对白发言人，旁白段用旁白发言人（多选则轮询），命中角色规则的段用规则发言人。",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }

        // ===== 预览 =====
        item {
            SectionCard(title = "对话识别预览（含角色规则分配）", subtitle = "每条：类型 → 发言人 → 文本") {
                rolePreview.forEachIndexed { i, seg ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (seg.isSpeech) "对白" else "旁白",
                             style = MaterialTheme.typography.labelSmall,
                             color = if (seg.isSpeech) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(6.dp))
                        if (seg.engineCode.isNotBlank()) {
                            val eng = EngineRegistry.get(seg.engineCode)
                            Text(eng?.name ?: seg.engineCode, style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.width(72.dp))
                        } else {
                            Text(if (seg.isSpeech) "对白发言人" else "旁白发言人", style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(72.dp))
                        }
                        Text(seg.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    // 角色规则编辑对话框（独立于 LazyColumn）
    if (editingRuleIdx != Int.MIN_VALUE) {
        val isNew = editingRuleIdx == -1
        RuleEditorDialog(
            initial = editingRule,
            voices = allVoices,
            onDismiss = { editingRuleIdx = Int.MIN_VALUE },
            onSave = { newRule ->
                rules = if (isNew) rules + newRule else rules.mapIndexed { i, r -> if (i == editingRuleIdx) newRule else r }
                TextPreprocessor.setRoleRules(rules)
                editingRuleIdx = Int.MIN_VALUE
            }
        )
    }
}

/** 从已保存的旁白角色恢复选中的 key 列表（多选） */
private fun loadSavedNarrationKeys(): Set<String> {
    val set = LinkedHashSet<String>()
    for (r in TextPreprocessor.getNarrationRoles()) {
        if (r.engineCode.isBlank() || r.voice.isBlank()) continue
        set.add("${r.engineCode}:${r.voice}")
    }
    return set
}

/** 从已保存的角色行恢复选中的 key 列表 */
private fun loadSavedSelected(): Set<String> {
    val set = LinkedHashSet<String>()
    for (role in TextPreprocessor.loadDialogRoles()) {
        set.add("${role.engineCode}:${role.voice}")
    }
    return set
}

/** 角色规则编辑对话框（MultiTTS RoleRule：正则→发言人） */
@Composable
private fun RuleEditorDialog(
    initial: TextPreprocessor.RoleRule,
    voices: List<Pair<TtsEngine, Voice>>,
    onDismiss: () -> Unit,
    onSave: (TextPreprocessor.RoleRule) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var regex by remember { mutableStateOf(initial.regex) }
    var engineCode by remember { mutableStateOf(initial.engineCode) }
    var voice by remember { mutableStateOf(initial.voice) }
    var activate by remember { mutableStateOf(initial.activate) }
    var isAbstract by remember { mutableStateOf(initial.isAbstract) }

    // 按引擎分组供选择
    val grouped = voices.groupBy { it.first.code }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "新增角色规则" else "编辑规则") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("规则名（人物名）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = regex, onValueChange = { regex = it },
                    label = { Text("匹配正则（例：他说(.{0,20})）") },
                    singleLine = false, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("命中该正则的文本段 → 用下方发言人；留空=不匹配", style = MaterialTheme.typography.labelSmall) }
                )

                // 发言人：引擎 + 音色
                Text("绑定发言人", style = MaterialTheme.typography.titleSmall)
                grouped.forEach { (ec, pairs) ->
                    val engName = pairs.first().first.name
                    FilterChip(
                        selected = engineCode == ec,
                        onClick = { engineCode = ec; voice = "" },
                        label = { Text("$engName [$ec]") }
                    )
                }
                // 当前引擎下的音色下拉
                val engVoices = grouped[engineCode].orEmpty()
                if (engVoices.isNotEmpty()) {
                    OutlinedTextField(
                        value = voice,
                        onValueChange = { voice = it },
                        label = { Text("音色代码") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text("当前引擎音色：" + engVoices.joinToString("、") { it.second.shortName }.take(80),
                                 style = MaterialTheme.typography.labelSmall)
                        }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppSwitch(checked = activate, onCheckedChange = { activate = it })
                    Spacer(Modifier.width(8.dp))
                    Text("启用规则")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppSwitch(checked = isAbstract, onCheckedChange = { isAbstract = it })
                    Spacer(Modifier.width(8.dp))
                    Text("抽象规则（仅锚点，不直接发音）")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(TextPreprocessor.RoleRule(
                    name = name.trim().ifBlank { "未命名" },
                    regex = regex.trim(),
                    engineCode = engineCode,
                    voice = voice.trim(),
                    activate = activate,
                    isAbstract = isAbstract,
                    matchBefore = initial.matchBefore,
                    matchAfter = initial.matchAfter
                ))
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 分区卡片（对齐设置主页 Card 视觉：surface + 1dp 阴影 + large 圆角） */
@Composable
internal fun SectionCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                 color = MaterialTheme.colorScheme.onBackground)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** 替换规则编辑对话框（用于语音包规则覆盖编辑 + 附加规则编辑） */
@Composable
private fun ReplaceRuleEditDialog(
    initial: TextPreprocessor.ReplaceRule,
    title: String,
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit,
    onSave: (TextPreprocessor.ReplaceRule) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var source by remember { mutableStateOf(initial.source) }
    var target by remember { mutableStateOf(initial.target) }
    var regex by remember { mutableStateOf(initial.regex) }
    var pauseMs by remember { mutableStateOf(initial.pauseMs) }
    var activate by remember { mutableStateOf(initial.activate) }
    // 试听默认文本：正则用自然提示句，非正则用原词（避免正则源码被 TTS 逐字符朗读）
    var lastDefault by remember {
        mutableStateOf(if (initial.regex) defaultRuleSample(initial) else initial.source)
    }
    var sample by remember { mutableStateOf(lastDefault) }
    LaunchedEffect(initial.source, initial.regex) {
        val newDefault = if (initial.regex) defaultRuleSample(initial) else initial.source
        if (sample == lastDefault) sample = newDefault
        lastDefault = newDefault
    }
    val processed = TextPreprocessor.applySingleRule(sample, TextPreprocessor.ReplaceRule(
        name = name, source = source, target = target, regex = regex, pauseMs = pauseMs, activate = activate
    ))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = source, onValueChange = { source = it },
                    label = { Text(if (regex) "匹配(正则)" else "匹配(原词)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = target, onValueChange = { target = it },
                    label = { Text("替换为") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                // 正则 + 停顿
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("正则", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(4.dp))
                    AppSwitch(checked = regex, onCheckedChange = { regex = it })
                    Spacer(Modifier.width(16.dp))
                    Text("停顿(ms)", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(
                        value = if (pauseMs > 0) pauseMs.toString() else "",
                        onValueChange = { pauseMs = it.toIntOrNull() ?: 0 },
                        modifier = Modifier.width(100.dp).height(56.dp),
                        singleLine = true, label = { Text("ms") }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppSwitch(checked = activate, onCheckedChange = { activate = it })
                    Spacer(Modifier.width(8.dp))
                    Text("启用规则")
                }
                // 试听区
                OutlinedTextField(
                    value = sample, onValueChange = { sample = it },
                    label = { Text(if (regex) "试听文本（默认=自然句，命中正则后替换）" else "试听文本（默认=原词A）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("处理后：", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(processed.take(120), style = MaterialTheme.typography.bodyMedium,
                         color = if (processed != sample) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.weight(1f))
                    IconButton(onClick = { onPlay(processed) }) {
                        Icon(Icons.Filled.PlayArrow, "试听处理后的结果",
                             tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(TextPreprocessor.ReplaceRule(
                    name = name.trim(),
                    source = source,
                    target = target,
                    regex = regex,
                    pauseMs = pauseMs,
                    activate = activate,
                    group = initial.group
                ))
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 文字替换规则行 —— 对齐多音字（BuiltinPolyCharCard）规则行样式：
 *  A → B（A 右对齐 / 箭头固定行中部 / B 左对齐，行行箭头对齐）+ 正则/停顿标记 + 试听 + 编辑 + 删除 + 开关（最右）。
 * 无分隔线、卡片式留白，可点击编辑、独立开关/试听/删除。
 */
@Composable
private fun ReplaceRuleRow(
    rule: TextPreprocessor.ReplaceRule,
    ruleIndex: Int,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onPlay: (String) -> Unit
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A（右对齐，箭头固定在行中部）
            Text(friendlyRuleLabel(rule).ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium,
                 color = if (rule.activate) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                 maxLines = 1, textAlign = TextAlign.End,
                 modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Text("→", style = MaterialTheme.typography.labelLarge,
                 color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            // B 区（标记内嵌，保证每行外层结构一致 → 箭头对齐）
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(rule.target.ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium,
                     color = if (rule.activate) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                     maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (rule.pauseMs > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text("+${rule.pauseMs}ms", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(6.dp))
            // 试听
            IconButton(onClick = { onPlay(rule.source) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.PlayArrow, "试听${rule.source}", tint = MaterialTheme.colorScheme.primary,
                     modifier = Modifier.size(18.dp))
            }
            // 编辑
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, "编辑", tint = MaterialTheme.colorScheme.primary,
                     modifier = Modifier.size(16.dp))
            }
            // 删除
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error,
                     modifier = Modifier.size(16.dp))
            }
            // 规则开关（最右）
            AppSwitch(checked = rule.activate, onCheckedChange = onToggle)
        }
    }
}

/** 替换规则导入导出设置页 —— 独立设置项，从文字替换页迁移而来 */
@Composable
fun ReplaceIoContent(vm: MainViewModel) {
    val ctx = LocalContext.current
    var rules by remember { mutableStateOf(TextPreprocessor.loadReplacesFromVoice()) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        rules = TextPreprocessor.loadReplacesFromVoice()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard(
            title = "替换规则导入导出",
            subtitle = "当前共 ${rules.size} 条规则。导入兼容 MultiTTS 格式，导出含分组/停顿/开关。"
        ) {
            // 导出
            AppOutlinedButton(text = "导出规则", modifier = Modifier.fillMaxWidth(), icon = Icons.Default.ExitToApp,
                onClick = { doExport(ctx, rules) })
            Spacer(Modifier.height(8.dp))
            // 导入
            val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                try {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) return@rememberLauncherForActivityResult
                    val imported = TextPreprocessor.parseReplaceYaml(String(bytes, Charsets.UTF_8))
                    if (imported.isNotEmpty()) {
                        val merged = rules.filterNot { existing ->
                            imported.any { it.source == existing.source && it.regex == existing.regex }
                        } + imported
                        TextPreprocessor.saveReplacesToVoice(merged)
                        rules = merged
                        importMsg = "已导入 ${imported.size} 条（合并去重，现共 ${merged.size} 条）"
                    } else {
                        importMsg = "导入失败：未解析到有效规则"
                    }
                    reloadKey++
                } catch (e: Exception) { importMsg = "导入失败: ${e.message}" }
            }
            AppPrimaryButton(text = "导入规则", modifier = Modifier.fillMaxWidth(), icon = Icons.Default.FileOpen,
                onClick = { picker.launch(arrayOf("text/yaml", "text/plain", "application/octet-stream", "application/x-yaml", "application/json")) })
            importMsg?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * 导出替换规则为自定义扩展格式 YAML（含分组/pauseMs/开关），写入系统下载目录 replaces_export.yaml。
 */
private fun doExport(ctx: android.content.Context, rules: List<TextPreprocessor.ReplaceRule>) {
    val text = StringBuilder("!!org.nobody.multitts.tts.replace.ReplaceConfig\nreplaces:\n")
    for (r in rules) {
        text.append("- name: ").append(quoteYaml(r.name)).append('\n')
        text.append("  group: ").append(quoteYaml(r.group.ifBlank { "默认分组" })).append('\n')
        text.append("  activate: ").append(r.activate).append('\n')
        text.append("  regex: ").append(r.regex).append('\n')
        text.append("  source: ").append(quoteYaml(r.source)).append('\n')
        text.append("  target: ").append(quoteYaml(r.target)).append('\n')
        if (r.pauseMs > 0) text.append("  pauseMs: ").append(r.pauseMs).append('\n')
    }
    val content = text.toString()
    val fileName = "replaces_export.yaml"
    try {
        val resolver = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/x-yaml")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建下载文件")
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("无法写入")
            Toast.makeText(ctx, "已导出到下载目录 $fileName", Toast.LENGTH_SHORT).show()
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IllegalStateException("下载目录不可用")
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeText(content)
            Toast.makeText(ctx, "已导出到 ${File(dir, fileName).absolutePath}", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(ctx, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
    }
}