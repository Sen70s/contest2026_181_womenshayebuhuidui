package com.pointerwander.luminawatch.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.pointerwander.luminawatch.ui.motionClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.app.Screen
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.feature.device.companionBackground
import com.pointerwander.luminawatch.feature.device.companionSecondary
import com.pointerwander.luminawatch.feature.device.companionText
import com.pointerwander.luminawatch.ui.PageColumn
import com.pointerwander.luminawatch.ui.LocalWatchColors

@Composable
fun DiscoverScreen(store: AppStore, onOpen: (Screen) -> Unit) {
    PageColumn(Modifier.background(companionBackground)) {
        Text("发现", color = companionText, fontSize = 34.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp))
        Text("现在开始", color = companionText, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp))
        Column(Modifier.fillMaxWidth()) {
            DiscoverRow(
                Icons.Rounded.Face, "和小云聊聊", "继续和小云对话",
                listOf(Color(0xFFF7B267), Color(0xFFD96C3B)),
            ) { onOpen(Screen.ChatHome) }
            DiscoverDivider()
            DiscoverRow(
                Icons.Rounded.Mood, "记录一次心情", "三步完成，云会随情绪变化",
                listOf(Color(0xFF7BC6FF), Color(0xFF3A7BFF)),
            ) { onOpen(Screen.MoodRecord()) }
            DiscoverDivider()
            DiscoverRow(
                Icons.Rounded.CalendarMonth, "心情月历", "按日期回看、补记与查看同日记录",
                listOf(Color(0xFFFFC46B), Color(0xFFE08A2E)),
            ) { onOpen(Screen.MoodCalendar) }
            DiscoverDivider()
            DiscoverRow(
                Icons.Rounded.FavoriteBorder, "查看健康概览", "心率、血氧与活动，来源清楚",
                listOf(Color(0xFF7BE0C3), Color(0xFF1C9C86)),
            ) { onOpen(Screen.HealthHome) }
        }

        Text("了解更多小云功能", color = companionText, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 18.dp))
        Column(Modifier.fillMaxWidth()) {
            DiscoverRow(
                Icons.Rounded.Spa, "关怀与提醒", "时间线、回访与安静时段",
                listOf(Color(0xFFFF9AB5), Color(0xFFF0466E)),
            ) { onOpen(Screen.CareHome) }
            DiscoverDivider()
            DiscoverRow(
                Icons.Rounded.History, "小云记忆", "查看和管理小云记住的内容",
                listOf(Color(0xFFC6A8FF), Color(0xFF7A54E8)),
            ) { onOpen(Screen.CloudMemory) }
            DiscoverDivider()
            DiscoverRow(
                Icons.Rounded.Mood, "心理健康测评", "独立问卷 · 结果仅供参考",
                listOf(Color(0xFF8BA4F7), Color(0xFF6753C9)),
            ) { onOpen(Screen.MentalAssessment) }
        }

        Text("今日总览", color = companionText, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 18.dp))
        DiscoverRow(
            Icons.Rounded.Today, "今日总览", "下一闹钟、健康、心情与关怀摘要",
            listOf(Color(0xFFFFD37A), Color(0xFFE9A23B)),
        ) { onOpen(Screen.TodayOverview) }

    }
}

@Composable
private fun DiscoverRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    gradient: List<Color>,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().motionClickable(onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(70.dp).clip(RoundedCornerShape(16.dp))
                .background(LocalWatchColors.current.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(gradient)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(27.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = companionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = companionSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun DiscoverDivider() {
    HorizontalDivider(Modifier.padding(start = 82.dp), color = LocalWatchColors.current.outline, thickness = .5.dp)
}
