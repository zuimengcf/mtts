package com.mtts.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.mtts.app.R
import com.mtts.app.engine.EngineCategory
import com.mtts.app.engine.EngineRegistry
import com.mtts.app.engine.TtsEngine
import com.mtts.app.util.AppPrefs
import com.mtts.app.util.MttsLog

/**
 * 主界面 —— 左侧边栏切换引擎，底部导航 TTS/设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MttsApp(vm: MainViewModel = viewModel()) {

    val uiState by vm.uiState.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // 引擎列表版本号：语音包导入/删除后递增，驱动 grouped 重新求值
    // 必须实际读取该 State，否则 enginesVersion 变化不会触发重组，侧边栏无法及时刷新新导入的引擎
    val enginesVersion by vm.enginesVersion.collectAsState()
    val grouped = vm.groupedEngines

    // 安卓返回键处理
    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            // 预处理二级页 → 预处理主页
            uiState.bottomTab == BottomTab.SETTINGS && uiState.settingsDetail == SettingsItem.PREPROCESS && uiState.preprocessPage != null ->
                vm.backToPreprocessHome()
            // 对话功能二级页 → 对话功能主页
            uiState.bottomTab == BottomTab.SETTINGS && uiState.settingsDetail == SettingsItem.DIALOG_FEATURE && uiState.dialogFeaturePage != null ->
                vm.backToDialogFeatureHome()
            // 任一功能主页 / 普通设置页 → 设置列表首页
            uiState.bottomTab == BottomTab.SETTINGS && uiState.settingsDetail != null ->
                vm.backToSettingsList()
            uiState.bottomTab == BottomTab.SETTINGS -> vm.selectTab(BottomTab.TTS)
            else -> {
                // 默认：退出（MainActivity 会处理 finish）
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 280.dp)) {
                // 侧边栏头部 —— 品牌区
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    // 品牌图标 + 应用名
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Mic, null,
                                 tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(ctx.getString(R.string.app_name),
                                 style = MaterialTheme.typography.titleLarge,
                                 color = MaterialTheme.colorScheme.onSurface)
                            Text(ctx.getString(R.string.app_tagline),
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // 全局合成参数（可折叠，离线包/引擎列表上方）
                GlobalParamsDrawerSection()
                // 引擎分组列表
                LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    grouped.forEach { (category, engines) ->
                        item(key = "header-${category.name}") {
                            Text(
                                ctx.engineCategoryLabel(category),
                                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp, end = 20.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(engines, key = { it.code }) { engine ->
                            EngineDrawerItem(
                                engine = engine,
                                selected = uiState.currentEngine?.code == engine.code,
                                onClick = {
                                    vm.selectEngine(engine.code)
                                    vm.selectTab(BottomTab.TTS)
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                    }
                }
                // 侧边栏底部 —— 常驻开关 + 退出按钮
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    // 常驻服务开关
                    var keepAlive by remember { mutableStateOf(com.mtts.app.util.AppPrefs.isKeepAliveEnabled()) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(ctx.getString(R.string.keep_alive_title),
                                 style = MaterialTheme.typography.bodyMedium)
                            Text(
                                ctx.getString(if (keepAlive) R.string.keep_alive_enabled_hint
                                              else R.string.keep_alive_disabled_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Switch(
                            checked = keepAlive,
                            onCheckedChange = { on ->
                                keepAlive = on
                                com.mtts.app.util.AppPrefs.setKeepAliveEnabledSync(on)
                                if (on) com.mtts.app.service.KeepAliveService.start(ctx)
                                else com.mtts.app.service.KeepAliveService.stop(ctx, true)
                            }
                        )
                    }
                    // 使用外部朗读参数（是否接受外部 App 传入的语速/音调）
                    var externalParams by remember { mutableStateOf(com.mtts.app.util.AppPrefs.isExternalParamsEnabled()) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("使用外部朗读参数",
                                 style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (externalParams) "跟随外部 App 传入的语速/音调"
                                else "忽略外部，强制使用本 App 全局参数",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Switch(
                            checked = externalParams,
                            onCheckedChange = { on ->
                                externalParams = on
                                com.mtts.app.util.AppPrefs.setExternalParamsEnabled(on)
                            }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    AppOutlinedButton(
                        text = ctx.getString(R.string.action_exit_app),
                        onClick = { MainActivity.exitApp(ctx as? android.app.Activity ?: return@AppOutlinedButton) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Default.Close,
                        enabled = true
                    )
                }
            }
        }
    ) {
        Scaffold(
            bottomBar = {
                // 底部导航栏
                NavigationBar {
                    NavigationBarItem(
                        selected = uiState.bottomTab == BottomTab.TTS,
                        onClick = { vm.selectTab(BottomTab.TTS) },
                        icon = { Icon(Icons.Default.Mic, null) },
                        label = { Text(ctx.tabLabel(BottomTab.TTS)) }
                    )
                    NavigationBarItem(
                        selected = uiState.bottomTab == BottomTab.DIALOG,
                        onClick = { vm.selectTab(BottomTab.DIALOG) },
                        icon = { Icon(Icons.Default.Forum, null) },
                        label = { Text(ctx.tabLabel(BottomTab.DIALOG)) }
                    )
                    NavigationBarItem(
                        selected = uiState.bottomTab == BottomTab.SETTINGS,
                        onClick = { vm.selectTab(BottomTab.SETTINGS) },
                        icon = { Icon(Icons.Default.Settings, null) },
                        label = { Text(ctx.tabLabel(BottomTab.SETTINGS)) }
                    )
                }
            }
        ) { innerPadding ->
            // ★ 关键修复：内容必须应用 innerPadding（底部避开导航栏），否则 UI 被底栏遮挡
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
            ) {
                when (uiState.bottomTab) {
                    BottomTab.TTS -> TtsContent(vm, uiState, drawerState, scope)
                    BottomTab.DIALOG -> DialogContent(vm)
                    BottomTab.SETTINGS -> SettingsScreen(vm, uiState.settingsDetail)
                }
            }
        }
    }
}

/** TTS 主内容区 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsContent(
    vm: MainViewModel,
    uiState: EngineUiState,
    drawerState: androidx.compose.material3.DrawerState,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val ctx = LocalContext.current
    // 合成输入框弹窗开关
    var synthOpen by remember { mutableStateOf(false) }
    var localDraft by remember { mutableStateOf(uiState.text) }
    LaunchedEffect(uiState.text) { localDraft = uiState.text }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏 —— 引擎名 + 右侧「合成」入口
        TopAppBar(
            title = {
                Text(uiState.currentEngine?.name ?: ctx.getString(R.string.app_name),
                     style = MaterialTheme.typography.titleLarge)
            },
            navigationIcon = {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.Menu, ctx.getString(R.string.cd_menu))
                }
            },
            actions = {
                IconButton(onClick = { synthOpen = true }) {
                    Icon(Icons.Default.Mic, ctx.getString(R.string.action_synthesize))
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // 引擎状态行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    uiState.currentEngine?.status?.let { ctx.engineStatusLabel(it) } ?: "",
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.width(12.dp))
                if (uiState.loading) {
                    Text(ctx.getString(R.string.loading_voices), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))

            // 音色列表（每张音色卡片「设置」可展开：旁白 / 角色 / 分组反向绑定 + 语速音量音调）
            VoiceListView(vm, uiState)
        }
    }

    // 顶部「合成」弹窗：文本输入 + 测试文本 + 合成 + 试听
    if (synthOpen) {
        SynthesizeDialog(
            vm = vm,
            uiState = uiState,
            text = localDraft,
            onTextChange = { localDraft = it; vm.onTextChange(it) },
            onDismiss = { synthOpen = false }
        )
    }
}

/** TTS 音色列表视图（正常音色平铺 + 被屏蔽音色收纳底部折叠区；长按卡片可拖动排序） */
@Composable
private fun VoiceListView(vm: MainViewModel, uiState: EngineUiState) {
    val ctx = LocalContext.current
    val engineCode = uiState.currentEngine?.code ?: ""
    // 被屏蔽音色（engine:voice 对集合），随屏蔽操作实时刷新
    var mutedPairs by remember { mutableStateOf(AppPrefs.loadMutedVoices()) }
    fun isMuted(v: com.mtts.app.engine.Voice): Boolean =
        mutedPairs.any { it.first == engineCode && it.second == v.shortName }
    // 底部折叠区展开状态
    var mutedExpanded by remember { mutableStateOf(false) }

    val all = uiState.voices
    // ★ 显示顺序：优先用户自定义顺序（拖动持久化），否则用引擎默认顺序
    var savedOrder by remember(engineCode) { mutableStateOf(AppPrefs.loadVoiceOrder(engineCode)) }
    val savedOrderValue = savedOrder   // 局部变量，避免委托属性 smart-cast 失败
    val orderedAll = remember(all, savedOrderValue) {
        if (savedOrderValue != null && savedOrderValue.isNotEmpty()) {
            val orderMap = savedOrderValue.withIndex().associate { it.value to it.index }
            // 已保存顺序在前（只保留仍存在的音色），引擎新增音色按原顺序排在后面
            val known = all.filter { orderMap.containsKey(it.shortName) }
                .sortedBy { orderMap[it.shortName] }
            val unknown = all.filterNot { orderMap.containsKey(it.shortName) }
            known + unknown
        } else all
    }
    val activeList = orderedAll.filter { !isMuted(it) }
    val mutedList = orderedAll.filter { isMuted(it) }

    // ===== 长按拖拽排序状态 =====
    var dragState by remember { mutableStateOf<VoiceDragState?>(null) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // pointerInput(Unit) 只组合一次，闭包内必须经 rememberUpdatedState 读取最新状态
    val latestActive by rememberUpdatedState(dragState?.active ?: activeList)

    // 拖拽结束持久化：active 顺序 + 被屏蔽音色尾随在后（每次重组更新，闭包引用最新值）
    val persistOrder: (List<com.mtts.app.engine.Voice>) -> Unit = { list ->
        val activeKeys = list.map { it.shortName }
        val mutedKeys = orderedAll.filter { isMuted(it) }.map { it.shortName }
        val newOrder = activeKeys + mutedKeys
        AppPrefs.saveVoiceOrder(engineCode, newOrder)
        savedOrder = newOrder   // 更新 state，驱动 orderedAll 重算，拖拽结束后保持新顺序
    }
    val latestPersist by rememberUpdatedState(persistOrder)

    AppSectionTitle(ctx.getString(R.string.title_choose_voice))
    Spacer(Modifier.height(4.dp))
    if (all.isEmpty()) {
        Text(ctx.getString(R.string.msg_no_voices), style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.secondary)
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ===== 正常音色（长按拖动排序） =====
            // 使用拖拽中的实时顺序；未拖拽用 activeList
            val displayList = dragState?.active ?: activeList
            items(displayList.size, key = { displayList[it].shortName }) { i ->
                val voice = displayList[i]
                val isDragging = dragState?.draggingIndex == i
                VoiceCard(
                    voice = voice,
                    engine = uiState.currentEngine,
                    selected = voice.shortName == uiState.selectedVoice,
                    onClick = { vm.selectVoice(voice.shortName) },
                    vm = vm,
                    muted = false,
                    onToggleMute = {
                        val newPairs = mutedPairs + (engineCode to voice.shortName)
                        AppPrefs.saveMutedVoices(newPairs)
                        mutedPairs = newPairs
                        // 若屏蔽的是当前选中音色 → 自动切到第一个未屏蔽音色（避免选中态停留已关闭音色）
                        if (voice.shortName == uiState.selectedVoice) {
                            orderedAll.firstOrNull { !isMuted(it) && it.shortName != voice.shortName }?.let {
                                vm.selectVoice(it.shortName)
                            } ?: run { vm.selectVoice("") }
                        }
                    },
                    // ★ 长按拖拽：手指按住卡片拖动时上下重排
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .animateItem(
                            // 被拖的卡片由 translationY 跟手，placement 必须瞬移（0ms）避免与手指位移叠加闪烁；
                            // 其余卡片用较慢 tween 平滑滑入新位置（替代默认 spring 的弹跳"刷"感）
                            placementSpec = if (isDragging) tween(durationMillis = 0)
                                            else tween(durationMillis = 280, easing = FastOutSlowInEasing)
                        )
                        .graphicsLayer {
                            // 被拖拽的卡片跟随手指位移（dragOffset 为自按下起的累计像素位移 → 视觉连续跟手）
                            translationY = if (isDragging) (dragState?.dragOffset ?: 0f) else 0f
                            // 被拖拽的卡片轻微上浮，其余卡片在交换时平滑过渡
                            scaleX = if (isDragging) 1.04f else 1f
                            scaleY = if (isDragging) 1.04f else 1f
                            shadowElevation = if (isDragging) 8f else 0f
                        }
                        .pointerInput(voice.shortName) {
                            val voiceKey = voice.shortName   // 闭包捕获音色 key（稳定），index 实时反查
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    val list = latestActive
                                    val idx = list.indexOfFirst { it.shortName == voiceKey }
                                        .takeIf { it >= 0 } ?: return@detectDragGesturesAfterLongPress
                                    dragState = VoiceDragState(
                                        active = list,
                                        draggingIndex = idx,
                                        dragOffset = 0f
                                    )
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val st = dragState ?: return@detectDragGesturesAfterLongPress
                                    val from = st.draggingIndex
                                    // 累计自按下起的绝对位移（像素）→ 用于卡片视觉跟手
                                    val absOffset = st.dragOffset + dragAmount.y
                                    // 估算卡片高度（头像 52dp + 名字区 + 按钮行 + 内边距 ≈ 130dp）+ 间距
                                    val cardH = with(density) { (130f + 10f).dp.toPx() }
                                    val target = (from + (absOffset / cardH).toInt())
                                        .coerceIn(0, latestActive.lastIndex)
                                    if (target != from && latestActive.isNotEmpty()) {
                                        val newList = latestActive.toMutableList()
                                        val item = newList.removeAt(from)
                                        newList.add(target, item)
                                        // 换位后 dragOffset 折算为"相对新位置手指继续移了多少"
                                        // （被拖卡片在新槽位基础上继续跟随剩余位移，视觉连续）
                                        val relative = absOffset - (target - from) * cardH
                                        dragState = VoiceDragState(
                                            active = newList,
                                            draggingIndex = target,
                                            dragOffset = relative
                                        )
                                    } else {
                                        dragState = VoiceDragState(
                                            active = latestActive,
                                            draggingIndex = from,
                                            dragOffset = absOffset
                                        )
                                    }
                                },
                                onDragEnd = {
                                    dragState?.active?.let { latestPersist(it) }
                                    dragState = null
                                },
                                onDragCancel = {
                                    dragState = null
                                }
                            )
                        }
                )
            }
            // ===== 被屏蔽音色底部折叠区 =====
            if (mutedList.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().clickable { mutedExpanded = !mutedExpanded }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (mutedExpanded) Icons.Default.KeyboardArrowDown
                            else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "被屏蔽音色（${mutedList.size}）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text("点击展开/收起", style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.secondary)
                    }
                }
                if (mutedExpanded) {
                    items(mutedList.size, key = { "muted_${mutedList[it].shortName}" }) { i ->
                        val voice = mutedList[i]
                        VoiceCard(
                            voice = voice,
                            engine = uiState.currentEngine,
                            selected = false,
                            onClick = { vm.selectVoice(voice.shortName) },
                            vm = vm,
                            muted = true,
                            onToggleMute = {
                                val newPairs = mutedPairs.filterNot { it.first == engineCode && it.second == voice.shortName }
                                AppPrefs.saveMutedVoices(newPairs)
                                mutedPairs = newPairs
                            },
                            modifier = Modifier
                        )
                    }
                }
            }
        }
    }
}

/** 拖拽重排中间状态 */
private data class VoiceDragState(
    val active: List<com.mtts.app.engine.Voice>,
    val draggingIndex: Int = 0,
    val dragOffset: Float = 0f   // 归一化位移：用于计算换位 + 也驱动被拖卡片跟随手指（视觉）
)

/** 顶部「合成」对话框 —— 输入文本 + 测试 + 合成 + 播放结果 */
@Composable
private fun SynthesizeDialog(
    vm: MainViewModel,
    uiState: EngineUiState,
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.text_input_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(ctx.getString(R.string.text_input_label)) },
                    placeholder = { Text(ctx.getString(R.string.text_input_placeholder)) },
                    minLines = 4, maxLines = 8
                )
                uiState.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                         style = MaterialTheme.typography.bodySmall)
                }
                if (uiState.audioReady) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AudioFile, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            ctx.getString(R.string.msg_audio_ready, uiState.audioBytes?.size?.div(1024) ?: 0),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.weight(1f))
                        AppTextButton(
                            text = if (uiState.playing) ctx.getString(R.string.action_stop)
                                   else ctx.getString(R.string.action_play),
                            onClick = { vm.playAudio() },
                            icon = if (uiState.playing) Icons.Default.Stop else Icons.Default.PlayArrow
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 测试文本：填入内置测试语句
                TextButton(onClick = { vm.loadTestText() }, enabled = !uiState.playing) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(ctx.getString(R.string.action_test_text))
                }
                Spacer(Modifier.width(8.dp))
                AppPrimaryButton(
                    text = if (uiState.synthesizing) ctx.getString(R.string.action_synthesizing)
                           else ctx.getString(R.string.action_synthesize),
                    onClick = { vm.synthesize() },
                    icon = Icons.Default.Mic,
                    enabled = !uiState.synthesizing
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) }
        }
    )
}

/** 单个音色卡片（横向：头像 + 名称/短名 + 状态图标 + 屏蔽按钮 + 试听/设置按钮） */
@Composable
private fun VoiceCard(
    voice: com.mtts.app.engine.Voice,
    engine: com.mtts.app.engine.TtsEngine?,
    selected: Boolean,
    onClick: () -> Unit,
    vm: MainViewModel,
    muted: Boolean = false,
    onToggleMute: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showSettings by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val engineCode = engine?.code ?: ""
    val voiceKey = voice.shortName
    // 选中态用主色浅底；普通态用提升卡片阴影；被屏蔽用灰底（彻底关闭态）
    val bg = when {
        muted -> MaterialTheme.colorScheme.surfaceVariant
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }

    // ===== 三个快捷状态（实时读取持久化） =====
    // ① 是否自定义参数（语速/音量/音调任一被自定义）
    val hasCustom = AppPrefs.getVoiceSpeed(voiceKey) != null ||
        AppPrefs.getVoiceVolume(voiceKey) != null ||
        AppPrefs.getVoicePitch(voiceKey) != null
    // ② 是否旁白
    val asNarrator = AppPrefs.loadNarrators().any { it.engineCode == engineCode && it.voice == voiceKey }
    // ③ 是否分配角色
    val asCharacter = AppPrefs.loadCharacters().any { it.engineCode == engineCode && it.voice == voiceKey }

    androidx.compose.material3.ElevatedCard(
        onClick = { if (!muted) onClick() },   // 被屏蔽：整卡不可点击（不可选中）
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(containerColor = bg),
        elevation = androidx.compose.material3.CardDefaults.elevatedCardElevation(
            defaultElevation = if (muted) 0.dp else 2.dp)
    ) {
        // 选中时顶部显示一条主色高亮
        if (selected && !muted) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VoiceAvatar(engineCode = engineCode, avatarName = voice.avatar, label = voice.name, size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        voice.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
                                else if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        voice.shortName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (muted) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "已关闭",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                // ===== 快捷状态图标（只读显示，保持与名字同一行、利用右侧空间放大） =====
                Spacer(Modifier.width(4.dp))
                // ① 是否自定义参数（Tune）
                Icon(
                    Icons.Default.Tune,
                    contentDescription = if (hasCustom) "已自定义参数" else "未自定义参数",
                    tint = if (!muted && hasCustom) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(6.dp))
                // ② 是否旁白（RecordVoiceOver）
                Icon(
                    Icons.Default.RecordVoiceOver,
                    contentDescription = if (asNarrator) "设为旁白" else "非旁白",
                    tint = if (!muted && asNarrator) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(6.dp))
                // ③ 是否分配角色（Person）
                Icon(
                    Icons.Default.Person,
                    contentDescription = if (asCharacter) "已分配角色" else "未分配角色",
                    tint = if (!muted && asCharacter) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                // ===== 屏蔽按钮（可点击切换，放大） =====
                IconButton(onClick = onToggleMute, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (muted) Icons.Outlined.VideocamOff else Icons.Default.VideocamOff,
                        contentDescription = if (muted) "解除屏蔽" else "屏蔽",
                        tint = if (muted) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // 试听 + 设置按钮（被屏蔽时全部禁用，只有「解除屏蔽」可恢复）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppOutlinedButton(
                    text = ctx.getString(R.string.action_preview),
                    onClick = { if (engine != null) vm.previewVoice(engine, voice.shortName) },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.PlayArrow,
                    enabled = engine != null && !vm.uiState.collectAsState().value.playing && !muted
                )
                AppOutlinedButton(
                    text = ctx.getString(R.string.action_setting),
                    onClick = { showSettings = true },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Settings,
                    enabled = !muted
                )
            }
        }
    }

    if (showSettings) {
        VoiceParamsDialog(vm, engine, voice) { showSettings = false }
    }
}

/** 音色参数设置对话框 —— 语速 / 音量 / 音高三滑块（UI 范围 1-100，对齐 MultiTTS）+ 归属管理（反向设置） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceParamsDialog(
    vm: MainViewModel,
    engine: com.mtts.app.engine.TtsEngine?,
    voice: com.mtts.app.engine.Voice,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    // ★ 展示「默认值」用 config 自带默认（pitch 基准 25），不被全局参数污染；
    //   合成实际生效仍走 effectiveVoiceParams（per-voice → 全局 → config）回退链。
    engine ?: return
    val voiceKey = voice.shortName
    val engineCode = engine.code
    val (cfgSpeed, cfgVolume, cfgPitch) = vm.configVoiceParams(voiceKey)
    // 是否已自定义（区别于 config 默认）
    val hasCustomSpeed = AppPrefs.getVoiceSpeed(voiceKey) != null
    val hasCustomVolume = AppPrefs.getVoiceVolume(voiceKey) != null
    val hasCustomPitch = AppPrefs.getVoicePitch(voiceKey) != null

    // ★ 归属状态（随修改实时刷新）
    var narrators by remember { mutableStateOf(AppPrefs.loadNarrators()) }
    var characters by remember { mutableStateOf(AppPrefs.loadCharacters()) }
    var groups by remember { mutableStateOf(AppPrefs.loadVoiceGroups()) }
    // 该音色当前归属
    val asNarrator = narrators.any { it.engineCode == engineCode && it.voice == voiceKey }
    val asCharacter = characters.filter { it.engineCode == engineCode && it.voice == voiceKey }
    val ownedGroups = groups.filter { g -> g.voices.any { it.first == engineCode && it.second == voiceKey } }

    // ★ UI 范围 1-100（对齐 MultiTTS），内部存储仍为引擎浮点参数：
    //   speed:  1-100，50=基准 1.0x（v/50）
    //   volume: 1-100，50=基准 1.0（v/50）
    //   pitch:  1-100，25=基准 0（(v-25)/25）
    fun toUiSpeed(f: Float) = (f * 50f).roundToInt().coerceIn(1, 100)
    fun toUiVolume(f: Float) = (f * 50f).roundToInt().coerceIn(1, 100)
    fun toUiPitch(f: Float) = (f * 25f + 25f).roundToInt().coerceIn(1, 100)

    var speedOn by remember { mutableStateOf(hasCustomSpeed) }
    var volumeOn by remember { mutableStateOf(hasCustomVolume) }
    var pitchOn by remember { mutableStateOf(hasCustomPitch) }
    var speed by remember { mutableStateOf(if (hasCustomSpeed) toUiSpeed(AppPrefs.getVoiceSpeed(voiceKey)!!) else toUiSpeed(cfgSpeed)) }
    var volume by remember { mutableStateOf(if (hasCustomVolume) toUiVolume(AppPrefs.getVoiceVolume(voiceKey)!!) else toUiVolume(cfgVolume)) }
    var pitch by remember { mutableStateOf(if (hasCustomPitch) toUiPitch(AppPrefs.getVoicePitch(voiceKey)!!) else toUiPitch(cfgPitch)) }

    @Composable
    fun handlerRow(title: String, value: Int, on: Boolean, onToggle: (Boolean)->Unit, onValue: (Int)->Unit) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("$value", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary)
                Switch(checked = on, onCheckedChange = onToggle)
            }
            if (on) {
                Slider(value = value.toFloat(), onValueChange = { onValue(it.roundToInt()) },
                       valueRange = 1f..100f)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(ctx.getString(R.string.voice_params_title, voice.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // ==================== 归属状态 ====================
                // 旁白
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("旁白", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        if (asNarrator) "√ 已设为旁白" else "未设为旁白",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (asNarrator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(checked = asNarrator, onCheckedChange = { on ->
                        narrators = if (on) {
                            val newList = narrators.filterNot { it.engineCode == engineCode && it.voice == voiceKey } +
                                com.mtts.app.util.DialogNarrator(engineCode, voiceKey)
                            AppPrefs.saveNarrators(newList); newList
                        } else {
                            val newList = narrators.filterNot { it.engineCode == engineCode && it.voice == voiceKey }
                            AppPrefs.saveNarrators(newList); newList
                        }
                    })
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // ============ 声音分组（下拉多选） ============
                var groupExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = groupExpanded,
                    onExpandedChange = { groupExpanded = it }
                ) {
                    OutlinedTextField(
                        value = if (ownedGroups.isEmpty()) "未加入任何分组"
                                else ownedGroups.joinToString("、") { "${it.name}(${it.gender})" },
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text("声音分组（多选）") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(groupExpanded) }
                    )
                    ExposedDropdownMenu(expanded = groupExpanded, onDismissRequest = { groupExpanded = false }) {
                        if (groups.isEmpty()) {
                            DropdownMenuItem(text = { Text("暂无分组（设置→对话功能→声音分组 创建）") }, onClick = {})
                        }
                        groups.forEach { g ->
                            val inGroup = g.voices.any { it.first == engineCode && it.second == voiceKey }
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = inGroup, onCheckedChange = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("${g.name}（${g.gender}）", Modifier.weight(1f))
                                    }
                                },
                                onClick = {
                                    groups = groups.map { grp ->
                                        if (grp.name != g.name) grp
                                        else {
                                            val newVoices = if (inGroup) {
                                                grp.voices.filterNot { it.first == engineCode && it.second == voiceKey }
                                            } else {
                                                (grp.voices.filterNot { it.first == engineCode && it.second == voiceKey } +
                                                 (engineCode to voiceKey))
                                            }
                                            grp.copy(voices = newVoices)
                                        }
                                    }
                                    AppPrefs.saveVoiceGroups(groups)
                                }
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant,
                                  modifier = Modifier.padding(vertical = 4.dp))
                // ============ 角色（下拉指派 / 解绑） ============
                var charExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = charExpanded,
                    onExpandedChange = { charExpanded = it }
                ) {
                    OutlinedTextField(
                        value = if (asCharacter.isEmpty()) "未绑定角色"
                                else asCharacter.joinToString("、") { it.name },
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text("绑定的角色") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(charExpanded) }
                    )
                    ExposedDropdownMenu(expanded = charExpanded, onDismissRequest = { charExpanded = false }) {
                        if (characters.isEmpty()) {
                            DropdownMenuItem(text = { Text("暂无角色（设置→对话功能→角色 创建）") }, onClick = {})
                        }
                        // 当前已绑定本音色的角色 → 一键解绑
                        asCharacter.forEach { c ->
                            DropdownMenuItem(
                                text = { Text("解绑：${c.name}", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    characters = characters.map { ch ->
                                        if (ch.name == c.name && ch.engineCode == engineCode && ch.voice == voiceKey)
                                            ch.copy(engineCode = "", voice = "", manual = false) else ch
                                    }
                                    AppPrefs.saveCharacters(characters)
                                }
                            )
                        }
                        // 其他未绑定本音色的角色 → 指派
                        val unassigned = characters.filter { it.engineCode != engineCode || it.voice != voiceKey }
                        unassigned.forEach { c ->
                            DropdownMenuItem(
                                text = { Text("指派给：${c.name}（${c.group.ifBlank { "未分组" }}）") },
                                onClick = {
                                    characters = characters.map { ch ->
                                        if (ch.name == c.name) ch.copy(engineCode = engineCode, voice = voiceKey, manual = true) else ch
                                    }
                                    AppPrefs.saveCharacters(characters)
                                }
                            )
                        }
                        if (asCharacter.isEmpty() && unassigned.isEmpty() && characters.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("所有角色均已绑定本音色") }, onClick = {})
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant,
                                  modifier = Modifier.padding(vertical = 4.dp))

                // ==================== 合成参数 ====================
                handlerRow(ctx.getString(R.string.param_speed), speed, speedOn, { speedOn = it }, { speed = it })
                Spacer(Modifier.padding(4.dp))
                handlerRow(ctx.getString(R.string.param_volume), volume, volumeOn, { volumeOn = it }, { volume = it })
                Spacer(Modifier.padding(4.dp))
                handlerRow(ctx.getString(R.string.param_pitch), pitch, pitchOn, { pitchOn = it }, { pitch = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.updateVoiceParams(
                    voiceKey,
                    if (speedOn) speed / 50f else null,
                    if (volumeOn) volume / 50f else null,
                    if (pitchOn) (pitch - 25) / 25f else null
                )
                onClose()
            }) { Text(ctx.getString(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(ctx.getString(R.string.action_cancel)) }
        }
    )
}

/** 侧边栏单个引擎条目 */
@Composable
private fun EngineDrawerItem(
    engine: TtsEngine,
    selected: Boolean,
    onClick: () -> Unit
) {
    val ctx = LocalContext.current
    NavigationDrawerItem(
        label = { Text(engine.name) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(engineCategoryIcon(engine.category), null) },
        badge = { Text(ctx.engineStatusLabel(engine.status), style = MaterialTheme.typography.labelSmall) }
    )
}

/**
 * 全局合成参数（侧边栏可折叠卡片，离线包/引擎列表上方）。
 * 语速/音量/音调 —— 未单独配置的音色回退使用此全局值。
 */
@Composable
private fun GlobalParamsDrawerSection() {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    // 全局当前值（null = 跟随 config）
    var speed by remember { mutableStateOf(AppPrefs.getGlobalSpeed() ?: 1.0f) }
    var volume by remember { mutableStateOf(AppPrefs.getGlobalVolume() ?: 1.0f) }
    var pitch by remember { mutableStateOf(AppPrefs.getGlobalPitch() ?: 0.0f) }
    // UI 基准映射：语速/音量 50=基准、语调 25=基准
    fun toUiSpeed(f: Float) = (f * 50f).roundToInt().coerceIn(1, 100)
    fun toUiVolume(f: Float) = (f * 50f).roundToInt().coerceIn(1, 100)
    fun toUiPitch(f: Float) = (f * 25f + 25f).roundToInt().coerceIn(1, 100)

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary,
                 modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(ctx.getString(R.string.global_params_title),
                 style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!expanded) {
            Text(ctx.getString(R.string.global_params_hint),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.secondary,
                 modifier = Modifier.padding(start = 28.dp))
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            // 语速
            GlobalSliderRow(
                title = ctx.getString(R.string.param_speed),
                value = toUiSpeed(speed),
                onChange = { speed = it / 50f; AppPrefs.setGlobalSpeed(speed) }
            )
            // 音量
            GlobalSliderRow(
                title = ctx.getString(R.string.param_volume),
                value = toUiVolume(volume),
                onChange = { volume = it / 50f; AppPrefs.setGlobalVolume(volume) }
            )
            // 音调
            GlobalSliderRow(
                title = ctx.getString(R.string.param_pitch),
                value = toUiPitch(pitch),
                onChange = { pitch = (it - 25) / 25f; AppPrefs.setGlobalPitch(pitch) }
            )
            Spacer(Modifier.height(4.dp))
            Text(ctx.getString(R.string.global_params_desc),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                AppPrefs.clearGlobalParams()
                speed = 1.0f; volume = 1.0f; pitch = 0.0f
            }, modifier = Modifier.align(Alignment.End)) {
                Text(ctx.getString(R.string.global_params_reset),
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 全局参数滑块行（小尺寸，适配侧边栏） */
@Composable
private fun GlobalSliderRow(title: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelSmall,
             modifier = Modifier.width(32.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 1f..100f,
            modifier = Modifier.weight(1f).height(28.dp)
        )
        Text("$value", style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(24.dp))
    }
}

private fun engineCategoryIcon(category: EngineCategory) = when (category) {
    EngineCategory.ONLINE -> Icons.Default.Cloud
    EngineCategory.RULE -> Icons.Default.Rule
    EngineCategory.OFFLINE -> Icons.Default.AudioFile
}

/** 语音下拉选择 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceDropdown(
    voices: List<com.mtts.app.engine.Voice>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedVoice = voices.firstOrNull { it.shortName == selected }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedVoice?.let { "${it.name} (${it.shortName})" } ?: "选择语音",
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text("${voice.name} (${voice.shortName}) [${voice.locale}]") },
                    onClick = {
                        onSelect(voice.shortName)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * 群聊对话页 —— 聊天气泡 + 群员管理（添加/踢出/禁言/点击头像改发言人）。
 * 每个群员绑定引擎+音色+头像，输入格式「角色：台词」（每行一句），
 * 显示为左右交替的聊天气泡；点击头像弹出管理菜单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogContent(vm: MainViewModel) {
    val dialog by vm.dialog.collectAsState()
    val engines = vm.allEngines
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var menuIndex by remember { mutableStateOf(-1) }
    var editSpeakerIndex by remember { mutableStateOf(-1) }
    var editMsgIndex by remember { mutableStateOf(-1) }   // 正在编辑的消息下标（-1=无）
    val scope = rememberCoroutineScope()

    // 解析每个群员的音色头像：role.avatar 优先，否则用 role.voice 对应音色的头像
    var avatarMap by remember { mutableStateOf(mapOf<String, String>()) }
    LaunchedEffect(dialog.roles) {
        val map = HashMap<String, String>()
        for (r in dialog.roles) {
            val av = if (r.avatar.isNotBlank()) r.avatar
                     else {
                         runCatching {
                             EngineRegistry.get(r.engineCode)
                                 ?.listVoices()?.firstOrNull { it.shortName == r.voice }?.avatar
                         }.getOrNull().orEmpty()
                     }
            map[r.label] = av ?: ""
        }
        avatarMap = map
    }

    val messages = remember(dialog.script) {
        dialog.script.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val idx = line.indexOfFirst { it == '：' || it == ':' }
            if (idx <= 0) return@mapNotNull null
            val role = line.substring(0, idx).trim()
            val text = line.substring(idx + 1).trim()
            if (text.isEmpty()) return@mapNotNull null
            DialogMessage(role, text, line)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(ctx.getString(R.string.dialog_title), style = MaterialTheme.typography.titleLarge)
                    Text("${ctx.getString(R.string.dialog_group_members)} ${dialog.roles.size}",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.secondary)
                }
            }
        )

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                items(dialog.roles.indices.toList()) { index ->
                    val role = dialog.roles[index]
                    GroupMemberAvatar(
                        role = role,
                        resolvedAvatar = avatarMap[role.label].orEmpty(),
                        onClick = { menuIndex = if (menuIndex == index) -1 else index },
                        menuExpanded = menuIndex == index,
                        onDismissMenu = { menuIndex = -1 },
                        onEditSpeaker = { editSpeakerIndex = index; menuIndex = -1 },
                        onToggleMute = { vm.toggleMuteRole(index); menuIndex = -1 },
                        onKick = { vm.removeRole(index); menuIndex = -1 },
                        canKick = dialog.roles.size > 1
                    )
                }
                item {
                    Column(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { vm.addRole() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(ctx.getString(R.string.dialog_script_placeholder),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.secondary,
                             modifier = Modifier.padding(8.dp))
                    }
                }
                items(messages) { msg ->
                    val roleIdx = dialog.roles.indexOfFirst { it.label == msg.role }
                    val role = dialog.roles.getOrNull(roleIdx)
                    ChatBubble(
                        role = role,
                        resolvedAvatar = role?.let { avatarMap[it.label].orEmpty() }.orEmpty(),
                        text = msg.text,
                        isSelf = roleIdx % 2 == 1,
                        onAvatarClick = {
                            if (roleIdx >= 0) menuIndex = if (menuIndex == roleIdx) -1 else roleIdx
                        },
                        onClick = {
                            val mi = messages.indexOf(msg)
                            if (mi >= 0) editMsgIndex = mi
                        }
                    )
                }
            }

            dialog.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.bodySmall)
            }

            if (dialog.playing) {
                val line = dialog.progressLine
                Text(
                    if (line >= 0) ctx.getString(R.string.msg_playing_line, line + 1)
                    else ctx.getString(R.string.msg_synthesizing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // 底部输入区 —— 左侧：当前发言人头像（点击切换）；中间：纯台词输入框；发送自动带上发言人
            var currentSpeaker by remember { mutableStateOf(0) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 发言人头像（点击切换发言人）
                val speakerRole = dialog.roles.getOrNull(currentSpeaker)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable {
                            if (dialog.roles.size > 1) {
                                currentSpeaker = (currentSpeaker + 1) % dialog.roles.size
                            } else {
                                editSpeakerIndex = 0
                            }
                        }
                ) {
                    if (speakerRole != null) {
                        VoiceAvatar(
                            engineCode = speakerRole.engineCode,
                            avatarName = avatarMap[speakerRole.label].orEmpty(),
                            label = speakerRole.label,
                            size = 40.dp
                        )
                    } else {
                        VoiceAvatar("", "", "我", 40.dp)
                    }
                    // 角标提示可点击
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .align(Alignment.BottomEnd)
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("…", color = MaterialTheme.colorScheme.onPrimary,
                             style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 纯台词输入框
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(ctx.getString(R.string.dialog_input_hint)) },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                // 发送按钮 —— 自动带上当前发言人
                IconButton(
                    onClick = {
                        val t = input.trim()
                        if (t.isNotEmpty()) {
                            val roleLabel = dialog.roles.getOrNull(currentSpeaker)?.label ?: "角色1"
                            vm.onDialogScriptChange(
                                (dialog.script.ifBlank { "" } + "\n" + "$roleLabel：$t").trim()
                            )
                            input = ""
                            scope.launch { listState.animateScrollToItem(messages.size + 1) }
                        }
                    },
                    enabled = input.isNotBlank()
                ) { Icon(Icons.Default.Send, ctx.getString(R.string.action_send)) }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { vm.playDialog() }) {
                    Icon(if (dialog.playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                         ctx.getString(R.string.action_play_dialog))
                }
            }
        }
    }

    if (editSpeakerIndex >= 0 && editSpeakerIndex < dialog.roles.size) {
        EditSpeakerDialog(
            role = dialog.roles[editSpeakerIndex],
            engines = engines,
            onEngineChange = { code -> vm.updateRole(editSpeakerIndex, code, null, null, null) },
            onVoiceChange = { v, av ->
                // 同步音色头像：由对话框内已加载的音色列表提供新音色的头像
                vm.updateRole(editSpeakerIndex, null, v, av, null)
            },
            onLabelChange = { l -> vm.updateRoleLabel(editSpeakerIndex, l) },
            onDismiss = { editSpeakerIndex = -1 }
        )
    }

    // 编辑已有消息：点击气泡弹出对话框（改台词/改发言人/本句间隔/删除）
    if (editMsgIndex >= 0 && editMsgIndex < messages.size) {
        EditMessageDialog(
            msg = messages[editMsgIndex],
            roles = dialog.roles,
            intervalMs = AppPrefs.getLineIntervalMs(messages[editMsgIndex].raw),
            onIntervalChange = { ms -> AppPrefs.setLineIntervalMs(messages[editMsgIndex].raw, ms) },
            onSave = { newRole, newText ->
                vm.replaceDialogLine(messages[editMsgIndex].raw, "$newRole：$newText")
                editMsgIndex = -1
            },
            onDelete = {
                vm.removeDialogLine(messages[editMsgIndex].raw)
                editMsgIndex = -1
            },
            onDismiss = { editMsgIndex = -1 }
        )
    }
}

@Composable
private fun GroupMemberAvatar(
    role: DialogRole,
    resolvedAvatar: String,
    onClick: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onEditSpeaker: () -> Unit,
    onToggleMute: () -> Unit,
    onKick: () -> Unit,
    canKick: Boolean
) {
    val ctx = LocalContext.current
    Box {
        Column(
            modifier = Modifier.width(56.dp).clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                VoiceAvatar(engineCode = role.engineCode, avatarName = resolvedAvatar, label = role.label, size = 44.dp)
                if (role.muted) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.BottomEnd)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.error),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MicOff, null,
                             modifier = Modifier.size(10.dp),
                             tint = MaterialTheme.colorScheme.onError)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(role.label, style = MaterialTheme.typography.labelSmall,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(
                text = { Text(ctx.getString(R.string.dialog_edit_speaker)) },
                onClick = onEditSpeaker,
                leadingIcon = { Icon(Icons.Default.Person, null) }
            )
            DropdownMenuItem(
                text = { Text(if (role.muted) ctx.getString(R.string.dialog_unmute) else ctx.getString(R.string.dialog_mute)) },
                onClick = onToggleMute,
                leadingIcon = { Icon(if (role.muted) Icons.Default.MicOff else Icons.Default.Mic, null) }
            )
            if (canKick) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(ctx.getString(R.string.dialog_kick), color = MaterialTheme.colorScheme.error) },
                    onClick = onKick,
                    leadingIcon = { Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error) }
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(
    role: DialogRole?,
    resolvedAvatar: String,
    text: String,
    isSelf: Boolean,
    onAvatarClick: () -> Unit = {},
    onClick: () -> Unit = {}
) {
    val ctx = LocalContext.current
    // 群聊样式：全部靠左显示（头像 + 昵称 + 气泡），不使用左右交替
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (role != null) {
            Box(modifier = Modifier.clickable(onClick = onAvatarClick)) {
                VoiceAvatar(engineCode = role.engineCode, avatarName = resolvedAvatar, label = role.label, size = 36.dp)
            }
            Spacer(Modifier.width(6.dp))
        }
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 300.dp)
        ) {
            role?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it.label, style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.secondary)
                    if (it.muted) {
                        Spacer(Modifier.width(4.dp))
                        Text(ctx.getString(R.string.dialog_muted),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .clickable(onClick = onClick)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isSelf) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private data class DialogMessage(val role: String, val text: String, val raw: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSpeakerDialog(
    role: DialogRole,
    engines: List<TtsEngine>,
    onEngineChange: (String) -> Unit,
    onVoiceChange: (String, String) -> Unit,
    onLabelChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var label by remember { mutableStateOf(role.label) }
    // ★ 该角色发言后的默认间隔（毫秒）：本角色的台词播完后停顿；仅存储展示，播放时读取
    var interval by remember { mutableStateOf(AppPrefs.getDialogIntervalMs()) }
    var voices by remember(role.engineCode) { mutableStateOf<List<com.mtts.app.engine.Voice>>(emptyList()) }
    LaunchedEffect(role.engineCode) {
        voices = withContext(Dispatchers.IO) {
            runCatching { EngineRegistry.get(role.engineCode)?.listVoices() ?: emptyList() }
                .getOrDefault(emptyList())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.dialog_edit_speaker)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("群员昵称") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                // ★ 角色默认间隔（毫秒）：本角色台词播完后停顿再播下一句
                OutlinedTextField(
                    value = if (interval == 0) "" else interval.toString(),
                    onValueChange = { v ->
                        interval = v.toIntOrNull()?.coerceIn(0, 10000) ?: 0
                        AppPrefs.setDialogIntervalMs(interval)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("默认句间隔（毫秒，0=无）") },
                    placeholder = { Text("0 = 无间隔") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                var engineExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = engineExpanded, onExpandedChange = { engineExpanded = it }) {
                    OutlinedTextField(
                        value = engines.firstOrNull { it.code == role.engineCode }?.name ?: role.engineCode,
                        onValueChange = {}, readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true,
                        label = { Text("引擎") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(engineExpanded) }
                    )
                    ExposedDropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                        engines.forEach { e ->
                            DropdownMenuItem(
                                text = { Text(e.name) },
                                onClick = { onEngineChange(e.code); engineExpanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                var voiceExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = voiceExpanded, onExpandedChange = { voiceExpanded = it }) {
                    val current = voices.firstOrNull { it.shortName == role.voice }
                    OutlinedTextField(
                        value = current?.let { "${it.name} (${it.shortName})" } ?: "未选择",
                        onValueChange = {}, readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true,
                        label = { Text("音色") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(voiceExpanded) }
                    )
                    ExposedDropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                        voices.forEach { v ->
                            DropdownMenuItem(
                                text = { Text("${v.name} (${v.shortName})") },
                                onClick = {
                                    onVoiceChange(v.shortName, v.avatar)
                                    voiceExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (label.isNotBlank()) onLabelChange(label.trim())
                onDismiss()
            }) { Text(ctx.getString(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) }
        }
    )
}

/** 编辑消息对话框 —— 点击气泡弹出：改发言人 + 改台词 + 本句间隔 + 删除该条 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditMessageDialog(
    msg: DialogMessage,
    roles: List<DialogRole>,
    intervalMs: Int,
    onIntervalChange: (Int) -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var role by remember { mutableStateOf(msg.role) }
    var text by remember { mutableStateOf(msg.text) }
    var interval by remember { mutableStateOf(intervalMs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑台词") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 发言人下拉
                var roleExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = roleExpanded, onExpandedChange = { roleExpanded = it }) {
                    OutlinedTextField(
                        value = role,
                        onValueChange = {}, readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true,
                        label = { Text("发言人") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(roleExpanded) }
                    )
                    ExposedDropdownMenu(expanded = roleExpanded, onDismissRequest = { roleExpanded = false }) {
                        roles.forEach { r ->
                            DropdownMenuItem(
                                text = { Text(r.label) },
                                onClick = { role = r.label; roleExpanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("台词") },
                    placeholder = { Text("输入台词") }
                )
                Spacer(Modifier.height(8.dp))
                // ★ 本句间隔（毫秒）：播放完本句后停顿，再播下一句（0=跟随默认间隔）
                OutlinedTextField(
                    value = if (interval == 0) "" else interval.toString(),
                    onValueChange = { v ->
                        interval = v.toIntOrNull()?.coerceIn(0, 10000) ?: 0
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("本句间隔（毫秒，0=跟随默认）") },
                    placeholder = { Text("0 = 跟随默认") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onIntervalChange(interval)
                if (text.isNotBlank()) onSave(role.ifBlank { msg.role }, text.trim())
            }) { Text(ctx.getString(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onDelete(); }) {
                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) }
            }
        }
    )
}