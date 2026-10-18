package com.mtts.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mtts.app.util.AvatarLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 音色头像显示组件 —— 后台加载语音包 avatar 图片。
 * 加载中/缺头像时显示角色首字圆形占位。
 */
@Composable
fun VoiceAvatar(
    engineCode: String,
    avatarName: String?,
    label: String,
    size: Dp = 48.dp
) {
    val ctx = LocalContext.current
    var bmp by remember(engineCode, avatarName) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(engineCode, avatarName) {
        bmp = withContext(Dispatchers.IO) {
            AvatarLoader.loadAvatar(ctx, engineCode, avatarName)
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            // 占位：显示标签首字
            Text(
                label.take(1),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}