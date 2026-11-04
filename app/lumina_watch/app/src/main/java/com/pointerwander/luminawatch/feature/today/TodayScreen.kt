package com.pointerwander.luminawatch.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import com.pointerwander.luminawatch.ui.motionClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.app.Screen
import com.pointerwander.luminawatch.app.Tab
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.CareKind
import com.pointerwander.luminawatch.data.Metric
import com.pointerwander.luminawatch.ui.CloudImage
import com.pointerwander.luminawatch.ui.DemoBadge
import com.pointerwander.luminawatch.ui.FrostedCard
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.MetricTile
import com.pointerwander.luminawatch.ui.PageColumn
import com.pointerwander.luminawatch.ui.PageHeader
import com.pointerwander.luminawatch.ui.SectionTitle
import com.pointerwander.luminawatch.ui.SourceTag
import com.pointerwander.luminawatch.ui.SurfaceCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodayScreen(
    store: AppStore,
    onOpen: (Screen) -> Unit,
    onOpenChat: () -> Unit,
    onOpenCare: () -> Unit,
    onOpenDevice: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val colors = LocalWatchColors.current
    val dateText = LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
    val pendingCare = store.careEvents.firstOrNull { !it.acknowledged }
    val nextAlarm = store.nextAlarm()
    val todayMood = store.todayMoods().firstOrNull()
    val heart = store.latestSample(Metric.HEART_RATE)
    val oxygen = store.latestSample(Metric.OXYGEN)

    PageColumn {
        PageHeader(
            title = "你好，今天也有小云",
            subtitle = dateText,
            onBack = onBack,
        )
        DemoBadge()

        FrostedCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("小云正在陪伴你", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "今天想聊些什么？\n我一直在这里。",
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                }
                Box(
                    Modifier.size(104.dp).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(colors.surface, colors.accentSoft))),
                    contentAlignment = Alignment.Center,
                ) {
                    CloudImage(Modifier.size(92.dp), avatarId = store.avatarId)
                }
            }
            Spacer(Modifier.height(12.dp))
            com.pointerwander.luminawatch.ui.PrimaryButton(
                "和小云聊聊",
                { store.activeCareContextId = null; onOpenChat() },
                Modifier.fillMaxWidth(),
                Icons.AutoMirrored.Rounded.Chat,
            )
        }

        if (pendingCare != null) {
            FrostedCard(Modifier.clickable { onOpen(Screen.CareDetail(pendingCare.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(colors.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Spa, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pendingCare.title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(pendingCare.timeLabel, color = colors.textSecondary, fontSize = 12.sp)
                    }
                    SourceTag("待回应", colors.accent)
                }
                Spacer(Modifier.height(8.dp))
                Text(pendingCare.detail, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }

        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FrostedCard(Modifier.weight(1f).fillMaxHeight().clickable { onOpen(Screen.AlarmList) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Alarm, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("下一闹钟", color = colors.textSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    nextAlarm?.timeText ?: "--:--",
                    color = colors.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    nextAlarm?.label ?: "尚未添加",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            FrostedCard(Modifier.weight(1f).fillMaxHeight().clickable { onOpenDevice() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Watch, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("手表", color = colors.textSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (store.device.connected) (store.device.battery?.let { "$it%" } ?: "已连接")
                    else if (store.device.bound) "已保存" else "未绑定",
                    color = colors.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (store.device.bound) "待连接手表" else "连接手表",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }

        SectionTitle("今天的身体状态", "心率详情") { onOpen(Screen.HeartDetail) }
        FrostedCard(Modifier.clickable { onOpen(Screen.HeartDetail) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetricIcon(Icons.Rounded.FavoriteBorder, colors.heart)
                Spacer(Modifier.width(10.dp))
                Text("心率变化", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            if (heart != null) {
                MiniTrend(listOf(68f, 72f, 70f, 74f, 71f, 82f, 86f, 79f, 104f, 88f, 80f, 78f), colors.heart)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MiniStat("平均", "79", "次/分")
                    MiniStat("最高", "104", "次/分")
                    MiniStat("最低", "68", "次/分")
                }
            } else {
                Text("连接手表后显示心率趋势", color = colors.textSecondary, fontSize = 13.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TodayMetricCard("活动消耗", "197", "千卡", Icons.Rounded.LocalFireDepartment,
                Color(0xFFFF7043), listOf(.08f, .16f, .1f, .6f, .8f, .35f, .2f),
                Modifier.weight(1f)) { onOpen(Screen.ActivityDetail("活动消耗")) }
            TodayMetricCard("血氧", oxygen?.value?.toString() ?: "--", "%", Icons.Rounded.Bloodtype,
                colors.oxygen, listOf(.72f, .82f, .7f, .92f, .76f, .86f, .78f),
                Modifier.weight(1f)) { onOpen(Screen.OxygenDetail) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TodayMetricCard("站立时间", "8", "次", Icons.Rounded.AccessibilityNew,
                Color(0xFF16A7C9), listOf(.1f, .2f, .75f, .8f, .82f, .72f, .62f),
                Modifier.weight(1f)) { onOpen(Screen.ActivityDetail("站立时间")) }
            TodayMetricCard("步数", "2,847", "步", Icons.Rounded.DirectionsWalk,
                Color(0xFFFF9F43), listOf(.04f, .1f, .75f, .3f, .65f, .2f, .15f),
                Modifier.weight(1f)) { onOpen(Screen.ActivityDetail("步数")) }
        }

        FrostedCard(Modifier.clickable { onOpen(Screen.MoodCalendar) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("今日心情", color = colors.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            todayMood == null -> "还没有记录，写下此刻感受"
                            todayMood.value >= .66f -> "今天的心情偏明亮"
                            todayMood.value >= .34f -> "今天的心情比较平静"
                            else -> "今天的心情有些低落"
                        },
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (todayMood != null) {
                        Text(
                            todayMood.words.joinToString(" · ") + " · " + todayMood.timeLabel,
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
                com.pointerwander.luminawatch.ui.PrimaryButton(
                    text = if (todayMood == null) "记录" else "再看",
                    onClick = { onOpen(Screen.MoodRecord()) },
                )
            }
        }

        SectionTitle("小云关心过你", "时间线") { onOpenCare() }
        if (store.careEvents.isEmpty()) {
            com.pointerwander.luminawatch.ui.EmptyState(
                title = "今天暂无关怀记录",
                body = "有新记录时会出现在这里。",
            )
        } else {
            store.careEvents.take(2).forEach { event ->
                FrostedCard(Modifier.clickable { onOpen(Screen.CareDetail(event.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(colors.surfaceMuted),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                when (event.kind) {
                                    CareKind.HEART -> Icons.Rounded.FavoriteBorder
                                    else -> Icons.Rounded.Spa
                                },
                                null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(event.title, color = colors.textPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(event.timeLabel, color = colors.textSecondary, fontSize = 11.sp)
                        }
                        if (event.acknowledged) {
                            Text("已回应", color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun MetricIcon(icon: ImageVector, color: Color) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun MiniStat(label: String, value: String, unit: String) {
    val colors = LocalWatchColors.current
    Column {
        Text(label, color = colors.textSecondary, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(2.dp))
            Text(unit, color = colors.textSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun MiniTrend(values: List<Float>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        if (values.size < 2) return@Canvas
        val min = values.minOrNull() ?: 0f
        val max = values.maxOrNull() ?: 1f
        val range = (max - min).coerceAtLeast(1f)
        val line = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1)
            val y = size.height * (.88f - .7f * (value - min) / range)
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        drawPath(line, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun TodayMetricCard(
    title: String,
    value: String,
    unit: String,
    icon: ImageVector,
    color: Color,
    bars: List<Float>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalWatchColors.current
    FrostedCard(modifier = modifier.motionClickable(onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Icon(icon, null, tint = color, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.height(13.dp))
        Canvas(Modifier.fillMaxWidth().height(46.dp)) {
            val gap = 5.dp.toPx()
            val width = (size.width - gap * (bars.size - 1)) / bars.size
            bars.forEachIndexed { index, level ->
                val height = size.height * level.coerceIn(.06f, 1f)
                drawRoundRect(
                    color.copy(alpha = if (index == bars.lastIndex) .95f else .6f),
                    topLeft = Offset(index * (width + gap), size.height - height),
                    size = androidx.compose.ui.geometry.Size(width, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = colors.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(3.dp))
            Text(unit, color = colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
