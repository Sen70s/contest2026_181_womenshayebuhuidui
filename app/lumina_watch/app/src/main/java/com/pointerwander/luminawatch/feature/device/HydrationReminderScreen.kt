package com.pointerwander.luminawatch.feature.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.ui.CareGlassToggle
import com.pointerwander.luminawatch.ui.LiquidGlassBottomSheet
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.TimeWheelPicker
import com.pointerwander.luminawatch.ui.motionClickable
import java.time.format.DateTimeFormatter

private val hydrationAccent = Color(0xFF22B8CF)
private val hydrationDays = listOf("一", "二", "三", "四", "五", "六", "日")
private val hydrationIntervals = listOf(30, 45, 60, 90, 120, 180)

private fun hydrationTime(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
private fun hydrationInterval(minutes: Int): String =
    if (minutes < 60) "每 $minutes 分钟" else if (minutes % 60 == 0) "每 ${minutes / 60} 小时"
    else "每 ${minutes / 60} 小时 ${minutes % 60} 分钟"

private fun hasHydrationNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun HydrationInfoScreen(store: AppStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalWatchColors.current
    val settings = store.hydrationReminder
    var permissionGranted by remember { mutableStateOf(hasHydrationNotificationPermission(context)) }
    var editingTime by remember { mutableStateOf<Boolean?>(null) }
    var editingInterval by remember { mutableStateOf(false) }
    var customInterval by remember { mutableStateOf("") }
    var showCustomInterval by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) store.updateHydrationReminder(store.hydrationReminder.copy(enabled = true))
    }

    WatchSettingsPage("喝水提醒", onBack) {
        WatchSettingsSection("在手机上提醒")
        WatchSettingsGroup {
            Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("喝水提醒", color = colors.textPrimary, fontSize = 16.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                CareGlassToggle(settings.enabled, onCheckedChange = { enabled ->
                    if (enabled && !hasHydrationNotificationPermission(context)) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        permissionGranted = true
                        store.updateHydrationReminder(settings.copy(enabled = enabled))
                    }
                })
            }
        }
        WatchSettingsNote(if (permissionGranted)
            "在选定的日期与时段内发送手机通知。"
            else "开启前需要允许小云发送通知；未授权时不会开启提醒。")

        WatchSettingsSection("提醒计划")
        WatchSettingsGroup {
            HydrationSettingRow("开始时间", hydrationTime(settings.startMinutes)) {
                editingTime = true
            }
            HydrationDivider()
            HydrationSettingRow("结束时间", hydrationTime(settings.endMinutes)) {
                editingTime = false
            }
            HydrationDivider()
            HydrationSettingRow("提醒频率", hydrationInterval(settings.intervalMinutes)) {
                showCustomInterval = false
                editingInterval = true
            }
        }
        WatchSettingsGroup {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("重复", color = colors.textPrimary, fontSize = 16.sp,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    hydrationDays.forEachIndexed { index, day ->
                        val number = index + 1
                        val selected = number in settings.days
                        Box(Modifier.size(37.dp).clip(CircleShape)
                            .background(if (selected) hydrationAccent else Color(0xFFF0F0F4))
                            .motionClickable(onClick = {
                                val newDays = if (selected) settings.days - number
                                else settings.days + number
                                if (newDays.isNotEmpty()) {
                                    store.updateHydrationReminder(settings.copy(days = newDays))
                                }
                            }), contentAlignment = Alignment.Center) {
                            Text(day, color = if (selected) Color.White else colors.textPrimary,
                                fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        val next = settings.nextTime()
        WatchSettingsNote(if (next == null) "关闭后不会发送提醒。"
            else "下次提醒：${next.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}。系统可能因省电设置稍晚送达。")

        WatchSettingsSection("手表")
        WatchSettingsGroup {
            Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("同步至手表", color = colors.textPrimary, fontSize = 16.sp,
                    modifier = Modifier.weight(1f))
                Text("暂不可用", color = colors.textSecondary, fontSize = 14.sp)
            }
        }
        WatchSettingsNote("手表提醒需要设备协议支持；目前设置只在手机上生效。")
    }

    editingTime?.let { isStart ->
        HydrationTimeSheet(
            title = if (isStart) "开始时间" else "结束时间",
            initialMinutes = if (isStart) settings.startMinutes else settings.endMinutes,
            onDismiss = { editingTime = null },
            onSave = { minutes ->
                if (isStart) store.updateHydrationReminder(settings.copy(startMinutes = minutes))
                else store.updateHydrationReminder(settings.copy(endMinutes = minutes))
                editingTime = null
            },
            valid = { minutes ->
                if (isStart) minutes + 30 <= settings.endMinutes
                else minutes >= settings.startMinutes + 30
            },
        )
    }
    if (editingInterval) {
        LiquidGlassBottomSheet(onDismiss = { editingInterval = false },
            surfaceColor = watchSettingsBackground, translucent = true,
            showNavigation = false) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(5.dp)
                .clip(RoundedCornerShape(3.dp)).background(Color(0xFFC8C8CE)))
            Spacer(Modifier.height(20.dp))
            Text("提醒频率", color = colors.textPrimary, fontSize = 23.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = .82f))) {
                Column(Modifier.fillMaxWidth().heightIn(max = 288.dp)
                    .verticalScroll(rememberScrollState())) {
                    hydrationIntervals.forEachIndexed { index, minutes ->
                        val selected = settings.intervalMinutes == minutes
                        Row(Modifier.fillMaxWidth().height(58.dp)
                            .motionClickable(onClick = {
                                store.updateHydrationReminder(settings.copy(intervalMinutes = minutes))
                                editingInterval = false
                            }).padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(hydrationInterval(minutes), color = colors.textPrimary,
                                fontSize = 16.sp, modifier = Modifier.weight(1f))
                            if (selected) Text("✓", color = hydrationAccent,
                                fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        if (index < hydrationIntervals.lastIndex) HydrationDivider()
                    }
                }
                HydrationDivider()
                Row(Modifier.fillMaxWidth().height(58.dp)
                    .motionClickable(onClick = {
                        if (!showCustomInterval && settings.intervalMinutes !in hydrationIntervals) {
                            customInterval = settings.intervalMinutes.toString()
                        }
                        showCustomInterval = !showCustomInterval
                    }).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("自定义", color = colors.textPrimary, fontSize = 16.sp,
                        modifier = Modifier.weight(1f))
                    if (settings.intervalMinutes !in hydrationIntervals && !showCustomInterval) {
                        Text(hydrationInterval(settings.intervalMinutes),
                            color = colors.textSecondary, fontSize = 14.sp)
                    }
                    Text(if (showCustomInterval) "⌄" else "›", color = colors.textSecondary,
                        fontSize = 23.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (showCustomInterval) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().height(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = .82f))
                    .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("间隔", color = colors.textPrimary, fontSize = 16.sp,
                        modifier = Modifier.weight(1f))
                    BasicTextField(customInterval, onValueChange = {
                        customInterval = it.filter(Char::isDigit).take(3)
                    }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary,
                            fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                        modifier = Modifier.width(64.dp),
                        decorationBox = { inner ->
                            if (customInterval.isEmpty()) Text("输入", color = colors.textSecondary,
                                fontSize = 15.sp)
                            inner()
                        })
                    Text("分钟", color = colors.textSecondary, fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(12.dp))
                val customValue = customInterval.toIntOrNull()
                LiquidGlassButton("应用自定义频率", {
                    if (customValue != null) {
                        store.updateHydrationReminder(settings.copy(intervalMinutes = customValue))
                        editingInterval = false
                    }
                }, Modifier.fillMaxWidth(), enabled = customValue in 30..360,
                    tint = hydrationAccent)
                WatchSettingsNote("自定义范围：30–360 分钟。")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HydrationSettingRow(title: String, value: String, onClick: () -> Unit) {
    val colors = LocalWatchColors.current
    Row(Modifier.fillMaxWidth().height(57.dp).motionClickable(onClick = onClick)
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = colors.textPrimary, fontSize = 16.sp,
            modifier = Modifier.weight(1f))
        Text(value, color = colors.textSecondary, fontSize = 15.sp)
        Text("›", color = Color(0xFFC7C7CC), fontSize = 25.sp,
            modifier = Modifier.padding(start = 9.dp))
    }
}

@Composable
private fun HydrationDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp)
        .background(Color(0xFFE6E6EA)))
}

@Composable
private fun HydrationTimeSheet(
    title: String,
    initialMinutes: Int,
    valid: (Int) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val colors = LocalWatchColors.current
    var hour by remember(initialMinutes) { mutableIntStateOf(initialMinutes / 60) }
    var minute by remember(initialMinutes) { mutableIntStateOf(initialMinutes % 60) }
    val selectedMinutes = hour * 60 + minute
    LiquidGlassBottomSheet(onDismiss = onDismiss, surfaceColor = watchSettingsBackground,
        translucent = true) {
        Text(title, color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        WatchSettingsGroup {
            TimeWheelPicker(hour = hour, minute = minute,
                onHourChange = { hour = it }, onMinuteChange = { minute = it })
        }
        Spacer(Modifier.height(10.dp))
        if (!valid(selectedMinutes)) {
            Text("开始与结束时间至少间隔 30 分钟。", color = colors.danger, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }
        LiquidGlassButton("完成", { onSave(selectedMinutes) }, Modifier.fillMaxWidth(),
            enabled = valid(selectedMinutes), tint = hydrationAccent)
        Spacer(Modifier.height(18.dp))
    }
}
