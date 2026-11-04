package com.pointerwander.luminawatch.feature.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.pointerwander.luminawatch.app.Screen
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.AddAlarmResult
import com.pointerwander.luminawatch.data.Alarm
import com.pointerwander.luminawatch.data.MAX_ALARMS
import com.pointerwander.luminawatch.ui.CloudImage
import com.pointerwander.luminawatch.ui.CloudAvatars
import com.pointerwander.luminawatch.ui.CareGlassToggle
import com.pointerwander.luminawatch.ui.DemoBadge
import com.pointerwander.luminawatch.ui.EmptyState
import com.pointerwander.luminawatch.ui.FrostedCard
import com.pointerwander.luminawatch.ui.GlassCard
import com.pointerwander.luminawatch.ui.InfoRow
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassConfirmDialog
import com.pointerwander.luminawatch.ui.LiquidGlassSurface
import com.pointerwander.luminawatch.ui.LiquidGlassHeaderPage
import com.pointerwander.luminawatch.ui.PageColumn
import com.pointerwander.luminawatch.ui.PageHeader
import com.pointerwander.luminawatch.ui.PrimaryButton
import com.pointerwander.luminawatch.ui.SecondaryButton
import com.pointerwander.luminawatch.ui.SectionTitle
import com.pointerwander.luminawatch.ui.SourceTag
import com.pointerwander.luminawatch.ui.SurfaceCard
import com.pointerwander.luminawatch.ui.TimeWheelPicker
import com.pointerwander.luminawatch.ui.ToggleRow
import com.pointerwander.luminawatch.ui.WatchSwitch
import com.pointerwander.luminawatch.ui.motionClickable

private val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

private fun repeatText(days: Set<Int>): String = when {
    days.isEmpty() -> "仅一次"
    days.size == 7 -> "每天"
    days.containsAll(setOf(1, 2, 3, 4, 5)) && days.size == 5 -> "工作日"
    days.containsAll(setOf(6, 7)) && days.size == 2 -> "周末"
    else -> days.sorted().joinToString(" ") { "周${dayLabels[it - 1]}" }
}

@Composable
fun DeviceScreen(
    store: AppStore,
    onOpen: (Screen) -> Unit,
    onBack: (() -> Unit)? = null,
    onGallery: () -> Unit = {},
) {
    val device = store.device
    WatchSettingsPage("连接与同步", onBack ?: {}) {
        WatchSettingsSection("我的手表")
        WatchSettingsGroup {
            WatchSettingsRow(device.name, Icons.Rounded.Watch, Color(0xFF0A84FF),
                if (device.connected) "已连接" else "未连接") { onOpen(Screen.AllWatches) }
            WatchSettingsDivider()
            WatchSettingsRow(if (device.bound) "重新绑定" else "添加手表", Icons.Rounded.Add,
                Color(0xFF34C759)) { onOpen(Screen.Binding) }
        }
        WatchSettingsNote(if (device.bound) "ID 尾号 ${device.tailId} · 设备信息保存在本机"
            else "尚未添加手表")

        WatchSettingsSection("连接状态")
        WatchSettingsGroup {
            WatchSettingsRow("手机与手表", Icons.Rounded.Watch, Color(0xFF0A84FF),
                if (device.connected) "已连接" else "未连接")
            WatchSettingsDivider()
            WatchSettingsRow("手表与云岸", Icons.Rounded.Refresh, Color(0xFF30B0C7), "尚未接入")
            WatchSettingsDivider()
            WatchSettingsRow("电量", Icons.Rounded.Info, Color(0xFF34C759),
                device.battery?.let { "$it%" } ?: "未知")
        }
        if (device.connected) {
            WatchSettingsGroup {
                WatchSettingsRow("同步数据", Icons.Rounded.Refresh, Color(0xFF0A84FF)) {
                    store.syncDevice()
                }
            }
        }
        device.lastSyncLabel?.let { WatchSettingsNote(it) }
        WatchSettingsNote("连接协议尚未接入；保存设备 ID 不代表已完成蓝牙配对。")
    }
}

@Composable
fun BindingScreen(store: AppStore, onBack: () -> Unit) {
    val colors = LocalWatchColors.current
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showUnbind by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    val device = store.device

    LiquidGlassHeaderPage(title = "添加手表", onBack = onBack, bounce = true) {
        Text(
            "手动输入 ID 或通过蓝牙搜索",
            color = colors.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )

        if (device.bound) {
            BindingCard {
                Text("当前手表", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                InfoRow("名称", device.name)
                InfoRow("唯一 ID", "•••• •••• ${device.tailId}")
                InfoRow("连接", if (device.connected) "已连接" else "未连接")
                Spacer(Modifier.height(6.dp))
                Text(
                    "设备信息已保存在本机；真实蓝牙配对与同步尚未接入。",
                    color = colors.textSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                BindingActionButton("添加另一台手表", Icons.Rounded.Add,
                    Color(0xFF0A6CFF)) { showAdd = true }
                Spacer(Modifier.height(8.dp))
                BindingActionButton("解除当前手表", Icons.Rounded.DeleteOutline,
                    colors.danger) { showUnbind = true }
            }
        }

        if (!device.bound || showAdd) {
            BindingCard {
                Text("手动输入手表唯一 ID", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "请输入 8–16 位字母或数字。只保存本机设备信息，不代表已完成真实配对。",
                    color = colors.textSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    singleLine = true,
                    label = { Text("手表 ID") },
                    placeholder = { Text("输入设备 ID") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error!!, color = colors.danger, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("保存设备信息", {
                    if (store.bindDevice(input)) {
                        error = null
                        onBack()
                    } else {
                        error = "请输入 8–16 位字母或数字的设备 ID。"
                    }
                }, Modifier.fillMaxWidth(), tint = Color(0xFF0A6CFF), fillAlpha = .84f)
            }

            BindingCard {
                BluetoothWatchSection { name, address ->
                    store.addBluetoothWatch(name, address)
                    onBack()
                }
            }

            BindingCard {
                Text("添加后会发生什么", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "新记录会加入「所有手表」列表；切换查看只改变当前设备，不会自动建立蓝牙连接，也不会伪造数据同步。",
                    color = colors.textSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                )
            }
        }
    }

    if (showUnbind) {
        LiquidGlassConfirmDialog(
            title = "解除当前手表？",
            message = "只会从「所有手表」移除这台设备记录，不影响其他手表、心情、闹钟与关怀记录。",
            confirmLabel = "解除",
            onConfirm = { store.unbindDevice(); showUnbind = false },
            onDismiss = { showUnbind = false },
            confirmTint = colors.danger,
        )
    }
}

@Composable
private fun BindingCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun BindingActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    LiquidGlassSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        filled = true,
        tint = tint,
        height = 50.dp,
        fillAlpha = .84f,
        border = Color.White.copy(alpha = .38f),
    ) { ink ->
        androidx.compose.material3.Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Text(label, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AlarmListScreen(store: AppStore, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val colors = LocalWatchColors.current
    val alarms = store.alarms.sortedWith(compareBy({ it.hour }, { it.minute }))
    var editing by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val orange = Color(0xFFFF9500)

    AlarmPage("", onBack, { backdrop ->
        if (alarms.isNotEmpty()) {
            LiquidGlassSurface(
                onClick = {
                    editing = !editing
                    pendingDeleteId = null
                },
                modifier = Modifier.width(66.dp),
                height = 48.dp,
                tint = orange,
                backdrop = backdrop,
                glassAlpha = .35f,
                border = Color(0xFFC5C7CE).copy(alpha = .75f),
                contentPadding = 0.dp,
                blurRadius = 8.dp,
                refractionHeight = 6.dp,
                refractionAmount = 8.dp,
                chromaticAberration = false,
            ) { ink ->
                Text(if (editing) "完成" else "编辑", color = ink, fontSize = 16.sp,
                    fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(8.dp))
        }
        LiquidGlassSurface(
            onClick = { onOpen(Screen.AlarmEdit(null)) },
            modifier = Modifier.width(48.dp),
            enabled = alarms.size < MAX_ALARMS,
            height = 48.dp,
            tint = orange,
            backdrop = backdrop,
            glassAlpha = .35f,
            border = Color(0xFFC5C7CE).copy(alpha = .75f),
            contentPadding = 0.dp,
            blurRadius = 8.dp,
            refractionHeight = 6.dp,
            refractionAmount = 8.dp,
            chromaticAberration = false,
        ) { ink ->
            Icon(Icons.Rounded.Add, contentDescription = "添加闹钟",
                tint = ink, modifier = Modifier.size(25.dp))
        }
    }) {
        Text("闹钟", color = colors.textPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 2.dp, top = 9.dp, bottom = 4.dp))
        if (store.lastDeletedAlarm != null) {
            WatchSettingsGroup {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已删除 ${store.lastDeletedAlarm!!.timeText}", color = colors.textSecondary,
                        fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 18.dp))
                    Text("撤销", color = orange, fontSize = 15.sp,
                        modifier = Modifier.motionClickable(onClick = { store.undoDeleteAlarm() })
                            .padding(horizontal = 18.dp, vertical = 14.dp))
                }
            }
        }
        if (alarms.isEmpty()) {
            WatchSettingsGroup {
                Text("没有闹钟", color = colors.textSecondary, fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp))
            }
        } else {
            WatchSettingsGroup {
                alarms.forEachIndexed { index, alarm ->
                    if (index > 0) Box(Modifier.fillMaxWidth().padding(start = 18.dp)
                        .height(1.dp).background(Color(0xFFE6E6EA)))
                    Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (editing) {
                            Box(Modifier.size(27.dp).clip(CircleShape)
                                .background(Color(0xFFFF3B30))
                                .motionClickable(onClick = {
                                    pendingDeleteId = if (pendingDeleteId == alarm.id) null else alarm.id
                                }),
                                contentAlignment = Alignment.Center) {
                                Text("−", color = Color.White, fontSize = 23.sp, lineHeight = 23.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f).motionClickable(onClick = {
                            onOpen(Screen.AlarmEdit(alarm.id))
                        })) {
                            Text(alarm.timeText,
                                color = if (alarm.enabled) colors.textPrimary else colors.textSecondary,
                                fontSize = 37.sp, fontWeight = FontWeight.Light, lineHeight = 42.sp)
                            Text("${alarm.label}，${repeatText(alarm.repeatDays)}",
                                color = colors.textSecondary, fontSize = 13.sp)
                        }
                        if (editing && pendingDeleteId == alarm.id) {
                            Box(Modifier.clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFF3B30))
                                .motionClickable(onClick = {
                                    store.deleteAlarm(alarm.id)
                                    pendingDeleteId = null
                                }).padding(horizontal = 13.dp, vertical = 9.dp)) {
                                Text("删除", color = Color.White, fontSize = 14.sp)
                            }
                        } else if (!editing) CareGlassToggle(
                            checked = alarm.enabled,
                            onCheckedChange = { store.updateAlarm(alarm.copy(enabled = it, synced = false)) },
                        )
                    }
                }
            }
        }
        WatchSettingsNote(if (alarms.size >= MAX_ALARMS)
            "最多可添加 $MAX_ALARMS 个闹钟。闹钟保存在本机，尚未同步到手表。"
            else "闹钟保存在本机，尚未同步到手表。")
    }
}

@Composable
private fun AlarmPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.(Backdrop) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    LiquidGlassHeaderPage(title, onBack, bounce = true,
        backgroundBrush = Brush.verticalGradient(listOf(watchSettingsBackground, watchSettingsBackground)),
        contentTopOffset = 68.dp, actions = { backdrop -> actions(backdrop) }) {
        content()
    }
}

@Composable
fun AlarmEditScreen(store: AppStore, alarmId: Long?, onBack: () -> Unit) {
    val colors = LocalWatchColors.current
    val existing = remember(alarmId) { store.alarms.firstOrNull { it.id == alarmId } }
    var hour by rememberSaveable { mutableIntStateOf(existing?.hour ?: 7) }
    var minute by rememberSaveable { mutableIntStateOf(existing?.minute ?: 30) }
    var label by rememberSaveable { mutableStateOf(existing?.label ?: "闹钟") }
    var days by remember { mutableStateOf(existing?.repeatDays ?: emptySet<Int>()) }
    var vibrate by remember { mutableStateOf(existing?.vibrate ?: true) }
    var announce by remember { mutableStateOf(existing?.announce ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    val orange = Color(0xFFFF9500)
    val save: () -> Unit = {
        if (existing == null) {
            when (store.addAlarm(hour, minute, label, days, vibrate, announce)) {
                AddAlarmResult.OK -> onBack()
                AddAlarmResult.LIMIT -> error = "最多只能创建 $MAX_ALARMS 个闹钟。"
                AddAlarmResult.INVALID -> error = "时间格式不正确。"
            }
        } else {
            store.updateAlarm(existing.copy(hour = hour, minute = minute,
                label = label.trim().ifEmpty { "闹钟" }, repeatDays = days,
                vibrate = vibrate, announce = announce, synced = false))
            onBack()
        }
    }

    AlarmPage(if (existing == null) "添加闹钟" else "编辑闹钟", onBack, { backdrop ->
        LiquidGlassSurface(
            onClick = save,
            modifier = Modifier.width(66.dp),
            height = 48.dp,
            tint = orange,
            backdrop = backdrop,
            glassAlpha = .35f,
            border = Color(0xFFC5C7CE).copy(alpha = .75f),
            contentPadding = 0.dp,
            blurRadius = 8.dp,
            refractionHeight = 6.dp,
            refractionAmount = 8.dp,
            chromaticAberration = false,
        ) { ink ->
            Text("存储", color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }) {
        WatchSettingsGroup {
            Spacer(Modifier.height(8.dp))
            TimeWheelPicker(
                hour = hour,
                minute = minute,
                onHourChange = { hour = it; error = null },
                onMinuteChange = { minute = it; error = null },
            )
            Spacer(Modifier.height(8.dp))
        }
        WatchSettingsGroup {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("重复", color = colors.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(repeatText(days), color = colors.textSecondary, fontSize = 15.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 18.dp)
                .background(Color(0xFFE6E6EA)))
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                dayLabels.forEachIndexed { index, day ->
                    val value = index + 1
                    val selected = days.contains(value)
                    Box(Modifier.size(37.dp).clip(CircleShape)
                        .background(if (selected) orange else Color(0xFFF1F1F5))
                        .motionClickable(onClick = { days = if (selected) days - value else days + value }),
                        contentAlignment = Alignment.Center) {
                        Text(day, color = if (selected) Color.White else colors.textPrimary,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 18.dp)
                .background(Color(0xFFE6E6EA)))
            Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("标签", color = colors.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                BasicTextField(value = label, onValueChange = { label = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = colors.textSecondary,
                        fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                    modifier = Modifier.width(180.dp))
            }
        }
        WatchSettingsGroup {
            AlarmToggleRow("震动", vibrate) { vibrate = it }
            Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 18.dp)
                .background(Color(0xFFE6E6EA)))
            AlarmToggleRow("小云播报", announce) { announce = it }
        }
        WatchSettingsNote("震动和小云播报需连接手表后才能生效。")
        if (error != null) Text(error!!, color = colors.danger, fontSize = 13.sp)
        if (existing != null) {
            WatchSettingsGroup {
                Box(Modifier.fillMaxWidth().height(54.dp).motionClickable(onClick = {
                    store.deleteAlarm(existing.id)
                    onBack()
                }), contentAlignment = Alignment.Center) {
                    Text("删除闹钟", color = Color(0xFFFF3B30), fontSize = 16.sp)
                }
            }
        }
        WatchSettingsNote("闹钟保存在本机，尚未同步到手表。")
    }
}

@Composable
private fun AlarmToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalWatchColors.current
    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = colors.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        CareGlassToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun AppearanceScreen(store: AppStore, onBack: () -> Unit, onGallery: () -> Unit) {
    val colors = LocalWatchColors.current
    val selectedAvatar = CloudAvatars.find(store.avatarId)
    val selectedIndex = CloudAvatars.all.indexOfFirst { it.id == store.avatarId }.coerceAtLeast(0)
    val avatarListState = rememberLazyListState(initialFirstVisibleItemIndex =
        (selectedIndex - 2).coerceAtLeast(0))

    WatchSettingsPage("小云形象", onBack) {
        WatchSettingsSection("当前形象")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFFE2E7FF),
                    Color(0xFFF1F4FF), Color.White.copy(alpha = .82f))))
                .border(1.dp, Color.White.copy(alpha = .85f), RoundedCornerShape(28.dp))
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(150.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = .58f)), contentAlignment = Alignment.Center) {
                CloudImage(Modifier.size(122.dp), avatarId = selectedAvatar.id)
            }
            Spacer(Modifier.height(14.dp))
            Text(selectedAvatar.label, color = colors.textPrimary, fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold)
            Text("用于首页与小云对话", color = colors.textSecondary, fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp))
        }

        WatchSettingsSection("选择形象")
        LazyRow(state = avatarListState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            items(CloudAvatars.all, key = { it.id }) { avatar ->
                val selected = store.avatarId == avatar.id
                Column(Modifier.width(78.dp).motionClickable(onClick = {
                    store.updateAvatar(avatar.id)
                }), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(72.dp)
                        .background(if (selected) Color(0xFFE2F0FF) else Color.White.copy(alpha = .80f),
                            CircleShape)
                        .border(if (selected) 2.dp else 1.dp,
                            if (selected) Color(0xFF0A84FF) else Color(0xFFE3E3E8), CircleShape),
                        contentAlignment = Alignment.Center) {
                        CloudImage(Modifier.size(55.dp), avatarId = avatar.id)
                        if (selected) {
                            Box(Modifier.align(Alignment.BottomEnd).size(19.dp)
                                .clip(CircleShape).background(Color(0xFF0A84FF)),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, tint = Color.White,
                                    modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                    Text(avatar.label, color = if (selected) colors.textPrimary else colors.textSecondary,
                        fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp))
                }
            }
        }
        WatchSettingsNote("左右滑动查看更多，点按即可切换形象。")
        WatchSettingsSection("表盘")
        WatchSettingsGroup {
            WatchSettingsRow("表盘图库", Icons.Rounded.Palette, Color(0xFFDE4EAB),
                "更换配色与表情", onGallery)
        }
        WatchSettingsNote("表盘选择目前只改变手机预览，尚未下发到手表。")
    }
}

@Composable
fun SettingsScreen(store: AppStore, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val colors = LocalWatchColors.current
    var showReset by remember { mutableStateOf(false) }
    val prefs = store.preferences

    WatchSettingsPage("个人与隐私", onBack) {
        PrivacySection("外观")
        WatchSettingsGroup {
            PrivacyRow("显示模式", Icons.Rounded.Palette, Color(0xFF8E8E93), "浅色")
        }

        PrivacySection("共享与隐私")
        WatchSettingsGroup {
            PrivacyToggleRow("家人共享",
                if (prefs.familySharing) "偏好已保存；共享服务尚未接入" else "未授权；家人无法查看数据",
                prefs.familySharing) { checked ->
                store.updatePreferences { pref -> pref.copy(familySharing = checked) }
            }
            WatchSettingsDivider()
            PrivacyToggleRow("默认共享健康上下文", "关闭后对话不会自动携带健康数值",
                prefs.shareHealthContext) { checked ->
                store.updatePreferences { pref -> pref.copy(shareHealthContext = checked) }
            }
        }

        PrivacySection("健康与设备")
        WatchSettingsGroup {
            PrivacyToggleRow("显示健康数据", "关闭后健康卡片显示无数据状态",
                store.showHealthData) { store.updateHealthDataVisibility(it) }
            WatchSettingsDivider()
            PrivacyRow("添加手表", Icons.Rounded.Watch, Color(0xFF0A84FF), onClick = {
                onOpen(Screen.Binding)
            })
        }

        PrivacySection("应用")
        WatchSettingsGroup {
            PrivacyRow("关于 LuminaWatch", Icons.Rounded.Info, Color(0xFF8E8E93), "0.1.0")
            WatchSettingsDivider()
            PrivacyRow("重置本机数据", Icons.Rounded.DeleteOutline, Color(0xFFFF3B30), onClick = {
                showReset = true
            })
        }
        Text("连接、传感器记录与云岸服务尚未接入。", color = colors.textSecondary,
            fontSize = 14.sp, lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
    }

    if (showReset) {
        LiquidGlassConfirmDialog(
            title = "重置本机数据？",
            message = "会清空闹钟、心情、关怀、对话与设备绑定，并回到欢迎页。",
            confirmLabel = "重置",
            onConfirm = { showReset = false; store.resetDemo() },
            onDismiss = { showReset = false },
            confirmTint = colors.danger,
        )
    }
}

@Composable
private fun PrivacySection(title: String) {
    val colors = LocalWatchColors.current
    Text(title, color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 6.dp, top = 24.dp, bottom = 10.dp))
}

@Composable
private fun PrivacyToggleRow(title: String, detail: String, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalWatchColors.current
    Row(Modifier.fillMaxWidth().height(86.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 17.sp,
                fontWeight = FontWeight.Medium, lineHeight = 23.sp)
            Spacer(Modifier.height(4.dp))
            Text(detail, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(14.dp))
        CareGlassToggle(checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PrivacyRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color, value: String? = null, onClick: (() -> Unit)? = null) {
    val colors = LocalWatchColors.current
    Row(Modifier.fillMaxWidth().height(70.dp)
        .then(if (onClick == null) Modifier else Modifier.motionClickable(onClick = onClick))
        .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        WatchSettingsIcon(icon, color)
        Spacer(Modifier.width(16.dp))
        Text(title, color = colors.textPrimary, fontSize = 17.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = colors.textSecondary, fontSize = 15.sp)
        if (onClick != null) Text("›", color = colors.textSecondary, fontSize = 26.sp,
            modifier = Modifier.padding(start = 10.dp))
    }
}
