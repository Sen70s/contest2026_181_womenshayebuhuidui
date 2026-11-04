package com.pointerwander.luminawatch.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.ui.CareGlassToggle
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassChoiceChip
import com.pointerwander.luminawatch.ui.LiquidGlassHeaderPage
import androidx.compose.ui.Alignment

@Composable
fun CloudMemoryScreen(store: AppStore, onBack: () -> Unit) {
    val colors = LocalWatchColors.current
    CloudSubpage("小云记忆", onBack) {
        Text("由你决定小云记住什么", color = colors.textSecondary,
            fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp))
        CloudContentCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用记忆", color = colors.textPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text("只保存你主动确认的信息", color = colors.textSecondary,
                        fontSize = 13.sp, lineHeight = 19.sp)
                }
                CareGlassToggle(store.memoryEnabled, store::updateMemoryEnabled)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("记忆列表", color = colors.textPrimary, fontSize = 19.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
        CloudContentCard {
            Text("还没有记忆", color = colors.textPrimary, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("小云保存新记忆前会先征得你的同意。", color = colors.textSecondary,
                fontSize = 14.sp, lineHeight = 21.sp)
        }
        Text("你可以随时关闭记忆并管理已保存的内容。", color = colors.textSecondary,
            fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
    }
}

@Composable
fun CloudStyleScreen(store: AppStore, onBack: () -> Unit) {
    val colors = LocalWatchColors.current
    var name by rememberSaveable { mutableStateOf(store.cloudName) }
    var personality by rememberSaveable { mutableStateOf(store.cloudPersonality) }
    var description by rememberSaveable { mutableStateOf(store.cloudDescription) }
    CloudSubpage("聊天风格", onBack) {
        Text("让小云更像你喜欢的样子", color = colors.textSecondary,
            fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp))
        CloudContentCard {
            Text("名称", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            CloudStyleField(name, { name = it.take(20) }, "给小云起个名字", singleLine = true)
            Spacer(Modifier.height(8.dp))
            Text("陪伴风格", color = colors.textPrimary, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("开朗", "温柔", "幽默", "成熟").forEach { item ->
                    LiquidGlassChoiceChip(item, personality == item, { personality = item },
                        modifier = Modifier.weight(1f), tint = colors.accent)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("简介", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            CloudStyleField(description, { description = it.take(120) }, "说说小云会怎样陪伴你",
                singleLine = false)
            Text("${description.length}/120", color = colors.textSecondary, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(6.dp))
        LiquidGlassButton("保存", {
            store.updateCloudProfile(name, personality, description)
            onBack()
        }, Modifier.fillMaxWidth(), tint = colors.accent, height = 54.dp)
        Text("设定仅影响当前 App 的显示。", color = colors.textSecondary,
            fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    }
}

@Composable
internal fun CloudSubpage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalWatchColors.current
    LiquidGlassHeaderPage(
        title = title,
        onBack = onBack,
        bounce = true,
        backgroundBrush = Brush.verticalGradient(
            listOf(colors.accentSoft.copy(alpha = .68f), colors.groupedSurface, colors.groupedSurface),
        ),
        contentTopOffset = 70.dp,
        content = content,
    )
}

@Composable
internal fun CloudContentCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalWatchColors.current
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier.fillMaxWidth().background(
            Brush.verticalGradient(listOf(colors.surface.copy(alpha = .94f),
                colors.surface.copy(alpha = .80f))), shape,
        ).border(1.dp, Color.White.copy(alpha = .72f), shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun CloudStyleField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
) {
    val colors = LocalWatchColors.current
    val shape = RoundedCornerShape(18.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().background(colors.surfaceMuted.copy(alpha = .68f), shape)
            .border(1.dp, colors.outline.copy(alpha = .42f), shape)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp, lineHeight = 23.sp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = colors.textSecondary, fontSize = 16.sp)
                inner()
            }
        },
    )
}
