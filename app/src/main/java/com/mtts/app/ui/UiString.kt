package com.mtts.app.ui

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import com.mtts.app.engine.EngineCategory
import com.mtts.app.engine.EngineStatus
import com.mtts.app.R

/** 从 strings 资源读取字符串的扩展（让非 Composable 的 ViewModel 也能取资源文本） */
fun Context.stringRes(id: Int): String = getString(id)

fun Context.stringRes(id: Int, vararg args: Any): String = getString(id, *args)

@Composable
fun stringRes(id: Int): String = LocalContext.current.getString(id)

@Composable
fun stringRes(id: Int, vararg args: Any): String = LocalContext.current.getString(id, *args)

/** 引擎状态 label 资源映射 */
fun Context.engineStatusLabel(status: EngineStatus): String = when (status) {
    EngineStatus.READY -> getString(R.string.engine_status_ready)
    EngineStatus.NEEDS_COOKIE -> getString(R.string.engine_status_needs_cookie)
    EngineStatus.NEEDS_CONFIG -> getString(R.string.engine_status_needs_config)
    EngineStatus.STALE -> getString(R.string.engine_status_stale)
}

/** 引擎类别 label 资源映射 */
fun Context.engineCategoryLabel(category: EngineCategory): String = when (category) {
    EngineCategory.ONLINE -> getString(R.string.engine_cat_online)
    EngineCategory.RULE -> getString(R.string.engine_cat_rule)
    EngineCategory.OFFLINE -> getString(R.string.engine_cat_offline)
}

/** 底部 Tab label 资源映射 */
fun Context.tabLabel(tab: BottomTab): String = when (tab) {
    BottomTab.TTS -> getString(R.string.tab_tts)
    BottomTab.DIALOG -> getString(R.string.tab_dialog)
    BottomTab.SETTINGS -> getString(R.string.tab_settings)
}

/** 设置项标题资源映射 */
fun Context.settingsTitle(item: SettingsItem): String = when (item) {
    SettingsItem.ABOUT -> getString(R.string.set_about)
    SettingsItem.ENGINES -> getString(R.string.set_engines)
    SettingsItem.VOICE_PACKS -> getString(R.string.set_voice_packs)
    SettingsItem.TEST_TEXT -> getString(R.string.set_test_text)
    SettingsItem.LOGS -> getString(R.string.set_logs)
    SettingsItem.HTTP_SERVER -> getString(R.string.set_http_server)
    SettingsItem.PREPROCESS -> getString(R.string.set_preprocess)
    SettingsItem.SETTINGS_IO -> "全局设置导入导出"
    SettingsItem.DIALOG_FEATURE -> "对话功能"
    SettingsItem.MODEL_DOWNLOAD -> "Sherpa 模型下载"
}

/** 设置项描述资源映射 */
fun Context.settingsDesc(item: SettingsItem): String = when (item) {
    SettingsItem.ABOUT -> getString(R.string.set_about_desc)
    SettingsItem.ENGINES -> getString(R.string.set_engines_desc)
    SettingsItem.VOICE_PACKS -> getString(R.string.set_voice_packs_desc)
    SettingsItem.TEST_TEXT -> getString(R.string.set_test_text_desc)
    SettingsItem.LOGS -> getString(R.string.set_logs_desc)
    SettingsItem.HTTP_SERVER -> getString(R.string.set_http_server_desc)
    SettingsItem.PREPROCESS -> getString(R.string.set_preprocess_desc)
    SettingsItem.SETTINGS_IO -> "除语音包外的全部设置一键导入 / 导出"
    SettingsItem.DIALOG_FEATURE -> "旁白 / 角色 / 声音分组 / 情感"
    SettingsItem.MODEL_DOWNLOAD -> "Matcha / Kokoro / MeloTTS，下载即用"
}