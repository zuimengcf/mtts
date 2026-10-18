package com.mtts.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mtts.app.R
import com.mtts.app.engine.VoiceImportManager

/** 设置 Tab。 */
@Composable
fun SettingsScreen(vm: MainViewModel, detail: SettingsItem?) {
    if (detail == null) {
        SettingsListScreen(vm)
    } else {
        SettingsDetailScreen(vm, detail)
    }
}

/** 设置列表首页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsListScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text(ctx.getString(R.string.settings_title)) }) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(SettingsItem.values().toList(), key = { it.name }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    SettingsRowItem(
                        icon = settingsItemIcon(item),
                        title = ctx.settingsTitle(item),
                        desc = ctx.settingsDesc(item),
                        onClick = { vm.openSettingsItem(item) }
                    )
                }
            }
        }
    }
}

/** 设置子项详情页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDetailScreen(vm: MainViewModel, item: SettingsItem) {
    val ctx = LocalContext.current
    val uiState by vm.uiState.collectAsState()
    // 预处理二级页右上角弹窗状态（搜索 / 添加规则）
    var showSearchDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    val subPage = uiState.preprocessPage
    // 标题：进入预处理二级页时显示对应子页名称，否则显示设置项名
    val topTitle = if (item == SettingsItem.PREPROCESS && subPage != null) subPage.label
                   else ctx.settingsTitle(item)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(topTitle) },
                navigationIcon = {
                    IconButton(onClick = {
                        // 智能返回：二级页 → 该功能主页；功能主页 → 设置列表
                        if (uiState.preprocessPage != null) vm.backToPreprocessHome()
                        else if (uiState.dialogFeaturePage != null) vm.backToDialogFeatureHome()
                        else vm.backToSettingsList()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            ctx.getString(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    // 预处理二级页：右上角「搜索规则」入口（所有子页）
                    if (item == SettingsItem.PREPROCESS && subPage != null) {
                        IconButton(onClick = { showSearchDialog = true }) {
                            Icon(Icons.Default.Search, "搜索规则", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        // 「添加规则」：文字替换 / 多音字 / 表情符号 支持新增，智能识别为固定内置规则集不提供新增
                        if (subPage != PreprocessSubPage.SMART) {
                            IconButton(onClick = { showAddDialog = true }) {
                                Icon(Icons.Default.Add, "添加规则", tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            when (item) {
                SettingsItem.ABOUT -> AboutContent()
                SettingsItem.ENGINES -> EnginesContent(vm)
                SettingsItem.VOICE_PACKS -> VoicePacksContent(vm)
                SettingsItem.TEST_TEXT -> TestTextContent(vm)
                SettingsItem.LOGS -> LogsContent()
                SettingsItem.HTTP_SERVER -> HttpServerContent(vm)
SettingsItem.PREPROCESS -> {
                    // 预处理：默认显示主页；点功能卡片后显示对应二级页
                    if (subPage == null) {
                        PreprocessHome(vm)
                    } else {
                        when (subPage) {
                            PreprocessSubPage.REPLACE -> ReplaceContent(
                                vm,
                                searchOpen = showSearchDialog,
                                addOpen = showAddDialog,
                                onCloseSearch = { showSearchDialog = false },
                                onCloseAdd = { showAddDialog = false })
                            PreprocessSubPage.POLYPHONIC -> PolyphonicContent(vm, searchOpen = showSearchDialog, addOpen = showAddDialog, onCloseSearch = { showSearchDialog = false }, onCloseAdd = { showAddDialog = false })
                            PreprocessSubPage.EMOJI -> EmojiContent(vm, searchOpen = showSearchDialog, addOpen = showAddDialog, onCloseSearch = { showSearchDialog = false }, onCloseAdd = { showAddDialog = false })
                            PreprocessSubPage.SMART -> SmartContent(vm)
                        }
                    }
                }
                SettingsItem.SETTINGS_IO -> SettingsIoContent()
                SettingsItem.DIALOG_FEATURE -> {
                    // 对话功能：默认显示主页；点功能卡片后显示对应二级页
                    if (uiState.dialogFeaturePage == null) {
                        DialogFeatureHome(vm)
                    } else {
                        DialogFeaturePageContent(vm, uiState.dialogFeaturePage!!)
                    }
                }
                SettingsItem.MODEL_DOWNLOAD -> SherpaModelDownloadContent(vm)
            }
        }
    }
}

/** 关于 */
@Composable
private fun AboutContent() {
    val ctx = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(ctx.getString(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.padding(4.dp))
            Text(ctx.getString(R.string.app_tagline), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(8.dp))
            Text(ctx.getString(R.string.about_version, "0.1.0"), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.padding(8.dp))
            Text(ctx.getString(R.string.about_desc),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.padding(8.dp))
            val ghUrl = ctx.getString(R.string.about_github_url)
            Text(
                ctx.getString(R.string.about_github),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ghUrl))) }
            )
        }
    }
}

/** 引擎设置 */
@Composable
private fun EnginesContent(vm: MainViewModel) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    // sherpa 启动预热开关（默认开；关掉则首次合成时才加载模型，启动更快）
    var warmup by remember { mutableStateOf(com.mtts.app.util.AppPrefs.isSherpaWarmupEnabled()) }
    // 百度密钥对话框
    var showBdKeyDialog by remember { mutableStateOf(false) }
    Column {
        AppSectionTitle(ctx.getString(R.string.registered_engines))
        Spacer(Modifier.padding(8.dp))
        settings.engines.forEach { engine ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("${engine.name}  [${ctx.engineStatusLabel(engine.status)}]",
                         style = MaterialTheme.typography.bodyLarge)
                    Text(
                        ctx.getString(R.string.engine_code_cat, engine.code, ctx.engineCategoryLabel(engine.category)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
        Spacer(Modifier.padding(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启动预热 sherpa 模型", style = MaterialTheme.typography.bodyLarge)
                        Text("开启=启动时后台预加载全部模型（首次合成不卡）；关闭=首次合成时才加载，启动更快。下次启动 App 生效。",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.secondary)
                    }
                    androidx.compose.material3.Switch(
                        checked = warmup,
                        onCheckedChange = { on ->
                            warmup = on
                            com.mtts.app.util.AppPrefs.setSherpaWarmupEnabled(on)
                        }
                    )
                }
            }
        }
        // 百度引擎密钥配置（仅当 bdetts 已注册时显示）
        if (settings.engines.any { it.code == "bdetts" }) {
            Spacer(Modifier.padding(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("百度引擎授权密钥", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("9294715 / 37111357 模型需要填写 appKey 和 appSecret 才能合成。",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(8.dp))
                    val hasKey929 = com.mtts.app.util.AppPrefs.getBdAppKey("9294715").isNotBlank()
                    val hasKey371 = com.mtts.app.util.AppPrefs.getBdAppKey("37111357").isNotBlank()
                    Text(
                        if (hasKey929 || hasKey371) "已配置（9294715: ${if (hasKey929) "✓" else "✗"} · 37111357: ${if (hasKey371) "✓" else "✗"}）"
                        else "未配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasKey929 || hasKey371) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    AppOutlinedButton(
                        text = "配置密钥",
                        onClick = { showBdKeyDialog = true }
                    )
                }
            }
        }
    }

    if (showBdKeyDialog) {
        BdKeyConfigDialog(
            onDismiss = { showBdKeyDialog = false }
        )
    }
}

/** 百度引擎密钥配置对话框 */
@Composable
private fun BdKeyConfigDialog(onDismiss: () -> Unit) {
    var key929 by remember { mutableStateOf(com.mtts.app.util.AppPrefs.getBdAppKey("9294715")) }
    var secret929 by remember { mutableStateOf(com.mtts.app.util.AppPrefs.getBdAppSecret("9294715")) }
    var key371 by remember { mutableStateOf(com.mtts.app.util.AppPrefs.getBdAppKey("37111357")) }
    var secret371 by remember { mutableStateOf(com.mtts.app.util.AppPrefs.getBdAppSecret("37111357")) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("百度引擎授权密钥") },
        text = {
            Column {
                Text("模型 9294715", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = key929, onValueChange = { key929 = it },
                    label = { Text("appKey") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = secret929, onValueChange = { secret929 = it },
                    label = { Text("appSecret") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("模型 37111357", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = key371, onValueChange = { key371 = it },
                    label = { Text("appKey") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = secret371, onValueChange = { secret371 = it },
                    label = { Text("appSecret") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("密钥仅存储在本设备，不随仓库分发。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = {
            AppPrimaryButton(
                text = "保存",
                onClick = {
                    com.mtts.app.util.AppPrefs.setBdAppKey("9294715", key929)
                    com.mtts.app.util.AppPrefs.setBdAppSecret("9294715", secret929)
                    com.mtts.app.util.AppPrefs.setBdAppKey("37111357", key371)
                    com.mtts.app.util.AppPrefs.setBdAppSecret("37111357", secret371)
                    onDismiss()
                }
            )
        },
        dismissButton = {
            AppOutlinedButton(text = "取消", onClick = onDismiss)
        }
    )
}

/** 语音包 */
@Composable
private fun VoicePacksContent(vm: MainViewModel) {
    val context = LocalContext.current
    val ctx = context
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf(VoiceImportManager.importedPacks(context)) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        message = null
        error = null
        scope.launch {
            try {
                val n = withContext(Dispatchers.IO) { VoiceImportManager.importZip(context, uri) }
                packs = VoiceImportManager.importedPacks(context)
                message = ctx.getString(R.string.msg_import_done, n)
                vm.refreshEngines()
            } catch (e: Exception) {
                error = ctx.getString(R.string.msg_import_fail, e.message ?: "")
            } finally {
                importing = false
            }
        }
    }

    // 语音包导出：pendingExportPack 非 null 时，CreateDocument 回调会导出该包
    var pendingExportPack by remember { mutableStateOf<String?>(null) }
    val exportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        val packName = pendingExportPack ?: ""
        pendingExportPack = null
        if (uri == null || packName.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val packInfo = packs.find { it.name == packName }
                if (packInfo == null) {
                    error = "导出失败：找不到语音包 $packName"
                } else {
                    val result = withContext(Dispatchers.IO) {
                        com.mtts.app.util.SettingsBackup.exportPack(packInfo.path, uri)
                    }
                    result.onSuccess { msg -> message = "导出 $packName：$msg" }
                          .onFailure { err -> error = "导出 $packName 失败：${err.message}" }
                }
            } catch (e: Exception) {
                error = "导出 $packName 失败：${e.message}"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppSectionTitle(ctx.getString(R.string.voice_packs_title))
        Spacer(Modifier.padding(8.dp))

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(ctx.getString(R.string.voice_packs_desc_title), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.padding(4.dp))
                Text(
                    ctx.getString(R.string.voice_packs_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.padding(8.dp))
                AppPrimaryButton(
                    text = if (importing) ctx.getString(R.string.action_importing)
                           else ctx.getString(R.string.action_import_voice),
                    onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.Add,
                    enabled = !importing
                )
            }
        }

        message?.let {
            Spacer(Modifier.padding(8.dp))
            Text(it, color = MaterialTheme.colorScheme.primary,
                 style = MaterialTheme.typography.bodySmall)
        }
        error?.let {
            Spacer(Modifier.padding(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error,
                 style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.padding(12.dp))
        Text(ctx.getString(R.string.imported_packs), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.padding(8.dp))

        if (packs.isEmpty()) {
            EmptyStateCard(
                message = ctx.getString(R.string.no_packs),
                hint = ctx.getString(R.string.no_packs_hint)
            )
        } else {
            packs.forEach { pack ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(pack.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                ctx.getString(R.string.pack_files, pack.fileCount, pack.sizeMB.toString()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        IconButton(onClick = {
                            pendingExportPack = pack.name
                            exportPicker.launch("${pack.name}.zip")
                        }) {
                            Icon(Icons.Default.ExitToApp, "导出 ${pack.name}",
                                 tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = {
                            val removed = VoiceImportManager.deletePack(context, pack.name)
                            packs = VoiceImportManager.importedPacks(context)
                            message = if (removed) ctx.getString(R.string.msg_pack_deleted, pack.name)
                                      else ctx.getString(R.string.msg_pack_delete_fail)
                            vm.refreshEngines()
                        }) {
                            Icon(Icons.Default.Delete, ctx.getString(R.string.cd_delete_pack, pack.name),
                                 tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/** 日志 —— 实时显示 TTS 合成日志（输入文本 → 输出结果），参考 tts-server 的可视化日志 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsContent() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var logText by remember { mutableStateOf("") }
    var autoRefresh by remember { mutableStateOf(true) }

    // 读取今日日志（IO 线程），倒序显示：最新在最上
    fun load() {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                val raw = com.mtts.app.util.FileLog.readToday(ctx)
                // 按行倒序，最新的日志行排在最上方
                raw.trim().lines().reversed().joinToString("\n")
            }
            logText = text
        }
    }

    LaunchedEffect(Unit) { load() }

    // 自动刷新：每 2 秒拉取一次（同样倒序显示）
    LaunchedEffect(autoRefresh) {
        while (autoRefresh) {
            delay(2000)
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    val raw = com.mtts.app.util.FileLog.readToday(ctx)
                    raw.trim().lines().reversed().joinToString("\n")
                }
                logText = text
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppSectionTitle(ctx.getString(R.string.logs_title))
        Spacer(Modifier.padding(4.dp))
        Text(
            ctx.getString(R.string.logs_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.padding(8.dp))

        // 操作栏：自动开关 / 刷新 / 清空
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppOutlinedButton(
                text = if (autoRefresh) ctx.getString(R.string.logs_auto_refresh)
                       else ctx.getString(R.string.logs_auto_off),
                onClick = { autoRefresh = !autoRefresh },
                modifier = Modifier.weight(1f)
            )
            AppPrimaryButton(
                text = ctx.getString(R.string.action_refresh),
                onClick = { load() },
                modifier = Modifier.weight(1f)
            )
            AppOutlinedButton(
                text = ctx.getString(R.string.action_clear_log),
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val dir = java.io.File(ctx.filesDir, "logs")
                            dir.listFiles { _, name -> name.endsWith(".log") }?.forEach { it.delete() }
                        }
                        logText = ""
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.padding(4.dp))
        Text(
            ctx.getString(R.string.logs_file_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.padding(8.dp))

        // 日志内容
        Card(
            Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (logText.isBlank()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        ctx.getString(R.string.logs_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            } else {
                // 单色等宽字体逐行展示，便于对齐"输入文本→输出结果"
                Text(
                    text = logText,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                )
            }
        }
    }
}

/** 测试文本 —— 编辑内置测试的默认文本 */
@Composable
private fun TestTextContent(vm: MainViewModel) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(com.mtts.app.util.AppPrefs.getTestText()) }
    var saved by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        AppSectionTitle(ctx.getString(R.string.test_text_title))
        Spacer(Modifier.padding(8.dp))
        Text(ctx.getString(R.string.test_text_desc),
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.padding(12.dp))
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                saved = false
            },
            modifier = Modifier.fillMaxWidth().height(160.dp),
            label = { Text(ctx.getString(R.string.test_text_field_label)) }
        )
        Spacer(Modifier.padding(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            AppPrimaryButton(
                text = ctx.getString(R.string.action_save),
                onClick = {
                    com.mtts.app.util.AppPrefs.setTestText(text)
                    saved = true
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            AppOutlinedButton(
                text = ctx.getString(R.string.action_restore_default),
                onClick = {
                    text = com.mtts.app.util.AppPrefs.DEFAULT_TEST_TEXT
                    com.mtts.app.util.AppPrefs.setTestText(text)
                    saved = true
                },
                modifier = Modifier.weight(1f)
            )
        }
        if (saved) {
            Spacer(Modifier.padding(8.dp))
            Text(ctx.getString(R.string.saved), color = MaterialTheme.colorScheme.primary,
                 style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** HTTP 网关设置 —— 开关 + 端口号 */
@Composable
private fun HttpServerContent(vm: MainViewModel) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(com.mtts.app.util.AppPrefs.isHttpEnabled()) }
    var portText by remember { mutableStateOf(com.mtts.app.util.AppPrefs.httpPort().toString()) }
    var tokenText by remember { mutableStateOf(com.mtts.app.util.AppPrefs.httpToken()) }
    var saved by remember { mutableStateOf(false) }
    // 当前运行状态（实时刷新）
    var running by remember { mutableStateOf(com.mtts.app.server.LocalHttpServer.isRunning) }
    var actualPort by remember { mutableStateOf(com.mtts.app.server.LocalHttpServer.actualPort) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        AppSectionTitle("HTTP 网关")
        Spacer(Modifier.height(8.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp)) {
                // 开关
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用 HTTP 网关", style = MaterialTheme.typography.bodyLarge)
                        Text("关闭后不启动本地 HTTP 服务（外部无法调用引擎）", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.secondary)
                    }
                    androidx.compose.material3.Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            enabled = on
                            com.mtts.app.util.AppPrefs.setHttpEnabled(on)
                            saved = false
                        }
                    )
                }
                Spacer(Modifier.height(16.dp))
                // 端口号
                Text("端口号", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5); saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("1024 - 65535") },
                    singleLine = true
                )
                Spacer(Modifier.height(4.dp))
                Text("默认 8080。若被占用会自动回退到其后端口。保存并重启后生效。",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(16.dp))
                // 认证令牌
                Text("认证令牌（X-Auth-Token）", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it; saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Auth Token") },
                    singleLine = true
                )
                Spacer(Modifier.height(4.dp))
                Text("请求时需携带此令牌（如默认 mttsdev123），改后重启生效。",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    AppPrimaryButton(
                        text = if (enabled) "保存并重启" else "保存",
                        onClick = {
                            val p = portText.toIntOrNull()?.coerceIn(1024, 65535) ?: 8080
                            portText = p.toString()
                            com.mtts.app.util.AppPrefs.setHttpPort(p)
                            com.mtts.app.util.AppPrefs.setHttpToken(tokenText)
                            com.mtts.app.util.AppPrefs.setHttpEnabled(enabled)
                            if (enabled) {
                                try { com.mtts.app.server.LocalHttpServer.stop() } catch (_: Throwable) {}
                                com.mtts.app.server.LocalHttpServer.start(p)
                            } else {
                                try { com.mtts.app.server.LocalHttpServer.stop() } catch (_: Throwable) {}
                            }
                            running = com.mtts.app.server.LocalHttpServer.isRunning
                            actualPort = com.mtts.app.server.LocalHttpServer.actualPort
                            saved = true
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("当前状态", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("运行中：", style = MaterialTheme.typography.bodyMedium)
                    Text(if (running) "是" else "否",
                         style = MaterialTheme.typography.bodyMedium,
                         color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                if (running) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("端口：", style = MaterialTheme.typography.bodyMedium)
                        Text(actualPort.toString(), style = MaterialTheme.typography.bodyMedium,
                             fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("调用示例：GET http://<手机IP>:${actualPort.toString().ifBlank { "8080" }}/api/v1/status （需 X-Auth-Token）",
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        if (saved) {
            Spacer(Modifier.height(8.dp))
            Text("已保存", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun settingsItemIcon(item: SettingsItem): ImageVector = when (item) {
    SettingsItem.ABOUT -> Icons.Default.Info
    SettingsItem.ENGINES -> Icons.Default.Build
    SettingsItem.VOICE_PACKS -> Icons.Default.List
    SettingsItem.TEST_TEXT -> Icons.Default.TextFields
    SettingsItem.LOGS -> Icons.Default.List
    SettingsItem.HTTP_SERVER -> Icons.Default.Language
    SettingsItem.PREPROCESS -> Icons.Default.Spellcheck
    SettingsItem.SETTINGS_IO -> Icons.Default.FileOpen
    SettingsItem.DIALOG_FEATURE -> Icons.Default.Chat
    SettingsItem.MODEL_DOWNLOAD -> Icons.Default.FileOpen
}

/** 全局设置导入导出：除语音包外的全部 SharedPreferences + 替换规则 */
@Composable
fun SettingsIoContent() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    // 导出：SAF CreateDocument
    val exportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            msg = null; err = null
            try {
                val result = withContext(Dispatchers.IO) {
                    com.mtts.app.util.SettingsBackup.exportTo(uri)
                }
                result.onSuccess { m -> msg = m }
                      .onFailure { e -> err = "导出失败：${e.message}" }
            } catch (e: Exception) {
                err = "导出失败：${e.message}"
            }
        }
    }

    // 导入：SAF OpenDocument
    val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            msg = null; err = null
            try {
                val result = withContext(Dispatchers.IO) {
                    com.mtts.app.util.SettingsBackup.importFrom(uri)
                }
                result.onSuccess { m -> msg = m }
                      .onFailure { e -> err = "导入失败：${e.message}" }
            } catch (e: Exception) {
                err = "导入失败：${e.message}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("全局设置导入导出", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(6.dp))
                Text(
                    "备份/恢复除语音包外的全部设置：\n" +
                        "• 合成参数（全局 + 每音色）\n" +
                        "• 对话功能（旁白/角色/声音分组/情感）\n" +
                        "• 替换规则\n" +
                        "• 引擎选择、HTTP 网关、保活、测试文本等\n\n" +
                        "语音包本体资源请使用「语音包」页面的导出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        AppPrimaryButton(
            text = "导出全局设置",
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Default.ExitToApp,
            onClick = { exportPicker.launch("mtts_settings_backup.json") }
        )

        AppPrimaryButton(
            text = "导入全局设置",
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Default.FileOpen,
            onClick = { importPicker.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) }
        )

        msg?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        err?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Sherpa 模型下载 —— 扩展模型（Matcha / Kokoro / MeloTTS）在线安装 */
@Composable
private fun SherpaModelDownloadContent(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 各模型安装状态（重进页面时刷新）
    var installed by remember {
        mutableStateOf(
            com.mtts.app.engine.SherpaModelDownload.models.associate { m ->
                m.id to com.mtts.app.engine.SherpaModelDownload.isInstalled(ctx, m)
            }
        )
    }
    // 下载进度（0..1），空 = 未在下载
    var progress by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    // 信息提示（成功/失败）
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppSectionTitle("Sherpa 模型下载")
        Text(
            "内置 Piper 双音色已随 App 附带；以下扩展模型一键下载安装，安装后立即出现在音色列表。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )

        com.mtts.app.engine.SherpaModelDownload.models.forEach { model ->
            val isInstalled = installed[model.id] == true
            val prog = progress[model.id]
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(model.label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.padding(4.dp))
                    Text(model.desc, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.secondary)
                    if (isInstalled) {
                        Spacer(Modifier.padding(6.dp))
                        Text(
                            "已安装 · ${com.mtts.app.engine.SherpaModelDownload.installedSizeMB(ctx, model)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.padding(8.dp))
                    if (prog != null) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { prog },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.padding(4.dp))
                        Text("下载中…", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.secondary)
                    } else if (isInstalled) {
                        AppOutlinedButton(
                            text = "卸载",
                            onClick = {
                                com.mtts.app.engine.SherpaModelDownload.uninstall(ctx, model)
                                installed = installed + (model.id to false)
                                vm.refreshEngines()
                                message = "已卸载 ${model.label}"
                            },
                            enabled = true
                        )
                    } else {
                        AppPrimaryButton(
                            text = "下载安装",
                            onClick = {
                                progress = progress + (model.id to 0f)
                                message = null; error = null
                                scope.launch {
                                    try {
                                        val ok = withContext(Dispatchers.IO) {
                                            com.mtts.app.engine.SherpaModelDownload.install(ctx, model) { p ->
                                                progress = progress + (model.id to p)
                                            }
                                        }
                                        installed = installed + (model.id to true)
                                        message = ok
                                        vm.refreshEngines()
                                    } catch (e: Exception) {
                                        error = "${model.label} 下载失败：${e.message}"
                                    } finally {
                                        progress = progress - model.id
                                    }
                                }
                            },
                            enabled = true
                        )
                    }
                }
            }
        }

        message?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        error?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}