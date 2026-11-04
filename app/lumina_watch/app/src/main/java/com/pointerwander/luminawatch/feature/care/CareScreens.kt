package com.pointerwander.luminawatch.feature.care

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.pointerwander.luminawatch.ui.motionClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.clip
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.pointerwander.luminawatch.ui.LiquidGlassBottomSheet
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.CareGlassToggle
import com.pointerwander.luminawatch.ui.LiquidGlassHeaderPage
import com.pointerwander.luminawatch.ui.LiquidGlassStickyHeader
import com.pointerwander.luminawatch.ui.LiquidGlassSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.app.Screen
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.CareEvent
import com.pointerwander.luminawatch.data.CareKind
import com.pointerwander.luminawatch.feature.health.MentalMoodCalendarSheet
import com.pointerwander.luminawatch.feature.device.WatchSettingsPage
import com.pointerwander.luminawatch.feature.device.WatchSettingsSection
import com.pointerwander.luminawatch.feature.device.WatchSettingsGroup
import com.pointerwander.luminawatch.feature.device.WatchSettingsDivider
import com.pointerwander.luminawatch.feature.device.WatchSettingsIcon
import com.pointerwander.luminawatch.feature.device.WatchSettingsRow
import com.pointerwander.luminawatch.feature.device.WatchSettingsNote
import com.pointerwander.luminawatch.ui.DemoBadge
import com.pointerwander.luminawatch.ui.EmptyState
import com.pointerwander.luminawatch.ui.GlassCard
import com.pointerwander.luminawatch.ui.FrostedCard
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.LiquidGlassChoiceChip
import com.pointerwander.luminawatch.ui.PageColumn
import com.pointerwander.luminawatch.ui.PageHeader
import com.pointerwander.luminawatch.ui.PrimaryButton
import com.pointerwander.luminawatch.ui.SecondaryButton
import com.pointerwander.luminawatch.ui.SectionTitle
import com.pointerwander.luminawatch.ui.SourceTag
import com.pointerwander.luminawatch.ui.SurfaceCard
import com.pointerwander.luminawatch.ui.ToggleRow

internal fun kindIcon(kind: CareKind) = when (kind) {
    CareKind.HEART -> Icons.Rounded.FavoriteBorder
    CareKind.OXYGEN -> Icons.Rounded.Bloodtype
    CareKind.MOOD -> Icons.Rounded.Mood
    CareKind.IDLE -> Icons.Rounded.DirectionsWalk
    CareKind.REMINDER -> Icons.Rounded.NotificationsNone
    CareKind.MANUAL -> Icons.Rounded.Spa
}

@Composable
fun CareScreen(store: AppStore, onOpen: (Screen) -> Unit, onBack: (() -> Unit)? = null) {
    CareHomeScreen(store, onOpen, onBack)
}

@Composable
private fun CareEventCard(event: CareEvent, onClick: () -> Unit) {
    val colors = LocalWatchColors.current
    FrostedCard(Modifier.motionClickable(onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(colors.surfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(kindIcon(event.kind), null, tint = colors.accent, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(event.title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(event.timeLabel + " · " + event.kind.label, color = colors.textSecondary, fontSize = 11.sp)
            }
            if (event.acknowledged) {
                Text("已回应", color = colors.oxygen, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(event.detail, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 19.sp)
        if (event.followUpMinutes != null) {
            Spacer(Modifier.height(4.dp))
            Text("已安排 ${event.followUpMinutes} 分钟后回访", color = colors.accent, fontSize = 11.sp)
        }
    }
}

@Composable
fun CareDetailScreen(
    store: AppStore,
    eventId: Long,
    onBack: () -> Unit,
    onOpen: (Screen) -> Unit,
    onChat: () -> Unit,
) {
    val colors = LocalWatchColors.current
    val event = store.careEvent(eventId)

    PageColumn {
        PageHeader("关怀详情", "触发原因与后续", onBack = onBack)
        DemoBadge()
        if (event == null) {
            EmptyState(title = "记录不存在", body = "这条关怀记录可能已被删除。", actionLabel = "返回", onAction = onBack)
            return@PageColumn
        }

        FrostedCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(kindIcon(event.kind), null, tint = colors.accent, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(event.title, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(event.timeLabel, color = colors.textSecondary, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(event.detail, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 20.sp)
        }

        SectionTitle("这条关怀用了什么")
        SurfaceCard {
            CareInfoRow("触发来源", event.kind.label)
            CareInfoRow("使用的数据", "手表数据尚未接入")
            CareInfoRow("手表动作", "手表连接后可接收震动提醒")
            CareInfoRow("当前状态", if (event.acknowledged) "已回应" else "待回应")
            CareInfoRow("回访安排", event.followUpMinutes?.let { "$it 分钟后" } ?: "未安排")
        }

        SectionTitle("健康上下文授权")
        SurfaceCard {
            Text(
                "默认不把健康数值带进对话。只有你明确同意后，小云才会在这次对话中使用它。",
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(6.dp))
            ToggleRow(
                title = "允许小云使用这次数据",
                detail = if (event.contextAuthorized) "已授权给本次对话" else "未授权",
                checked = event.contextAuthorized,
                onCheckedChange = { store.authorizeContext(event.id, it) },
            )
        }

        SectionTitle("稍后回访")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 30).forEach { minutes ->
                LiquidGlassChoiceChip("${minutes} 分钟", event.followUpMinutes == minutes,
                    { store.setFollowUp(event.id, if (event.followUpMinutes == minutes) null else minutes) })
            }
            LiquidGlassChoiceChip("不回访", event.followUpMinutes == null,
                { store.setFollowUp(event.id, null) })
        }

        PrimaryButton(
            if (event.contextAuthorized) "带着已授权数据和小云聊聊" else "和小云聊聊（不共享健康数值）",
            onChat,
            Modifier.fillMaxWidth(),
        )
        if (!event.acknowledged) {
            SecondaryButton("标记为已处理", {
                store.acknowledgeCare(event.id)
                onBack()
            }, Modifier.fillMaxWidth())
        }
        SecondaryButton("删除这条记录", {
            store.deleteCare(event.id)
            onBack()
        }, Modifier.fillMaxWidth(), tint = colors.danger)
        Text(
            "小云不会根据单次读数给出诊断；如持续不适，请寻求专业帮助。",
            color = colors.textSecondary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun CareInfoRow(label: String, value: String) {
    val colors = LocalWatchColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.width(84.dp))
        Text(value, color = colors.textPrimary, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
fun CareRulesScreen(store: AppStore, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val colors = LocalWatchColors.current
    val prefs = store.preferences
    var showCalendar by remember { mutableStateOf(false) }
    var calendarDate by remember { mutableStateOf(java.time.LocalDate.now().toEpochDay()) }
    WatchSettingsPage("关怀规则", onBack) {
        WatchSettingsSection("提醒设置")
        WatchSettingsGroup {
            CareRuleRow(
                title = "久坐关怀",
                detail = "一段时间没有活动时，小云轻声提醒",
                icon = Icons.Rounded.DirectionsWalk,
                iconColor = Color(0xFF34C759),
                checked = prefs.idleCare,
                onCheckedChange = { checked -> store.updatePreferences { it.copy(idleCare = checked) } },
            )
            WatchSettingsDivider()
            CareRuleRow(
                title = "安静时段",
                detail = "${prefs.quietStart}–${prefs.quietEnd} 减少非紧急打扰",
                icon = Icons.Rounded.Bedtime,
                iconColor = Color(0xFF5856D6),
                checked = prefs.quietHours,
                onCheckedChange = { checked -> store.updatePreferences { it.copy(quietHours = checked) } },
            )
            WatchSettingsDivider()
            CareRuleRow(
                title = "震动提醒",
                detail = "手表连接后才能生效",
                icon = Icons.Rounded.Vibration,
                iconColor = Color(0xFFFF9F0A),
                checked = prefs.vibration,
                onCheckedChange = { checked -> store.updatePreferences { it.copy(vibration = checked) } },
            )
            WatchSettingsDivider()
            CareRuleRow(
                title = "默认允许健康上下文",
                detail = "开启后，新关怀事件默认携带数据；仍可在事件详情里单独关闭",
                icon = Icons.Rounded.HealthAndSafety,
                iconColor = Color(0xFF0A84FF),
                checked = prefs.shareHealthContext,
                onCheckedChange = { checked -> store.updatePreferences { it.copy(shareHealthContext = checked) } },
            )
        }
        WatchSettingsNote("按你的节奏，让小云关心你。")
        WatchSettingsSection("相关记录")
        WatchSettingsGroup {
            WatchSettingsRow("心情月历", Icons.Rounded.DateRange, Color(0xFF6E5CE7)) {
                showCalendar = true
            }
        }
    }
    if (showCalendar) {
        MentalMoodCalendarSheet(
            store = store,
            selectedEpochDay = calendarDate,
            onSelect = { calendarDate = it },
            onRecord = { day ->
                showCalendar = false
                onOpen(Screen.MoodRecord(day))
            },
            onDismiss = { showCalendar = false },
        )
    }
}

@Composable
private fun CareRuleRow(
    title: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalWatchColors.current
    Row(Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }
        .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        WatchSettingsIcon(icon, iconColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(detail, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        CareGlassToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CareRuleDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp).height(1.dp)
        .background(Color(0xFFE7E7EC)))
}
