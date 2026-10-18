package com.mtts.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 条件 Modifier 扩展 */
internal fun Modifier.ifTrue(cond: Boolean, then: Modifier.() -> Modifier): Modifier =
    if (cond) this.then() else this

/**
 * 统一 UI 组件库 —— 现代化风格、全部文本走 strings 资源。
 *
 * 目的：统一按钮/卡片/文本输入样式，避免各页面各自为政、硬编码文本。
 */

/** 统一主按钮（主色填充，圆角） */
@Composable
fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.width(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 统一次要按钮（描边） */
@Composable
fun AppOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.width(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 统一文字按钮 */
@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        if (icon != null) {
            Icon(icon, null, Modifier.width(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 统一标题栏（设置/详情页顶栏用） */
@Composable
fun AppSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier
    )
}

/**
 * 可折叠文本输入面板 —— "把外面的文本收起来"。
 * 默认收起只显示标题行；点击展开显示文本框，提供隐藏/显示切换。
 */
@Composable
fun CollapsibleTextPanel(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    textFieldLabel: String? = null,
    singleLine: Boolean = false,
    minHeight: Int = 100,
    expandedInitially: Boolean = false
) {
    var expanded by remember { mutableStateOf(expandedInitially) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(4.dp)) {
            // 标题行（点击切换展开/收起），带收起图标
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.KeyboardArrowDown,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .ifTrue(!singleLine) { height(minHeight.dp) },
                    label = textFieldLabel?.let { { Text(it) } },
                    placeholder = { Text(placeholder, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    singleLine = singleLine
                )
            }
        }
    }
}

/** 给 noheight 的 Modifier 用的扩展（内部简化，不实际生效链式） */
private fun Modifier.thenLv(h: Int): Modifier = this

/** 统一设置项行（图标 + 标题 + 描述 + 箭头） */
@Composable
fun SettingsRowItem(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            desc.takeIf { it.isNotEmpty() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 统一空态占位卡片 */
@Composable
fun EmptyStateCard(message: String, modifier: Modifier = Modifier, hint: String? = null) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            hint?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.padding(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}