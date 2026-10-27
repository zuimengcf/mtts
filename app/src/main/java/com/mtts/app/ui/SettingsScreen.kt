package com.mtts.app.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
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
                SettingsItem.SOUND_FX -> SoundFxContent(vm)
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
                SettingsItem.SETTINGS_IO -> SettingsIoContent(vm)
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
    // 微软嵌入式 license 对话框
    var showMsLicenseDialog by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
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
        // 微软嵌入式授权 license（仅当 microsoft 引擎已注册时显示）
        if (settings.engines.any { it.code == "microsoft" }) {
            Spacer(Modifier.padding(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("微软嵌入式授权 license", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("离线合成需填微软嵌入式语音授权 license。密钥仅存本设备，不随仓库分发。",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(8.dp))
                    val hasMsLic = com.mtts.app.util.AppPrefs.getMsLicense().isNotBlank()
                    Text(
                        if (hasMsLic) "已配置" else "未配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasMsLic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    AppOutlinedButton(
                        text = if (hasMsLic) "修改 license" else "填写 license",
                        onClick = { showMsLicenseDialog = true }
                    )
                }
            }
        }
    }

    if (showMsLicenseDialog) {
        MsLicenseConfigDialog(
            onDismiss = { showMsLicenseDialog = false }
        )
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

/** 微软嵌入式授权 license 配置对话框 */
@Composable
private fun MsLicenseConfigDialog(onDismiss: () -> Unit) {
    var lic by remember { mutableStateOf(com.mtts.app.util.AppPrefs.getMsLicense()) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("微软嵌入式授权 license") },
        text = {
            Column {
                OutlinedTextField(
                    value = lic, onValueChange = { lic = it },
                    label = { Text("license") }, singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("离线合成需填的嵌入式语音授权 license。仅存本设备，不随仓库分发。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = {
            AppPrimaryButton(
                text = "保存",
                onClick = {
                    com.mtts.app.util.AppPrefs.setMsLicense(lic)
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
                Text("请求时需携带此令牌。首次使用自动生成；清空并保存可重新生成。启用后局域网可访问，请勿泄露令牌。",
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
                            tokenText = com.mtts.app.util.AppPrefs.httpToken()
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
    SettingsItem.SOUND_FX -> Icons.Default.Search
    SettingsItem.PREPROCESS -> Icons.Default.Spellcheck
    SettingsItem.SETTINGS_IO -> Icons.Default.FileOpen
    SettingsItem.DIALOG_FEATURE -> Icons.Default.Chat
    SettingsItem.MODEL_DOWNLOAD -> Icons.Default.FileOpen
}

/** 全局设置导入导出：除语音包外的全部 SharedPreferences + 替换规则 */
@Composable
fun SettingsIoContent(vm: MainViewModel) {
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
                result.onSuccess { m ->
                          msg = m
                          vm.reloadAfterSettingsImport()
                      }.onFailure { e -> err = "导入失败：${e.message}" }
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

/** 音效设置 —— BGM（长音频）+ 短音效（标记触发 / 试听） */
@Composable
private fun SoundFxContent(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 配置状态
    var bgmEnabled by remember { mutableStateOf(com.mtts.app.util.AppPrefs.isBgmEnabled()) }
    var bgmPath by remember { mutableStateOf(com.mtts.app.util.AppPrefs.getBgmPath()) }
    var bgmVolume by remember { mutableStateOf(com.mtts.app.util.AppPrefs.getBgmVolume()) }
    var sfxEnabled by remember { mutableStateOf(com.mtts.app.util.AppPrefs.isSfxEnabled()) }
    var sfxVolume by remember { mutableStateOf(com.mtts.app.util.AppPrefs.getSfxVolume()) }
    var sfxEntries by remember { mutableStateOf(com.mtts.app.util.AppPrefs.loadSfxMap()) }
    var kwEntries by remember { mutableStateOf(com.mtts.app.util.AppPrefs.loadSfxKeywords()) }

    // 文件选择：BGM（pending="bgm"）或音效（pending=音效名）
    var pendingPick by remember { mutableStateOf<String?>(null) }
    val audioPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val target = pendingPick
        pendingPick = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                // 持久化读权限（重启后仍可读）
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // 复制到应用私有目录（content:// 可能失效，复制更稳）
                val dir = java.io.File(ctx.filesDir, "soundfx").apply { mkdirs() }
                val name = (target.ifBlank { "bgm" }).replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_")
                val ext = guessAudioExt(uri)
                val dest = java.io.File(dir, "${name}_${System.currentTimeMillis()}.$ext")
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw RuntimeException("无法读取所选文件")
                }
                if (target == "bgm") {
                    bgmPath = dest.absolutePath
                    com.mtts.app.util.AppPrefs.setBgmPath(bgmPath)
                    com.mtts.app.util.SoundEffectManager.clearCache()
                } else {
                    // 更新音效注册表
                    val newEntries = sfxEntries.filter { it.first != target } + (target to dest.absolutePath)
                    sfxEntries = newEntries
                    com.mtts.app.util.AppPrefs.saveSfxMap(newEntries)
                    com.mtts.app.util.SoundEffectManager.clearCache()
                }
            } catch (e: Exception) {
                com.mtts.app.util.MttsLog.w("SoundFx", "选择音频失败: ${e.message}")
            }
        }
    }
    // 添加音效对话框
    var showAddSfx by remember { mutableStateOf(false) }
    // 编辑音效（存原标记名）
    var editingSfx by remember { mutableStateOf<String?>(null) }
    // 编辑关键词（存原 kw to name）
    var editingKw by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 添加关键词对话框
    var showAddKw by remember { mutableStateOf(false) }
    // 折叠 + 搜索（注册表 / 关键词表）
    var sfxListOpen by remember { mutableStateOf(false) }
    var sfxQuery by remember { mutableStateOf("") }
    var kwListOpen by remember { mutableStateOf(false) }
    var kwQuery by remember { mutableStateOf("") }
    // 例句试听
    var sfxTestText by remember { mutableStateOf("外面下着雨，雷声轰鸣。他踩着草地脚步走近，敲了敲门。屋里传来狗叫和猫叫。") }

    // 批量导入：多选音频 → 文件名即标记名，复制到私有目录并注册
    var batchImporting by remember { mutableStateOf(false) }
    var batchMsg by remember { mutableStateOf("") }
    val batchSfxPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        batchImporting = true
        batchMsg = ""
        scope.launch {
            var ok = 0
            val failed = ArrayList<String>()
            withContext(Dispatchers.IO) {
                val dir = java.io.File(ctx.filesDir, "soundfx").apply { mkdirs() }
                val entries = com.mtts.app.util.AppPrefs.loadSfxMap().toMutableList()
                for (uri in uris) {
                    try {
                        runCatching {
                            ctx.contentResolver.takePersistableUriPermission(
                                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        // 文件名（去扩展名）即标记名，清理非法字符
                        val raw = queryDisplayName(ctx, uri)
                            ?: uri.lastPathSegment ?: "sfx_${System.currentTimeMillis()}"
                        val name = raw.substringBeforeLast('.')
                            .replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_").ifBlank { "sfx" }
                        val ext = guessAudioExt(uri)
                        val dest = java.io.File(dir, "${name}_${System.currentTimeMillis()}.$ext")
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw RuntimeException("无法读取 $raw")
                        if (dest.length() > 0L) {
                            entries.removeAll { it.first == name }
                            entries.add(name to dest.absolutePath)
                            ok++
                        } else throw RuntimeException("空文件")
                    } catch (e: Exception) {
                        failed.add(uri.lastPathSegment ?: "?")
                    }
                }
                com.mtts.app.util.AppPrefs.saveSfxMap(entries)
            }
            batchImporting = false
            batchMsg = if (failed.isEmpty()) "已导入 $ok 个音效"
                       else "导入 $ok 个成功，${failed.size} 个失败"
            if (ok > 0) com.mtts.app.util.SoundEffectManager.clearCache()
            // 刷新注册表显示
            sfxEntries = com.mtts.app.util.AppPrefs.loadSfxMap()
        }
    }


    Column(Modifier.verticalScroll(rememberScrollState())) {
        AppSectionTitle("背景音乐 BGM")
        Spacer(Modifier.padding(8.dp))

        // BGM 开关
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用背景音乐", style = MaterialTheme.typography.bodyLarge)
                        Text("所有 TTS 输出循环混入 BGM，直到朗读结束。",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.secondary)
                    }
                    androidx.compose.material3.Switch(
                        checked = bgmEnabled,
                        onCheckedChange = { on ->
                            bgmEnabled = on
                            com.mtts.app.util.AppPrefs.setBgmEnabled(on)
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "BGM 文件：" + if (bgmPath.isBlank()) "未选择"
                    else java.io.File(bgmPath).name,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bgmPath.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppPrimaryButton(
                        text = if (bgmPath.isBlank()) "选择 BGM 文件" else "更换 BGM",
                        onClick = { pendingPick = "bgm"; audioPicker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.weight(1f)
                    )
                    if (bgmPath.isNotBlank()) {
                        AppOutlinedButton(
                            text = "清除",
                            onClick = {
                                bgmPath = ""
                                com.mtts.app.util.AppPrefs.setBgmPath("")
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("BGM 音量：${"%.0f".format(bgmVolume * 100)}%",
                     style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.Slider(
                    value = bgmVolume,
                    onValueChange = {
                        bgmVolume = it
                        com.mtts.app.util.AppPrefs.setBgmVolume(it)
                        com.mtts.app.util.SoundEffectManager.bgmVolume = it
                    },
                    valueRange = 0f..1f
                )
            }
        }

        Spacer(Modifier.padding(16.dp))
        AppSectionTitle("短音效")
        Spacer(Modifier.padding(8.dp))

        // 音效开关 + 音量
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用音效", style = MaterialTheme.typography.bodyLarge)
                        Text("文本中 ⟨sfx:名字⟩ 标记处混入对应音效。",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.secondary)
                    }
                    androidx.compose.material3.Switch(
                        checked = sfxEnabled,
                        onCheckedChange = { on ->
                            sfxEnabled = on
                            com.mtts.app.util.AppPrefs.setSfxEnabled(on)
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("音效音量：${"%.0f".format(sfxVolume * 100)}%",
                     style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.Slider(
                    value = sfxVolume,
                    onValueChange = {
                        sfxVolume = it
                        com.mtts.app.util.AppPrefs.setSfxVolume(it)
                        com.mtts.app.util.SoundEffectManager.sfxVolume = it
                    },
                    valueRange = 0f..1f
                )
            }
        }

        Spacer(Modifier.padding(12.dp))
        // 例句试听
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("例句试听", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text("输入含关键词的文本，试听音效自动注入后的完整效果。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = sfxTestText,
                    onValueChange = { sfxTestText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("输入测试文本") }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppPrimaryButton(
                        text = "试听",
                        onClick = {
                            val names = vm.effectiveSfxAt(sfxTestText).joinToString("、") { it.name }
                            if (names.isNotBlank()) {
                                android.widget.Toast.makeText(ctx, "触发音效：$names",
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                            vm.playText(sfxTestText)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.padding(12.dp))
        // 音效注册表（卡片只包标题行，展开内容在卡外，避免高卡片阴影错位）
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clickable { sfxListOpen = !sfxListOpen }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("音效注册表", style = MaterialTheme.typography.bodyLarge)
                    Text("标记名绑定音频文件。文本里写 ⟨sfx:标记名⟩ 即在该处混入。共 ${sfxEntries.size} 条。",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.secondary)
                }
                Icon(
                    if (sfxListOpen) androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp
                    else androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                    if (sfxListOpen) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        AppOutlinedButton(text = "添加音效", onClick = { showAddSfx = true }, modifier = Modifier.fillMaxWidth())
        if (sfxListOpen) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sfxQuery,
                onValueChange = { sfxQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索标记名 / 文件名") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            val sq = sfxQuery.trim().lowercase()
            val shown = sfxEntries.filter { (name, path) ->
                sq.isBlank() || name.lowercase().contains(sq) || path.lowercase().contains(sq)
            }
            if (shown.isEmpty()) {
                Text(if (sfxEntries.isEmpty()) "暂无音效。点击上方「添加音效」。"
                     else "无匹配条目",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary)
            } else {
                shown.forEach { (name, path) ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                                .clickable { editingSfx = name }
                        ) {
                        Column(Modifier.weight(1f)) {
                            Text("⟨sfx:$name⟩", style = MaterialTheme.typography.bodyMedium)
                            Text(java.io.File(path).name,
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.secondary,
                                 maxLines = 1)
                        }
                        IconButton(onClick = {
                            com.mtts.app.util.SoundEffectManager.sfxVolume = sfxVolume
                            com.mtts.app.util.SoundEffectManager.preview(ctx, path)
                        }) {
                            Icon(Icons.Default.PlayArrow, "试听 $name",
                                 tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { pendingPick = name; audioPicker.launch(arrayOf("audio/*")) }) {
                            Icon(Icons.Default.Add, "更换文件 $name",
                                 tint = MaterialTheme.colorScheme.primary)
                        }
                                                IconButton(onClick = {
                            val newEntries = sfxEntries.filter { it.first != name }
                            sfxEntries = newEntries
                            com.mtts.app.util.AppPrefs.saveSfxMap(newEntries)
                            val newKw = kwEntries.filter { it.second != name }
                            kwEntries = newKw
                            com.mtts.app.util.AppPrefs.saveSfxKeywords(newKw)
                            com.mtts.app.util.SoundEffectManager.clearCache()
                        }) {
                            Icon(Icons.Default.Delete, "删除 $name",
                                 tint = MaterialTheme.colorScheme.error)
                        }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.padding(12.dp))
        // 获取更多音效：Mixkit 引导 + 批量导入
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("获取更多音效", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text("Mixkit 提供海量免费短音效（雨/雷/脚步/动物/门/剑等），Mixkit License 允许免费商用、无需署名。在浏览器打开后点条目上的 Download Free SFX 下载 mp3，回来用「批量导入」一次导入多个；文件名即标记名（如 rain.mp3 → ⟨sfx:rain⟩）。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(
                        text = "打开 Mixkit",
                        onClick = {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mixkit.co/free-sound-effects/")))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    AppPrimaryButton(
                        text = if (batchImporting) "导入中…" else "批量导入",
                        onClick = { batchSfxPicker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.weight(1f),
                        enabled = !batchImporting
                    )
                }
                if (batchMsg.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(batchMsg,
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(Modifier.padding(12.dp))
        // 关键词触发（卡片只包标题行，展开内容在卡外，避免高卡片阴影错位）
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clickable { kwListOpen = !kwListOpen }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("关键词触发", style = MaterialTheme.typography.bodyLarge)
                    Text("文本中出现关键词时自动注入对应音效标记（同一关键词可对应多个音效，随机选用）。共 ${kwEntries.size} 条。",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.secondary)
                }
                Icon(
                    if (kwListOpen) androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp
                    else androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                    if (kwListOpen) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        AppOutlinedButton(text = "添加关键词", onClick = { showAddKw = true }, modifier = Modifier.fillMaxWidth())
        if (kwListOpen) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = kwQuery,
                onValueChange = { kwQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索关键词 / 音效名") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            val kq = kwQuery.trim().lowercase()
            val shown = kwEntries.filter { (kw, name) ->
                kq.isBlank() || kw.lowercase().contains(kq) || name.lowercase().contains(kq)
            }
            if (shown.isEmpty()) {
                Text(if (kwEntries.isEmpty()) "暂无关键词规则。" else "无匹配条目",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary)
            } else {
                shown.forEach { (kw, name) ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                                .clickable { editingKw = kw to name }
                        ) {
                            Text("「$kw」→ ⟨sfx:$name⟩",
                                 style = MaterialTheme.typography.bodyMedium,
                                 modifier = Modifier.weight(1f))
                            IconButton(onClick = { editingKw = kw to name }) {
                                Icon(Icons.Default.Edit, "编辑 $kw",
                                     tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.padding(12.dp))
        // 音效导入导出（zip：注册表+关键词+配置+音频，可部分导入）
        val sfxExportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    com.mtts.app.util.SettingsBackup.exportSfxTo(uri)
                }
                r.onSuccess { msg -> android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() }
                 .onFailure { e -> android.widget.Toast.makeText(ctx, "导出失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
        val sfxImportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    com.mtts.app.util.SettingsBackup.importSfxFrom(uri)
                }
                r.onSuccess { msg ->
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                    sfxEntries = com.mtts.app.util.AppPrefs.loadSfxMap()
                    kwEntries = com.mtts.app.util.AppPrefs.loadSfxKeywords()
                }
                 .onFailure { e -> android.widget.Toast.makeText(ctx, "导入失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("音效导入导出", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text("导出为 zip 压缩包（注册表 + 关键词 + 配置 + 音频文件）。导入支持完整包、部分条目的包，或单独的注册表/关键词文本文件（每行「名称|值」）。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(
                        text = "导出音效包",
                        onClick = { sfxExportPicker.launch("mtts_sfx_backup.zip") },
                        modifier = Modifier.weight(1f)
                    )
                    AppPrimaryButton(
                        text = "导入音效包",
                        onClick = { sfxImportPicker.launch(arrayOf("application/zip", "application/octet-stream", "text/plain")) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.padding(16.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("使用说明", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("· BGM：选择音频文件后开启即可，所有朗读（含系统 TTS 外部调用）循环混入。\n" +
                     "· 音效：注册「标记名 → 音频文件」，在文本中写 ⟨sfx:标记名⟩ 触发；或配置关键词自动触发。\n" +
                     "· 音频支持 mp3 / ogg / wav / m4a / flac。文件会复制到应用私有目录，删除应用即清除。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary)
            }
        }
        Spacer(Modifier.padding(16.dp))
    }

    if (showAddSfx) {
        AddSfxDialog(
            onDismiss = { showAddSfx = false },
            onConfirm = { name ->
                showAddSfx = false
                if (name.isNotBlank() && sfxEntries.none { it.first == name }) {
                    pendingPick = name
                    audioPicker.launch(arrayOf("audio/*"))
                }
            }
        )
    }
    if (showAddKw) {
        AddSfxKeywordDialog(
            names = sfxEntries.map { it.first },
            onDismiss = { showAddKw = false },
            onConfirm = { kw, name ->
                showAddKw = false
                if (kw.isNotBlank() && name.isNotBlank()) {
                    val newKw = kwEntries.filter { it.first != kw } + (kw to name)
                    kwEntries = newKw
                    com.mtts.app.util.AppPrefs.saveSfxKeywords(newKw)
                }
            }
        )
    }
    // 编辑音效：改标记名（音频文件保留，关键词引用同步改名）
    editingSfx?.let { oldName ->
        val path = sfxEntries.firstOrNull { it.first == oldName }?.second ?: ""
        EditSfxDialog(
            initialName = oldName,
            filePath = path,
            onDismiss = { editingSfx = null },
            onSave = { newName ->
                editingSfx = null
                val nn = newName.trim()
                if (nn.isBlank() || nn == oldName) return@EditSfxDialog
                if (sfxEntries.any { it.first == nn }) return@EditSfxDialog
                val newEntries = sfxEntries.map { if (it.first == oldName) nn to it.second else it }
                sfxEntries = newEntries
                com.mtts.app.util.AppPrefs.saveSfxMap(newEntries)
                // 关键词引用同步改名
                val newKw = kwEntries.map { if (it.second == oldName) it.first to nn else it }
                kwEntries = newKw
                com.mtts.app.util.AppPrefs.saveSfxKeywords(newKw)
                com.mtts.app.util.SoundEffectManager.clearCache()
            }
        )
    }
    // 编辑关键词：改关键词文本与指向的音效，可删除
    editingKw?.let { (oldKw, oldName) ->
        EditSfxKeywordDialog(
            initialKw = oldKw,
            initialName = oldName,
            names = sfxEntries.map { it.first },
            onDismiss = { editingKw = null },
            onSave = { newKw, newName ->
                editingKw = null
                val nk = newKw.trim()
                if (nk.isBlank() || newName.isBlank()) return@EditSfxKeywordDialog
                var list = kwEntries.filter { it.first != oldKw }
                list = list.filter { it.first != nk } + (nk to newName)
                kwEntries = list
                com.mtts.app.util.AppPrefs.saveSfxKeywords(list)
            },
            onDelete = {
                editingKw = null
                val list = kwEntries.filter { it.first != oldKw }
                kwEntries = list
                com.mtts.app.util.AppPrefs.saveSfxKeywords(list)
            }
        )
    }
}

/** 从 Uri 猜测音频扩展名（默认 mp3） */
private fun guessAudioExt(uri: Uri): String {
    val last = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
    return if (last in setOf("mp3", "ogg", "wav", "m4a", "flac", "aac", "opus")) last else "mp3"
}

/** 从 Uri 查询显示文件名（ContentResolver OpenableColumns），失败返回 null */
private fun queryDisplayName(ctx: android.content.Context, uri: Uri): String? {
    return runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()
}

/** 编辑音效对话框：改标记名（文件保留） */
@Composable
private fun EditSfxDialog(
    initialName: String,
    filePath: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑音效") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("标记名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("文件：${java.io.File(filePath).name}",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary)
                Text("改名将同步更新文本 ⟨sfx:标记名⟩ 与关键词引用。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = { AppPrimaryButton(text = "保存", onClick = { onSave(name) }, enabled = name.isNotBlank()) },
        dismissButton = { AppOutlinedButton(text = "取消", onClick = onDismiss) }
    )
}

/** 编辑关键词对话框：改关键词 / 指向音效 / 删除 */
@Composable
private fun EditSfxKeywordDialog(
    initialKw: String,
    initialName: String,
    names: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit
) {
    var kw by remember { mutableStateOf(initialKw) }
    var sel by remember { mutableStateOf(initialName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑关键词") },
        text = {
            Column {
                OutlinedTextField(
                    value = kw, onValueChange = { kw = it },
                    label = { Text("关键词（文本中出现即触发）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("触发音效：", style = MaterialTheme.typography.bodySmall)
                if (names.isEmpty()) {
                    Text("音效注册表为空。",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                } else {
                    Column(Modifier.height(180.dp).verticalScroll(rememberScrollState())) {
                        names.forEach { n ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(
                                    selected = sel == n,
                                    onClick = { sel = n }
                                )
                                Text("⟨sfx:$n⟩", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            AppPrimaryButton(
                text = "保存",
                onClick = { onSave(kw, sel) },
                enabled = kw.isNotBlank() && sel.isNotBlank()
            )
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(4.dp))
                AppOutlinedButton(text = "取消", onClick = onDismiss)
            }
        }
    )
}

/** 添加音效对话框：输入标记名 → 选文件 */
@Composable
private fun AddSfxDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加音效") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("标记名（如：door、rain）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("确认后选择音频文件。标记名用于文本 ⟨sfx:名字⟩ 触发。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = { AppPrimaryButton(text = "下一步", onClick = { onConfirm(name.trim()) }) },
        dismissButton = { AppOutlinedButton(text = "取消", onClick = onDismiss) }
    )
}

/** 添加关键词对话框：关键词 + 选择音效名 */
@Composable
private fun AddSfxKeywordDialog(names: List<String>, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var kw by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf(names.firstOrNull() ?: "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加关键词触发") },
        text = {
            Column {
                OutlinedTextField(
                    value = kw, onValueChange = { kw = it },
                    label = { Text("关键词（文本中出现即触发）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("触发音效：", style = MaterialTheme.typography.bodySmall)
                if (names.isEmpty()) {
                    Text("请先在音效注册表添加音效。",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                } else {
                    Column(Modifier.height(180.dp).verticalScroll(rememberScrollState())) {
                        names.forEach { n ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(
                                    selected = sel == n,
                                    onClick = { sel = n }
                                )
                                Text("⟨sfx:$n⟩", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            AppPrimaryButton(
                text = "保存",
                onClick = { onConfirm(kw.trim(), sel) },
                enabled = kw.isNotBlank() && sel.isNotBlank()
            )
        },
        dismissButton = { AppOutlinedButton(text = "取消", onClick = onDismiss) }
    )
}
