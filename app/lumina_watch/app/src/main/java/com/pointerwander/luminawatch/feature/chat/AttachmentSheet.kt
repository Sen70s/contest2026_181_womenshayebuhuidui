package com.pointerwander.luminawatch.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import java.io.File

/** 聊天附件面板：复用液态玻璃弹层和按钮。 */
@Composable
fun AttachmentSheet(
    onDismiss: () -> Unit,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalWatchColors.current
    Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier.align(Alignment.CenterHorizontally)
                    .width(40.dp).height(4.dp)
                    .clip(CircleShape)
                    .background(colors.outline),
            )
            Spacer(Modifier.height(16.dp))
            Text("添加到对话", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "照片只在本机预览，不会上传云岸。",
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiquidGlassButton("从相册选择", onPickPhoto, Modifier.weight(1f),
                    Icons.Rounded.PhotoLibrary, enabled = enabled)
                LiquidGlassButton("拍照", onTakePhoto, Modifier.weight(1f),
                    Icons.Rounded.PhotoCamera, enabled = enabled)
            }
            Spacer(Modifier.height(8.dp))
            LiquidGlassButton("取消", onDismiss, Modifier.fillMaxWidth(), filled = false)
    }
}

/** 把相册返回的 content Uri 复制到应用私有目录，避免会话结束后权限失效。 */
fun copyImageToFiles(context: Context, uri: Uri): String? = runCatching {
    val dir = File(context.filesDir, "chat_images").apply { mkdirs() }
    val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
    }
    Uri.fromFile(file).toString()
}.getOrNull()

/** 拍照返回的缩略图保存为本地文件。 */
fun saveBitmapToFiles(context: Context, bitmap: Bitmap): String? = runCatching {
    val dir = File(context.filesDir, "chat_images").apply { mkdirs() }
    val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
    file.outputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
    }
    Uri.fromFile(file).toString()
}.getOrNull()
