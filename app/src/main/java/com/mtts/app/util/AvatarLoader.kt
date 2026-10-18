package com.mtts.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mtts.app.engine.VoicePackageManager
import java.io.File

/**
 * 音色头像加载器 —— 从语音包 avatar 目录读取头像图片。
 *
 * 目录约定：voice/<engineCode>/avatar/<avatar 文件名>
 * 头像文件名由 config.yaml 的 Speaker.avatar 提供（如 duzhongxiang.png）。
 */
object AvatarLoader {

    /**
     * 载入某引擎某音色的头像 Bitmap。
     * @param engineCode 引擎 code（如 bdetts/sougou/microsoft）
     * @param avatarName 头像文件名（Speaker.avatar 字段）；空或缺失时返回 null
     */
    fun loadAvatar(context: Context, engineCode: String, avatarName: String?): Bitmap? {
        if (avatarName.isNullOrBlank()) return null
        val file = resolveAvatarFile(context, engineCode, avatarName) ?: return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (t: Throwable) {
            null
        }
    }

    /** 解析头像文件的绝对路径（兼容外部/内部两个 voice 根） */
    fun resolveAvatarFile(context: Context, engineCode: String, avatarName: String): File? {
        val roots = listOfNotNull(
            VoicePackageManager.externalEngineDir(engineCode),
            VoicePackageManager.engineDir(engineCode)
        )
        for (root in roots) {
            val f = File(File(root, "avatar"), avatarName)
            if (f.isFile) return f
        }
        return null
    }
}