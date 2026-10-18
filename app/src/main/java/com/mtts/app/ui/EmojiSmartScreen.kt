package com.mtts.app.ui

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.mtts.app.util.TextPreprocessor

// ==================== 表情符号处理 ====================

/** 表情符号处理二级页 —— emoji→文字 + 标点→停顿（以多音字页为模板：顶部开关+搜索，规则列表可编辑/开关/删除/试听） */
@Composable
fun EmojiContent(
    vm: MainViewModel,
    searchOpen: Boolean = false,
    addOpen: Boolean = false,
    onCloseSearch: () -> Unit = {},
    onCloseAdd: () -> Unit = {}
) {
    var enabled by remember { mutableStateOf(TextPreprocessor.isEmojiEnabled()) }
    var emojis by remember { mutableStateOf(TextPreprocessor.loadEmojiMaps()) }
    var puncts by remember { mutableStateOf(TextPreprocessor.loadPunctRules()) }
    var searchQuery by remember { mutableStateOf("") }
    var testIn by remember { mutableStateOf("今天好开心😂🪿，我们一起去玩吧……等等！") }
    var testOut by remember { mutableStateOf("") }
    var rulesCollapsed by remember { mutableStateOf(true) } // 大折叠：规则列表默认收起
    var editingEmoji by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editingPunct by remember { mutableStateOf<TextPreprocessor.PunctRule?>(null) }

    fun reload() {
        emojis = TextPreprocessor.loadEmojiMaps()
        puncts = TextPreprocessor.loadPunctRules()
    }
    LaunchedEffect(Unit) { reload() }

    val q = searchQuery.trim()
    val emojiFiltered = if (q.isEmpty()) emojis else emojis.filter { (e, w) -> e.contains(q) || w.contains(q) }
    val punctFiltered = if (q.isEmpty()) puncts else puncts.filter { it.name.contains(q) || it.pattern.contains(q) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard(
                title = "表情符号处理",
                subtitle = "把无法朗读的 emoji 转为文字，并把标点符号转为停顿控制。"
            ) {
                ToggleRow("启用表情符号处理", "关闭后 emoji 与标点保持原样", enabled) { enabled = it; TextPreprocessor.setEmojiEnabled(it) }
            }
        }

        // 测试（位于规则上方）
        item {
            SectionCard(title = "测试", subtitle = "输入含 emoji 和标点的文本，预览转换结果") {
                OutlinedTextField(value = testIn, onValueChange = { testIn = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("测试文本") })
                Spacer(Modifier.height(8.dp))
                Row {
                    AppPrimaryButton(text = "转换预览", onClick = {
                        testOut = TextPreprocessor.applyEmojiFeature(testIn)
                    }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    AppOutlinedButton(text = "试听", onClick = {
                        testOut = TextPreprocessor.applyEmojiFeature(testIn)
                        vm.playText(testOut)
                    }, modifier = Modifier.weight(1f))
                }
                if (testOut.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("转换后（含停顿标记）：", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(testOut, style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 4) 规则列表（大折叠置底，默认收起）
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = MaterialTheme.shapes.large
            ) {
                // 大折叠卡头
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
                            if (rulesCollapsed) "emoji ${emojiFiltered.size} / 标点 ${punctFiltered.size} · 点击展开"
                            else "emoji ${emojiFiltered.size} / 标点 ${punctFiltered.size} · 点击收起",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        // 规则列表展开内容（每个区块独立成卡，避免双层背景/阴影）
        if (!rulesCollapsed) {
            // 标点 → 停顿规则
            item {
                SectionCard(
                    title = "标点 → 停顿规则（${punctFiltered.size}）",
                    subtitle = "读到这类标点即插入停顿（毫秒），让朗读有节奏。"
                ) {
                    if (punctFiltered.isEmpty()) {
                        Text("无匹配的标点规则", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        punctFiltered.forEach { p ->
                            RuleRow(
                                text = "${p.name}   →  ${p.ms}ms",
                                sub = p.pattern,
                                enabled = p.enabled,
                                onToggle = { TextPreprocessor.setPunctOverride(p.pattern, p.ms, it, false); reload() },
                                onEdit = { editingPunct = p },
                                onDelete = { TextPreprocessor.setPunctOverride(p.pattern, p.ms, true, true); reload() },
                                onPlay = { vm.playText("试听标点停顿。句号。逗号，省略号……") }
                            )
                        }
                    }
                }
            }
            // emoji → 中文映射
            item {
                SectionCard(
                    title = "emoji → 中文映射（${emojiFiltered.size}）",
                    subtitle = "把无法朗读的 emoji 转为中文文字。点击编辑/删除/开关"
                ) {
                    if (emojiFiltered.isEmpty()) {
                        Text("无匹配的 emoji 映射", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        emojiFiltered.forEach { (e, w) ->
                            RuleRow(
                                text = "$e  →  $w",
                                sub = "emoji",
                                enabled = true,
                                onToggle = { TextPreprocessor.setEmojiOverride(e, w, it, false); reload() },
                                onEdit = { editingEmoji = e to w },
                                onDelete = { TextPreprocessor.setEmojiOverride(e, w, true, true); reload() },
                                onPlay = { vm.playText("试听emoji：今天好开心$e 我们出发吧") }
                            )
                        }
                    }
                }
            }
        }
    }

    editingEmoji?.let { (emoji, word) ->
        var newWord by remember { mutableStateOf(word) }
        AlertDialog(
            onDismissRequest = { editingEmoji = null },
            title = { Text("编辑 emoji 映射") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = emoji, onValueChange = {}, enabled = false,
                        modifier = Modifier.fillMaxWidth(), label = { Text("emoji") }, singleLine = true)
                    OutlinedTextField(value = newWord, onValueChange = { newWord = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("中文词") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    TextPreprocessor.setEmojiOverride(emoji, newWord.trim().ifBlank { word }, true, false)
                    editingEmoji = null
                    reload()
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingEmoji = null }) { Text("取消") } }
        )
    }

    editingPunct?.let { p ->
        var ms by remember { mutableStateOf(p.ms.toString()) }
        AlertDialog(
            onDismissRequest = { editingPunct = null },
            title = { Text("编辑标点停顿 · ${p.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = p.pattern, onValueChange = {}, enabled = false,
                        modifier = Modifier.fillMaxWidth(), label = { Text("标点（正则）") }, singleLine = true)
                    OutlinedTextField(value = ms, onValueChange = { ms = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth(), label = { Text("停顿毫秒") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    TextPreprocessor.setPunctOverride(p.pattern, ms.toIntOrNull() ?: p.ms, true, false)
                    editingPunct = null
                    reload()
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingPunct = null }) { Text("取消") } }
        )
    }

    // 添加 emoji 映射弹窗（右上角 ＋ 入口）
    if (addOpen) {
        var e by remember { mutableStateOf("") }
        var w by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { onCloseAdd() },
            title = { Text("添加 emoji 映射") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = e, onValueChange = { e = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("emoji") }, singleLine = true)
                    OutlinedTextField(value = w, onValueChange = { w = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("中文词") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val eTrim = e.trim(); val wTrim = w.trim()
                    if (eTrim.isNotEmpty() && wTrim.isNotEmpty()) {
                        TextPreprocessor.setEmojiOverride(eTrim, wTrim, true, false)
                        reload()
                        onCloseAdd()
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { onCloseAdd() }) { Text("取消") } }
        )
    }

    // 搜索弹窗（右上下 🔍 入口；跨 emoji + 标点）
    if (searchOpen) {
        var sq by remember { mutableStateOf("") }
        val sqTrim = sq.trim()
        val emojiHits = if (sqTrim.isEmpty()) emojis else emojis.filter { (em, w) -> em.contains(sqTrim) || w.contains(sqTrim) }
        val punctHits = if (sqTrim.isEmpty()) puncts else puncts.filter { it.name.contains(sqTrim) || it.pattern.contains(sqTrim) }
        AlertDialog(
            onDismissRequest = { onCloseSearch() },
            title = { Text("搜索映射") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sq, onValueChange = { sq = it },
                        modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索 emoji / 中文词 / 标点") },
                        singleLine = true)
                    if (emojiHits.isEmpty() && punctHits.isEmpty()) {
                        Text("无匹配映射", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(punctHits.size) { i ->
                                val p = punctHits[i]
                                RuleRow(
                                    text = "${p.name}   →  ${p.ms}ms",
                                    sub = p.pattern,
                                    enabled = p.enabled,
                                    onToggle = { TextPreprocessor.setPunctOverride(p.pattern, p.ms, it, false); reload() },
                                    onEdit = { editingPunct = p; onCloseSearch() },
                                    onDelete = { TextPreprocessor.setPunctOverride(p.pattern, p.ms, true, true); reload() },
                                    onPlay = { vm.playText("试听标点停顿。句号。逗号，省略号……") }
                                )
                            }
                            items(emojiHits.size) { i ->
                                val (em, w) = emojiHits[i]
                                RuleRow(
                                    text = "$em  →  $w",
                                    sub = "emoji",
                                    enabled = true,
                                    onToggle = { TextPreprocessor.setEmojiOverride(em, w, it, false); reload() },
                                    onEdit = { editingEmoji = em to w; onCloseSearch() },
                                    onDelete = { TextPreprocessor.setEmojiOverride(em, w, true, true); reload() },
                                    onPlay = { vm.playText("试听emoji：今天好开心$em 我们出发吧") }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onCloseSearch() }) { Text("关闭") } }
        )
    }
}

// ==================== 智能识别 ====================

/** 智能识别二级页 —— 等一下→停顿、大声说→音量（以多音字页为模板：顶部开关+搜索，规则可编辑/开关/删除/试听） */
@Composable
fun SmartContent(
    vm: MainViewModel,
    searchOpen: Boolean = false,
    addOpen: Boolean = false,
    onCloseSearch: () -> Unit = {},
    onCloseAdd: () -> Unit = {}
) {
    var enabled by remember { mutableStateOf(TextPreprocessor.isSmartEnabled()) }
    var rules by remember { mutableStateOf(TextPreprocessor.loadSmartRules()) }
    var searchQuery by remember { mutableStateOf("") }
    var testIn by remember { mutableStateOf("他大声说：等一下！我马上来。她小声说：别怕。") }
    var testOut by remember { mutableStateOf("") }
    var rulesCollapsed by remember { mutableStateOf(true) } // 大折叠：规则列表默认收起
    var editingRule by remember { mutableStateOf<TextPreprocessor.SmartRule?>(null) }

    fun reload() { rules = TextPreprocessor.loadSmartRules() }
    LaunchedEffect(Unit) { reload() }

    val q = searchQuery.trim()
    val filtered = if (q.isEmpty()) rules else rules.filter { it.label.contains(q) || it.regex.pattern.contains(q) }

    fun typeLabel(t: String) = when (t) {
        "pause" -> "停顿"
        "vol" -> "音量"
        "pit" -> "音调"
        else -> t
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard(
                title = "智能识别",
                subtitle = "识别口语与情绪关键词，自动注入停顿、音量与音调标记。"
            ) {
                ToggleRow("启用智能识别", "关闭后这些关键词不产生停顿/音量效果", enabled) { enabled = it; TextPreprocessor.setSmartEnabled(it) }
            }
        }

        // 测试（位于规则上方）
        item {
            SectionCard(title = "测试", subtitle = "输入含关键词的文本，预览注入的标记") {
                OutlinedTextField(value = testIn, onValueChange = { testIn = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("测试文本") })
                Spacer(Modifier.height(8.dp))
                Row {
                    AppPrimaryButton(text = "智能预览", onClick = {
                        testOut = TextPreprocessor.applySmartFeature(testIn)
                    }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    AppOutlinedButton(text = "试听", onClick = {
                        testOut = TextPreprocessor.applySmartFeature(testIn)
                        vm.playText(testOut)
                    }, modifier = Modifier.weight(1f))
                }
                if (testOut.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("注入标记后：", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(testOut, style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 4) 规则列表（大折叠置底，默认收起）
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = MaterialTheme.shapes.large
            ) {
                // 大折叠卡头
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
                            if (rulesCollapsed) "智能规则 ${filtered.size} · 点击展开"
                            else "智能规则 ${filtered.size} · 点击收起",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        // 规则列表展开内容（独立成卡，避免双层背景/阴影）
        if (!rulesCollapsed) {
            item {
                SectionCard(
                    title = "智能规则（${filtered.size}）",
                    subtitle = "命中关键词自动注入标记（音量/音调需引擎支持）。"
                ) {
                    if (filtered.isEmpty()) {
                        Text("无匹配的智能规则", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        filtered.forEach { r ->
                            val valueText = when (r.type) {
                                "pause" -> "${r.value.toInt()}ms"
                                "vol" -> "×${r.value}"
                                "pit" -> if (r.value >= 0) "+${r.value}" else "${r.value}"
                                else -> "${r.value}"
                            }
                            RuleRow(
                                text = "${r.label}   →  ${typeLabel(r.type)} $valueText",
                                sub = r.regex.pattern,
                                enabled = true,
                                onToggle = { TextPreprocessor.setSmartOverride(r.regex.pattern, r.value, r.label, it, false); reload() },
                                onEdit = { editingRule = r },
                                onDelete = { TextPreprocessor.setSmartOverride(r.regex.pattern, r.value, r.label, true, true); reload() },
                                onPlay = { vm.playText("试听智能识别：${r.label}，快点过来坐好。") }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("与对话功能联动：开启对话功能后，情绪规则可从声音分组切换发言人；本功能单独生效时为唯一发言人。",
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }

    editingRule?.let { r ->
        var newValue by remember { mutableStateOf(r.value.toString()) }
        var newLabel by remember { mutableStateOf(r.label) }
        AlertDialog(
            onDismissRequest = { editingRule = null },
            title = { Text("编辑智能规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = r.regex.pattern, onValueChange = {}, enabled = false,
                        modifier = Modifier.fillMaxWidth(), label = { Text("匹配正则") }, singleLine = true)
                    OutlinedTextField(value = newLabel, onValueChange = { newLabel = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("标签") }, singleLine = true)
                    OutlinedTextField(value = newValue, onValueChange = { newValue = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("参数（停顿ms / 音量倍率 / 音调值）") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    TextPreprocessor.setSmartOverride(r.regex.pattern, newValue.toFloatOrNull() ?: r.value, newLabel.trim().ifBlank { r.label }, true, false)
                    editingRule = null
                    reload()
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingRule = null }) { Text("取消") } }
        )
    }

    // 搜索弹窗（右上角 🔍 入口）
    if (searchOpen) {
        var sq by remember { mutableStateOf("") }
        val sqTrim = sq.trim()
        val hits = if (sqTrim.isEmpty()) rules else rules.filter { it.label.contains(sqTrim) || it.regex.pattern.contains(sqTrim) }
        AlertDialog(
            onDismissRequest = { onCloseSearch() },
            title = { Text("搜索智能规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sq, onValueChange = { sq = it },
                        modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索标签 / 正则") },
                        singleLine = true)
                    if (hits.isEmpty()) {
                        Text("无匹配规则", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(hits.size) { i ->
                                val r = hits[i]
                                val valueText = when (r.type) {
                                    "pause" -> "${r.value.toInt()}ms"
                                    "vol" -> "×${r.value}"
                                    "pit" -> if (r.value >= 0) "+${r.value}" else "${r.value}"
                                    else -> "${r.value}"
                                }
                                RuleRow(
                                    text = "${r.label}   →  ${typeLabel(r.type)} $valueText",
                                    sub = r.regex.pattern,
                                    enabled = true,
                                    onToggle = { TextPreprocessor.setSmartOverride(r.regex.pattern, r.value, r.label, it, false); reload() },
                                    onEdit = { editingRule = r; onCloseSearch() },
                                    onDelete = { TextPreprocessor.setSmartOverride(r.regex.pattern, r.value, r.label, true, true); reload() },
                                    onPlay = { vm.playText("试听智能识别：${r.label}，快点过来坐好。") }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onCloseSearch() }) { Text("关闭") } }
        )
    }
}

// ==================== 通用行组件（多音字模板风格） ====================

/** 规则行：主文本 + 副文本 + 试听/编辑/删除 + 开关（最右） */
@Composable
private fun RuleRow(
    text: String,
    sub: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit
) {
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
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                     color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sub, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary, maxLines = 1)
            }
            IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.PlayArrow, "试听", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, "编辑", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            }
            AppSwitch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}