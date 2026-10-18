package com.mtts.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mtts.app.engine.EngineCategory
import com.mtts.app.engine.EngineRegistry
import com.mtts.app.engine.TtsEngine
import com.mtts.app.engine.Voice
import com.mtts.app.util.AppPrefs
import com.mtts.app.util.DialogCharacter
import com.mtts.app.util.DialogEmotion
import com.mtts.app.util.DialogFeature
import com.mtts.app.util.DialogNarrator
import com.mtts.app.util.DialogSeg
import com.mtts.app.util.DialogVoiceGroup
import com.mtts.app.util.DetectedRole
import com.mtts.app.util.HIGH_FREQ_THRESHOLD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 对话功能子页 */
enum class DialogFeaturePage(val label: String) {
    NARRATOR("旁白"),
    CHARACTER("角色"),
    VOICE_GROUP("声音分组"),
    EMOTION("情感")
}

/** 收集某引擎的全部音色（供发声人多选列表） */
private suspend fun collectEngineVoices(): List<Pair<TtsEngine, List<Voice>>> {
    val out = ArrayList<Pair<TtsEngine, List<Voice>>>()
    for (engine in EngineRegistry.all()) {
        val vs = withContext(Dispatchers.IO) {
            runCatching { engine.listVoices() }.getOrDefault(emptyList())
        }
        if (vs.isNotEmpty()) out.add(engine to vs)
    }
    return out
}

private fun dialogEngineCategoryIcon(category: EngineCategory) = when (category) {
    EngineCategory.ONLINE -> Icons.Default.Cloud
    EngineCategory.RULE -> Icons.Default.Rule
    EngineCategory.OFFLINE -> Icons.Default.AudioFile
}

/**
 * 引擎选择卡片 —— 对话功能内统一引擎选择器。
 * 卡头显示「已选/总数 (0/N)」+ 引擎名 + 展开箭头；默认收纳，点击展开竖向音色列表。
 * 展开区内内容（勾选/单选）由调用方通过 content 提供。
 */
@Composable
private fun DialogEnginePickerCard(
    engine: TtsEngine,
    voices: List<Voice>,
    selectedCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            // ===== 引擎卡头（点击展开/收起）=====
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(dialogEngineCategoryIcon(engine.category), null,
                     tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(engine.name, style = MaterialTheme.typography.titleMedium,
                         color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(1.dp))
                    Text("${voices.size} 个音色", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.secondary)
                }
                // 已选/总数：0/N
                Text(
                    "$selectedCount/${voices.size}",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selectedCount > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // ===== 展开：竖向音色列表 =====
            if (expanded) {
                Spacer(Modifier.height(2.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (voices.isEmpty()) {
                        Text("该引擎暂无音色", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.secondary,
                             modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                    } else {
                        content()
                    }
                }
            }
        }
    }
}

/** 对话功能主入口 —— 总开关 + 四个子功能卡片 */
@Composable
fun DialogFeatureHome(vm: MainViewModel) {
    var enabled by remember { mutableStateOf(AppPrefs.isDialogFeatureEnabled()) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "朗读时识别说话人并分配音色。各子功能独立开关、独立配置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 总开关
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用对话功能", style = MaterialTheme.typography.bodyLarge)
                    Text(if (enabled) "已启用：朗读时识别说话人并按规则分配音色"
                         else "关闭后整段用当前引擎朗读",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.secondary)
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    AppPrefs.setDialogFeatureEnabled(it)
                })
            }
        }

        // 旁白
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            SettingsRowItem(
                icon = Icons.Default.RecordVoiceOver,
                title = "旁白",
                desc = "为旁白叙述指定音色，可多选轮询朗读",
                onClick = { vm.openDialogFeaturePage(DialogFeaturePage.NARRATOR) }
            )
        }
        // 角色
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            SettingsRowItem(
                icon = Icons.Default.Person,
                title = "角色",
                desc = "识别说话人，让每个角色稳定用固定音色朗读",
                onClick = { vm.openDialogFeaturePage(DialogFeaturePage.CHARACTER) }
            )
        }
        // 声音分组
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            SettingsRowItem(
                icon = Icons.Default.People,
                title = "声音分组",
                desc = "按类型组织音色，让角色从分组装载声音",
                onClick = { vm.openDialogFeaturePage(DialogFeaturePage.VOICE_GROUP) }
            )
        }
        // 情感
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            SettingsRowItem(
                icon = Icons.Default.SentimentSatisfied,
                title = "情感",
                desc = "按匹配规则调整语速音量，或切换发言人",
                onClick = { vm.openDialogFeaturePage(DialogFeaturePage.EMOTION) }
            )
        }
    }
}

/** 对话功能二级页打包器（根据子页枚举渲染对应页面） */
@Composable
fun DialogFeaturePageContent(vm: MainViewModel, page: DialogFeaturePage) {
    when (page) {
        DialogFeaturePage.NARRATOR -> NarratorContent(vm)
        DialogFeaturePage.CHARACTER -> CharacterContent(vm)
        DialogFeaturePage.VOICE_GROUP -> VoiceGroupContent(vm)
        DialogFeaturePage.EMOTION -> EmotionContent(vm)
    }
}

// ==================== 旁白 ====================

/** 旁白页：多选引擎音色 → 旁白列表 */
@Composable
fun NarratorContent(vm: MainViewModel) {
    var narrators by remember { mutableStateOf(AppPrefs.loadNarrators()) }
    var allVoices by remember { mutableStateOf(emptyList<Pair<TtsEngine, List<Voice>>>()) }
    // 默认收纳：当前展开的引擎 code（null=全部收起）
    var expandedEng by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        allVoices = collectEngineVoices()
    }
    val currentKeys = narrators.map { "${it.engineCode}:${it.voice}" }.toSet()

    fun save(list: List<DialogNarrator>) {
        narrators = list
        AppPrefs.saveNarrators(list)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard(
                title = "旁白音色（多选）",
                subtitle = "为旁白叙述指定音色，多选则轮询分配。不选则用当前引擎默认音色。"
            ) {
                if (narrators.isEmpty()) {
                    Text("尚未选择旁白音色。", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                }
                narrators.forEachIndexed { i, n ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val eng = EngineRegistry.get(n.engineCode)
                        Text("旁白${i + 1}", style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n.voice.ifBlank { "未选" }, style = MaterialTheme.typography.bodyMedium)
                            Text(eng?.name ?: n.engineCode, style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.secondary)
                        }
                        IconButton(onClick = { save(narrators.filterIndexed { j, _ -> j != i }) }) {
                            Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        item {
            SectionCard(title = "选择引擎", subtitle = "按引擎选择旁白音色，可多选") {
                if (allVoices.isEmpty()) {
                    Text("暂无引擎", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        allVoices.forEach { (eng, voices) ->
                            val selectedCount = voices.count { v ->
                                "${eng.code}:${v.shortName}" in currentKeys
                            }
                            DialogEnginePickerCard(
                                engine = eng,
                                voices = voices,
                                selectedCount = selectedCount,
                                expanded = expandedEng == eng.code,
                                onToggle = { expandedEng = if (expandedEng == eng.code) null else eng.code }
                            ) {
                                voices.forEach { v ->
                                    val key = "${eng.code}:${v.shortName}"
                                    val checked = key in currentKeys
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = checked, onCheckedChange = {
                                            val updated = if (checked) narrators.filterNot { it.engineCode == eng.code && it.voice == v.shortName }
                                                          else narrators + DialogNarrator(eng.code, v.shortName)
                                            save(updated)
                                        })
                                        Column(Modifier.weight(1f)) {
                                            Text(v.name.ifBlank { v.shortName }, style = MaterialTheme.typography.bodyMedium)
                                            Text("${v.shortName} · ${v.gender.ifBlank { "未知" }}",
                                                 style = MaterialTheme.typography.bodySmall,
                                                 color = MaterialTheme.colorScheme.secondary)
                                        }
                                        IconButton(onClick = { vm.previewVoice(eng, v.shortName) },
                                                   modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Filled.PlayArrow, "试听${v.name}", tint = MaterialTheme.colorScheme.primary,
                                                 modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 声音分组 ====================

/** 声音分组页：按类型组织音色 */
@Composable
fun VoiceGroupContent(vm: MainViewModel) {
    var groups by remember { mutableStateOf(AppPrefs.loadVoiceGroups()) }
    var newName by remember { mutableStateOf("") }
    var newGender by remember { mutableStateOf("女") }
    var expandedIdx by remember { mutableStateOf(-1) }       // 展开的分组索引
    var expandedEng by remember { mutableStateOf<String?>(null) }  // 展开的引擎卡（null=全部收起）
    var allVoices by remember { mutableStateOf(emptyList<Pair<TtsEngine, List<Voice>>>()) }

    // 一次性加载所有引擎音色（供每个分组绑定）
    LaunchedEffect(Unit) {
        allVoices = collectEngineVoices()
        // 首次进入：无任何分组时自动补上内置 4 组，保证每个分组都能展开绑定音色
        if (groups.isEmpty()) {
            val builtin = listOf(
                DialogVoiceGroup("女", "女"),
                DialogVoiceGroup("男", "男"),
                DialogVoiceGroup("老", "老"),
                DialogVoiceGroup("少", "少")
            )
            groups = builtin
            AppPrefs.saveVoiceGroups(builtin)
        }
    }

    fun save(list: List<DialogVoiceGroup>) {
        groups = list
        AppPrefs.saveVoiceGroups(list)
    }

    fun updateGroup(idx: Int, updated: DialogVoiceGroup) {
        val list = groups.toMutableList()
        list[idx] = updated
        save(list)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard(
                title = "声音分组",
                subtitle = "按性别等类型组织音色。每个分组可勾选多个音色，角色朗读时按角色名稳定分配其中一个；未绑定则按性别自动挑选。女/男/老/少内置组同样可删除。"
            ) {
                if (groups.isEmpty()) {
                    Text("暂无分组。下方会自动提供 4 个内置分组。",
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                groups.forEachIndexed { i, g ->
                    val expanded = expandedIdx == i
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(g.name, style = MaterialTheme.typography.bodyMedium,
                                     color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text("· ${g.gender}", style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.secondary)
                                if (g.voices.isNotEmpty()) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("· ${g.voices.size} 个音色", style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.primary)
                                } else {
                                    Spacer(Modifier.width(6.dp))
                                    Text("· 未绑定（自动）", style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        IconButton(onClick = {
                            expandedIdx = if (expanded) -1 else i
                        }) {
                            Icon(
                                if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                if (expanded) "收起" else "绑定音色",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            val updated = groups.filterIndexed { j, _ -> j != i }
                            expandedIdx = -1
                            save(updated)
                        }) {
                            Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (expanded) {
                        Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 4.dp)) {
                            Text("勾选音色（可多选，角色朗读时按角色名稳定分配其中一个）",
                                 style = MaterialTheme.typography.labelMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            if (allVoices.isEmpty()) {
                                Text("未找到可用音色，请先导入语音包或检查引擎状态。",
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.error)
                            }
                            // 每个引擎卡片：多选取
                            allVoices.forEach { (eng, engVs) ->
                                val selCount = engVs.count { v ->
                                    g.voices.any { it.first == eng.code && it.second == v.shortName }
                                }
                                DialogEnginePickerCard(
                                    engine = eng,
                                    voices = engVs,
                                    selectedCount = selCount,
                                    expanded = expandedEng == eng.code,
                                    onToggle = {
                                        expandedEng = if (expandedEng == eng.code) null else eng.code
                                    }
                                ) {
                                    engVs.forEach { v ->
                                        val key = "${eng.code}:${v.shortName}"
                                        val checked = g.voices.any { it.first == eng.code && it.second == v.shortName }
                                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = checked, onCheckedChange = {
                                                val adding = !checked
                                                val newVoices = if (checked) {
                                                    g.voices.filterNot { it.first == eng.code && it.second == v.shortName }
                                                } else {
                                                    g.voices + (eng.code to v.shortName)
                                                }
                                                // ★ 性别自动检测：勾选音色时按其 gender 自动设置分组性别
                                                val autoGender = if (adding) {
                                                    when (v.gender.trim().lowercase()) {
                                                        "male", "男" -> "男"
                                                        "female", "女" -> "女"
                                                        else -> g.gender
                                                    }
                                                } else g.gender
                                                updateGroup(i, g.copy(voices = newVoices, gender = autoGender))
                                            })
                                            Column(Modifier.weight(1f)) {
                                                Text(v.name.ifBlank { v.shortName }, style = MaterialTheme.typography.bodyMedium)
                                                Text("${v.shortName} · ${v.gender.ifBlank { "未知" }}",
                                                     style = MaterialTheme.typography.bodySmall,
                                                     color = MaterialTheme.colorScheme.secondary)
                                            }
                                            IconButton(onClick = { vm.previewVoice(eng, v.shortName) },
                                                       modifier = Modifier.size(32.dp)) {
                                                Icon(Icons.Filled.PlayArrow, "试听${v.name}", tint = MaterialTheme.colorScheme.primary,
                                                     modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                            // 清除按钮（清除分组全部绑定，回到按性别自动挑选）
                            if (g.voices.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = { updateGroup(i, g.copy(voices = emptyList())) },
                                    modifier = Modifier.align(Alignment.End)) {
                                    Text("清除全部绑定（回到自动按性别）", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionCard(title = "新增分组", subtitle = "输入分组名并选择类型（创建后仍可在上方单独绑定音色）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        modifier = Modifier.weight(1f), label = { Text("分组名（如 中年女）") }, singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("女", "男", "老", "少").forEach { gen ->
                        FilterChip(
                            selected = newGender == gen,
                            onClick = { newGender = gen },
                            label = { Text(gen) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                AppPrimaryButton(
                    text = "＋ 新增分组",
                    onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty() && groups.none { it.name == name }) {
                            save(groups + DialogVoiceGroup(name, newGender))
                            newName = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), icon = Icons.Default.Add
                )
            }
        }
    }
}

// ==================== 角色 ====================

/** 角色页：识别说话人 → 分配固定音色（从分组装载/手动指定） */
@Composable
fun CharacterContent(vm: MainViewModel) {
    var characters by remember { mutableStateOf(AppPrefs.loadCharacters()) }
    var groups by remember { mutableStateOf(AppPrefs.loadVoiceGroups()) }
    var allVoices by remember { mutableStateOf(emptyList<Pair<TtsEngine, List<Voice>>>()) }
    var editingIdx by remember { mutableStateOf(-1) }
    var editing by remember { mutableStateOf<DialogCharacter?>(null) }
    // 自动识别角色
    var detectOpen by remember { mutableStateOf(false) }
    var detectText by remember { mutableStateOf("") }
    var detected by remember { mutableStateOf<List<DetectedRole>>(emptyList()) }
    var detectMsg by remember { mutableStateOf("") }
    // 识别结果勾选状态（index -> 是否勾选）
    var checkedIdx by remember { mutableStateOf(setOf<Int>()) }
    // 抽象角色（朗读积累中，未注册）
    var abstractRoles by remember { mutableStateOf(AppPrefs.loadAbstractRoles()) }
    // 识别进行中（jieba 首次加载词典较慢，避免主线程阻塞 → ANR）
    var detectLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { allVoices = collectEngineVoices() }

    fun save(list: List<DialogCharacter>) {
        characters = list
        AppPrefs.saveCharacters(list)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard(
                title = "角色（说话人）",
                subtitle = "配置说话人：名字 + 所属声音分组。朗读时识别说话人并分配对应音色，稳定不变。角色分【自动分配】（朗读满${HIGH_FREQ_THRESHOLD}次注册，按分组自动装载音色）与【手动分配】（手动指定固定音色）。"
            ) {
                if (characters.isEmpty()) {
                    Text("暂无正式角色。朗读时新说话人会先积累为抽象角色（满${HIGH_FREQ_THRESHOLD}次自动注册），也可点击下方「自动识别角色」批量生成。",
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                // ★ 只显示未屏蔽的角色；被屏蔽的收纳到底部折叠区
                characters.forEachIndexed { i, c ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.name, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (c.manual) "手动" else "自动",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (c.manual) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.secondary
                                    )
                                }
                                val eng = if (c.engineCode.isNotBlank()) EngineRegistry.get(c.engineCode) else null
                                Text(
                                    if (c.voice.isNotBlank()) "固定：${eng?.name ?: c.engineCode} / ${c.voice}"
                                    else "分组：${c.group.ifBlank { "未分组" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            TextButton(onClick = { editingIdx = i; editing = c }) { Text("编辑") }
                            TextButton(onClick = { save(characters.filterIndexed { j, _ -> j != i }) }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                // 抽象角色（朗读积累中）
                if (abstractRoles.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(4.dp))
Text("抽象角色（朗读积累中，满${HIGH_FREQ_THRESHOLD}次自动注册）",
                         style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                     abstractRoles.forEach { r ->
                         Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                             verticalAlignment = Alignment.CenterVertically) {
                             Text("${r.name}（${r.gender}）", Modifier.weight(1f),
                                  style = MaterialTheme.typography.bodySmall)
                             Text("${r.count}/${HIGH_FREQ_THRESHOLD}", style = MaterialTheme.typography.labelSmall,
                                  color = if (r.count >= HIGH_FREQ_THRESHOLD) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                TextButton(onClick = {
                    editingIdx = -1
                    editing = DialogCharacter(name = "新角色", group = groups.firstOrNull()?.name ?: "女", manual = false)
                }) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Text(" 新增角色")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 6.dp))
                TextButton(onClick = { detectOpen = true }) {
                    Icon(Icons.Default.People, null, tint = MaterialTheme.colorScheme.primary)
                    Text(" 自动识别角色（粘贴小说文本）")
                }
            }
        }
    }

    // 自动识别角色对话框
    if (detectOpen) {
        // 状态：无结果=「开始识别」；有结果=「加入角色」（单按钮状态切换）
        val hasResult = detected.isNotEmpty()
        val mainBtnText = when {
            detectLoading -> "识别中…"
            hasResult -> "加入角色"
            else -> "开始识别"
        }

        // 主按钮动作：无结果=开始识别；有结果=加入勾选角色
        fun detectAction() {
            if (detectLoading) return                       // 识别中，忽略
            if (hasResult) {
                // 加入勾选的角色（只添加勾选的，不重复添加已有角色）
                val toAdd = detected.filterIndexed { i, _ -> i in checkedIdx }
                if (toAdd.isEmpty()) {
                    detectMsg = "未勾选任何角色（可点「全选」或勾选具体角色）"
                    return
                }
                val existing = characters.map { it.name }.toSet()
                val newOnes = toAdd.filter { it.name.isNotBlank() && it.name !in existing }
                    .map { DialogCharacter(name = it.name, group = it.gender, manual = false) }
                save(characters + newOnes)
                // 清掉已添加的，保留未勾选的供再次选择
                detected = detected.filterIndexed { i, _ -> i !in checkedIdx }
                checkedIdx = detected.mapIndexedNotNull { i, d ->
                    if (d.count >= HIGH_FREQ_THRESHOLD) i else null
                }.toSet()
                detectMsg = "已加入 ${newOnes.size} 个角色，剩余 ${detected.size} 个未添加"
                return
            }
            // 首次点击：开始识别
            if (detectText.isBlank()) {
                detectMsg = "请先粘贴一段含对话的小说文本。"
                return
            }
            // ★ 后台执行：jieba 首次加载词典 ~2MB（应用启动已后台预热），
            //   仍在 Dispatchers.IO 跑，保证不阻塞主线程（曾因此 ANR）
            detectLoading = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        DialogFeature.autoDetectCharacters(detectText)
                    }
                    detected = result
                    // ★ 只默认勾选高频项（出现 ≥10 次，与朗读积累注册阈值统一），
                    //   低频项多为误判/短语，留待用户手动勾选
                    checkedIdx = result.mapIndexedNotNull { i, d ->
                        if (d.count >= HIGH_FREQ_THRESHOLD) i else null
                    }.toSet()
                    detectMsg = if (result.isEmpty()) "未识别到说话人，请确认文本含「XX说/问/道」句式。"
                                else "识别到 ${checkedIdx.size}/${result.size} 个角色（默认只勾选出现≥${HIGH_FREQ_THRESHOLD}次的高频项），点「加入角色」添加勾选的。"
                } catch (e: Throwable) {
                    detectMsg = "识别失败：${e.message}"
                } finally {
                    detectLoading = false
                }
            }
        }

        AlertDialog(
            onDismissRequest = { detectOpen = false },
            title = { Text("自动识别角色") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("粘贴一段含对话的小说文本，自动识别「XX说/问/道」的说话人。识别后勾选需要的角色，再点「加入角色」批量添加。",
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = detectText, onValueChange = { detectText = it },
                        label = { Text("小说文本") }, minLines = 5, maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (detectMsg.isNotBlank()) {
                        Text(detectMsg, style = MaterialTheme.typography.bodySmall,
                             color = if (detected.isEmpty() && !hasResult) MaterialTheme.colorScheme.error
                                     else MaterialTheme.colorScheme.primary)
                    }
                    // 批量操作：全选 / 全不选 / 只留高频
                    if (detected.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { checkedIdx = detected.indices.toSet() },
                                       modifier = Modifier.weight(1f)) { Text("全选") }
                            TextButton(onClick = { checkedIdx = emptySet() },
                                       modifier = Modifier.weight(1f)) { Text("全不选") }
                            TextButton(onClick = {
                                // 只勾选出现 ≥10 次的高频角色（误判多为低频短语）
                                checkedIdx = detected.mapIndexedNotNull { i, d ->
                                    if (d.count >= HIGH_FREQ_THRESHOLD) i else null
                                }.toSet()
                            }, modifier = Modifier.weight(1f)) { Text("只留高频(≥${HIGH_FREQ_THRESHOLD}次)") }
                        }
                    }
                    // 识别结果列表：勾选 + 人名 + 命中规则 + 出现次数（权重）
                    detected.forEachIndexed { i, d ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = i in checkedIdx,
                                onCheckedChange = {
                                    checkedIdx = if (i in checkedIdx) checkedIdx - i else checkedIdx + i
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(detected[i].name, style = MaterialTheme.typography.bodyLarge,
                                     color = MaterialTheme.colorScheme.onSurface)
                                Text("命中：${detected[i].rule} · 出现 ${detected[i].count} 次",
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.secondary)
                            }
                            IconButton(onClick = {
                                detected = detected.filterIndexed { j, _ -> j != i }
                                checkedIdx = checkedIdx.mapNotNull { if (it == i) null else if (it > i) it - 1 else it }.toSet()
                            }) {
                                Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { detectAction() }, enabled = !detectLoading) {
                    if (detectLoading) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(mainBtnText)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    detectOpen = false
                    detected = emptyList()
                    checkedIdx = emptySet()
                    detectMsg = ""
                    detectText = ""
                }) { Text("关闭") }
            }
        )
    }

    // 角色编辑对话框
    editing?.let { cur ->
        var name by remember { mutableStateOf(cur.name) }
        var group by remember { mutableStateOf(cur.group) }
        var engineCode by remember { mutableStateOf(cur.engineCode) }
        var voice by remember { mutableStateOf(cur.voice) }
        var useFixed by remember { mutableStateOf(cur.voice.isNotBlank() || cur.engineCode.isNotBlank()) }
        // 手动指定音色时：当前展开的引擎卡（默认收；编辑已有固定音色时默认展开该引擎）
        var fixedExpandedEng by remember { mutableStateOf(if (useFixed) { engineCode } else null) }

        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (editingIdx == -1) "新增角色" else "编辑角色") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("角色名（如 莉可 / 苍宫月）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    // 选择声音分组
                    Text("声音分组", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (groups.ifEmpty { listOf(DialogVoiceGroup("女", "女"), DialogVoiceGroup("男", "男"), DialogVoiceGroup("老", "老"), DialogVoiceGroup("少", "少")) })
                            .forEach { g ->
                                FilterChip(selected = group == g.name, onClick = { group = g.name }, label = { Text(g.name) })
                            }
                    }
                    // 手动指定固定音色 开关（打开后该角色转为「手动分配」；关闭=自动分配按分组装载）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = useFixed, onCheckedChange = { useFixed = it })
                        Spacer(Modifier.width(8.dp))
                        Text("手动指定固定音色（转为手动分配）", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (useFixed) {
                        // 引擎 + 音色（卡片式：默认收纳，点击展开竖列单选）
                        Text("选择引擎", style = MaterialTheme.typography.titleSmall)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            allVoices.forEach { (eng, engVs) ->
                                DialogEnginePickerCard(
                                    engine = eng,
                                    voices = engVs,
                                    selectedCount = if (engineCode == eng.code && voice.isNotBlank()) 1 else 0,
                                    expanded = fixedExpandedEng == eng.code,
                                    onToggle = {
                                        fixedExpandedEng = if (fixedExpandedEng == eng.code) null else eng.code
                                    }
                                ) {
                                    engVs.forEach { v ->
                                        val isSel = voice == v.shortName && engineCode == eng.code
                                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = isSel, onCheckedChange = {
                                                engineCode = eng.code
                                                voice = v.shortName
                                                // ★ 性别自动检测：选中音色时按其 gender 自动切换对应分组
                                                when (v.gender.trim().lowercase()) {
                                                    "male", "男" -> group = "男"
                                                    "female", "女" -> group = "女"
                                                }
                                            })
                                            Column(Modifier.weight(1f)) {
                                                Text(v.name.ifBlank { v.shortName }, style = MaterialTheme.typography.bodyMedium)
                                                Text("${v.shortName} · ${v.gender.ifBlank { "未知" }}",
                                                     style = MaterialTheme.typography.bodySmall,
                                                     color = MaterialTheme.colorScheme.secondary)
                                            }
                                            IconButton(onClick = { vm.previewVoice(eng, v.shortName) },
                                                       modifier = Modifier.size(32.dp)) {
                                                Icon(Icons.Filled.PlayArrow, "试听${v.name}", tint = MaterialTheme.colorScheme.primary,
                                                     modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (group.isNotBlank() && !useFixed) {
                        Text("将按分组「$group」的性别自动装载音色（女→Female、男/老→Male）。",
                             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val fixedNew = if (useFixed) {
                        // 手动指定固定音色 → 标记为「手动分配」（此后用户对该角色就是手动管理）
                        val engCode = engineCode.ifBlank { allVoices.firstOrNull()?.first?.code ?: "microsoft" }
                        DialogCharacter(name = name.trim().ifBlank { "未命名" }, group = group,
                            engineCode = engCode, voice = voice, manual = true)
                    } else {
                        // 仅选分组 → 保持「自动分配」（按分组自动装载音色）
                        DialogCharacter(name = name.trim().ifBlank { "未命名" }, group = group, manual = false)
                    }
                    val updated = if (editingIdx == -1) characters + fixedNew
                                  else characters.mapIndexed { i, c -> if (i == editingIdx) fixedNew else c }
                    save(updated)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}

// ==================== 情感 ====================

/** 情感页：正则规则 → 调速/换发言人 */
@Composable
fun EmotionContent(vm: MainViewModel) {
    var emotions by remember { mutableStateOf(AppPrefs.loadEmotions()) }
    var allVoices by remember { mutableStateOf(emptyList<Pair<TtsEngine, List<Voice>>>()) }
    var testIn by remember { mutableStateOf("床前明月光，疑是地上霜。举头望明月，低头思故乡。") }
    var testOut by remember { mutableStateOf<List<DialogSeg>>(emptyList()) }
    var editingIdx by remember { mutableStateOf(-1) }
    var editing by remember { mutableStateOf<DialogEmotion?>(null) }

    LaunchedEffect(Unit) { allVoices = collectEngineVoices() }

    fun save(list: List<DialogEmotion>) {
        emotions = list
        AppPrefs.saveEmotions(list)
    }
    fun runTest() {
        val defaultEng = allVoices.firstOrNull()?.first?.code ?: "microsoft"
        val defaultVoice = allVoices.firstOrNull()?.second?.firstOrNull()?.shortName ?: ""
        testOut = DialogFeature.preview(testIn, { ec ->
            allVoices.firstOrNull { it.first.code == ec }?.second ?: emptyList()
        }, defaultEng, defaultVoice)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard(
                title = "情感规则",
                subtitle = "用正则匹配文本特征，命中后调整语速/音量/音调，或切换发言人。"
            ) {
                if (emotions.isEmpty()) {
                    Text("暂无情感规则。点击「新增情感」添加。",
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                emotions.forEachIndexed { i, e ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.name, style = MaterialTheme.typography.bodyLarge)
                                Text("正则: ${e.regex.ifBlank { "(空)" }}  ·  语速 ×${e.speed} 音量 ×${e.volume}",
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace)
                            }
                            TextButton(onClick = { editingIdx = i; editing = e }) { Text("编辑") }
                            TextButton(onClick = { save(emotions.filterIndexed { j, _ -> j != i }) }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                TextButton(onClick = {
                    editingIdx = -1
                    editing = DialogEmotion(name = "新情感", regex = "床前明月光", speed = 0.7f)
                }) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Text(" 新增情感")
                }
            }
        }
        item {
            SectionCard(title = "测试", subtitle = "输入文本，预览情感规则命中结果") {
                OutlinedTextField(value = testIn, onValueChange = { testIn = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("测试文本") })
                Spacer(Modifier.height(8.dp))
                Row {
                    AppPrimaryButton(text = "预览", onClick = { runTest() }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    AppOutlinedButton(text = "试听", onClick = {
                        runTest()
                        if (testOut.isNotEmpty()) {
                            val first = testOut.firstOrNull { it.isSpeech } ?: testOut.first()
                            vm.playText(first.text)
                        }
                    }, modifier = Modifier.weight(1f))
                }
                if (testOut.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("结果：", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(2.dp))
                    testOut.forEach { seg ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (seg.isSpeech) "对白" else "旁白",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = if (seg.isSpeech) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                 modifier = Modifier.width(48.dp))
                            if (seg.speaker.isNotBlank()) {
                                Text(seg.speaker, style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.width(64.dp))
                            }
                            Text(seg.text, style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    editing?.let { cur ->
        var name by remember { mutableStateOf(cur.name) }
        var regex by remember { mutableStateOf(cur.regex) }
        var speed by remember { mutableStateOf(cur.speed.toString()) }
        var volume by remember { mutableStateOf(cur.volume.toString()) }
        var pitch by remember { mutableStateOf(cur.pitch.toString()) }
        var engineCode by remember { mutableStateOf(cur.engineCode) }
        var voice by remember { mutableStateOf(cur.voice) }
        var useVoice by remember { mutableStateOf(cur.voice.isNotBlank() || cur.engineCode.isNotBlank()) }
        // 切换发言人时：当前展开的引擎卡（默认收；编辑已有固定音色时默认展开该引擎）
        var emotionExpandedEng by remember { mutableStateOf(if (useVoice) { engineCode } else null) }

        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (editingIdx == -1) "新增情感" else "编辑情感") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("名称（如 诗句）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = regex, onValueChange = { regex = it },
                        label = { Text("匹配正则（如 床前明月光|疑似地上霜）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("语速 ×", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(value = speed, onValueChange = { speed = it },
                            modifier = Modifier.width(90.dp).height(56.dp),
                            label = { Text("<1 慢 >1 快") }, singleLine = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("音量 ×", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(value = volume, onValueChange = { volume = it },
                            modifier = Modifier.width(90.dp).height(56.dp), singleLine = true)
                        Spacer(Modifier.width(8.dp))
                        Text("音调", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(value = pitch, onValueChange = { pitch = it },
                            modifier = Modifier.width(90.dp).height(56.dp), singleLine = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = useVoice, onCheckedChange = { useVoice = it })
                        Spacer(Modifier.width(8.dp))
                        Text("切换发言人", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (useVoice) {
                        Text("选择引擎", style = MaterialTheme.typography.titleSmall)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            allVoices.forEach { (eng, engVs) ->
                                DialogEnginePickerCard(
                                    engine = eng,
                                    voices = engVs,
                                    selectedCount = if (engineCode == eng.code && voice.isNotBlank()) 1 else 0,
                                    expanded = emotionExpandedEng == eng.code,
                                    onToggle = {
                                        emotionExpandedEng = if (emotionExpandedEng == eng.code) null else eng.code
                                    }
                                ) {
                                    engVs.forEach { v ->
                                        val isSel = voice == v.shortName && engineCode == eng.code
                                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = isSel, onCheckedChange = {
                                                engineCode = eng.code
                                                voice = v.shortName
                                            })
                                            Column(Modifier.weight(1f)) {
                                                Text(v.name.ifBlank { v.shortName }, style = MaterialTheme.typography.bodyMedium)
                                                Text("${v.shortName} · ${v.gender.ifBlank { "未知" }}",
                                                     style = MaterialTheme.typography.bodySmall,
                                                     color = MaterialTheme.colorScheme.secondary)
                                            }
                                            IconButton(onClick = { vm.previewVoice(eng, v.shortName) },
                                                       modifier = Modifier.size(32.dp)) {
                                                Icon(Icons.Filled.PlayArrow, "试听${v.name}", tint = MaterialTheme.colorScheme.primary,
                                                     modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val eNew = DialogEmotion(
                        name = name.trim().ifBlank { "未命名" },
                        regex = regex.trim(),
                        engineCode = if (useVoice) engineCode else "",
                        voice = if (useVoice) voice else "",
                        speed = speed.toFloatOrNull() ?: 1f,
                        volume = volume.toFloatOrNull() ?: 1f,
                        pitch = pitch.toFloatOrNull() ?: 0f
                    )
                    val updated = if (editingIdx == -1) emotions + eNew
                                  else emotions.mapIndexed { i, e -> if (i == editingIdx) eNew else e }
                    save(updated)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}