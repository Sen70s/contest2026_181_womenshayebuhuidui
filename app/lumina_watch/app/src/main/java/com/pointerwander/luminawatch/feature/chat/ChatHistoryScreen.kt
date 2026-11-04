package com.pointerwander.luminawatch.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.motionClickable

@Composable
fun ChatHistoryScreen(
    store: AppStore,
    onBack: () -> Unit,
    onOpenMessage: (Long) -> Unit,
) {
    val colors = LocalWatchColors.current

    CloudSubpage("会话历史", onBack) {
        Text("保存在本机", color = colors.textSecondary, fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp))
        CloudContentCard {
            Text("当前对话", color = colors.textPrimary, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold)
            Text("${store.messages.size} 条消息 · 仅保存在本机", color = colors.textSecondary,
                fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text("消息记录", color = colors.textPrimary, fontSize = 19.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))

        if (store.messages.isEmpty()) {
            CloudContentCard {
                Text("还没有对话", color = colors.textPrimary, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold)
                Text("回到小云页开始第一句对话。", color = colors.textSecondary,
                    fontSize = 14.sp)
            }
        } else {
            store.messages.forEach { message ->
                CloudContentCard(Modifier.motionClickable(onClick = { onOpenMessage(message.id) })) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            if (message.fromCloud) "小云" else "我",
                            color = if (message.fromCloud) colors.accent else colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(message.timeLabel, color = colors.textSecondary, fontSize = 12.sp)
                    }
                    Text(message.text.ifBlank { if (message.imageUri != null) "照片" else "消息" },
                        color = colors.textPrimary, fontSize = 15.sp, lineHeight = 22.sp)
                }
            }
        }
        Text("跨设备同步尚未接入。", color = colors.textSecondary,
            fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
    }
}
