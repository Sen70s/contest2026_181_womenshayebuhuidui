package com.pointerwander.luminawatch.feature.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pointerwander.luminawatch.app.Screen
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.WatchDevice
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassBottomSheet
import com.pointerwander.luminawatch.ui.LiquidGlassIconButton
import com.pointerwander.luminawatch.ui.LiquidGlassPanel
import com.pointerwander.luminawatch.ui.LiquidGlassHeaderPage
import com.pointerwander.luminawatch.ui.LiquidGlassTopBarIconButton
import com.pointerwander.luminawatch.ui.PageColumn
import com.pointerwander.luminawatch.ui.PageHeader
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.motionClickable
import com.pointerwander.luminawatch.ui.CloudAvatars
import com.pointerwander.luminawatch.ui.CloudImage

internal val companionBackground: Color @Composable get() = LocalWatchColors.current.background
internal val companionGroup: Color @Composable get() = LocalWatchColors.current.groupedSurface
internal val companionSecondary: Color @Composable get() = LocalWatchColors.current.textSecondary
internal val companionAccent: Color @Composable get() = LocalWatchColors.current.accent
internal val companionText: Color @Composable get() = LocalWatchColors.current.textPrimary
private data class FaceStyle(val name: String, val category: String, val description: String)
private val faceStyles = listOf(
    FaceStyle("纯白", "简约", "清晰的时间与小云表情"),
    FaceStyle("珊瑚", "色彩", "明亮的暖色渐变"),
    FaceStyle("深色", "简约", "低调的深色时间显示"),
    FaceStyle("晨光", "色彩", "像清晨日光一样柔和"),
    FaceStyle("星夜", "指针", "经典指针与夜空配色"),
    FaceStyle("活力", "健康", "用彩色圆环点亮表盘"),
    FaceStyle("模块", "健康", "时间与健康信息分区呈现"),
    FaceStyle("霓虹", "数字", "醒目的大号数字"),
    FaceStyle("小云", "角色", "让小云成为表盘主角"),
)
private val faceNames = faceStyles.map { it.name }

@Composable
fun WatchHomeScreen(store: AppStore, onOpen: (Screen) -> Unit, onGallery: () -> Unit) {
    val device = store.device
    val nextAlarm = store.nextAlarm()
    var query by rememberSaveable { mutableStateOf("") }

    data class SettingRow(val icon: ImageVector, val title: String, val subtitle: String, val screen: Screen)
    val settings = listOf(
        SettingRow(Icons.Rounded.Alarm, "闹钟", nextAlarm?.let { "下一闹钟 ${it.timeText} · 本机保存" } ?: "暂无闹钟", Screen.AlarmList),
        SettingRow(Icons.Rounded.Watch, "连接与同步", if (device.connected) "查看设备状态" else "手表尚未连接", Screen.DeviceHome),
        SettingRow(Icons.Rounded.Face, "小云形象", "更换 App 内的角色外观", Screen.Appearance),
        SettingRow(Icons.Rounded.Settings, "个人与隐私", "共享偏好与本机数据", Screen.Settings),
    )
    val health = listOf(
        SettingRow(Icons.Rounded.FavoriteBorder, "健康概览", "心率、血氧与活动记录", Screen.HealthHome),
        SettingRow(Icons.Rounded.Mood, "记录心情", "今天已记录 ${store.todayMoods().size} 次", Screen.MoodRecord()),
        SettingRow(Icons.Rounded.Face, "和小云聊聊", "继续对话", Screen.ChatHome),
        SettingRow(Icons.Rounded.Spa, "关怀提醒", "查看提醒与时间线", Screen.CareHome),
    )
    val filtered = (settings + health).filter {
        query.isBlank() || it.title.contains(query, ignoreCase = true) || it.subtitle.contains(query, ignoreCase = true)
    }

    PageColumn(Modifier.background(companionBackground), spacing = 8.dp) {
        Row(
            Modifier.padding(top = 10.dp).height(32.dp)
                .clip(RoundedCornerShape(18.dp)).background(companionGroup)
                .clickable { onOpen(Screen.AllWatches) }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("所有手表", color = companionText, fontSize = 13.sp)
            Text("⌄", color = companionSecondary, fontSize = 15.sp, modifier = Modifier.padding(start = 6.dp))
        }
        Text(
            if (device.bound) device.name else "我的手表",
            color = companionText, fontSize = 32.sp, fontWeight = FontWeight.Bold,
        )
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(11.dp))
                .background(companionGroup).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, "搜索设置", tint = companionSecondary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(color = companionText, fontSize = 14.sp),
                cursorBrush = SolidColor(companionAccent),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("搜索设置", color = companionSecondary, fontSize = 14.sp)
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                Text("×", color = companionSecondary, fontSize = 18.sp,
                    modifier = Modifier.clickable { query = "" }.padding(start = 8.dp))
            }
        }

        if (query.isBlank()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("我的表盘", Modifier.weight(1f), color = companionText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("编辑", color = companionAccent, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onGallery() }.padding(8.dp))
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                faceNames.forEachIndexed { index, name ->
                    FaceTile(index, name, store.faceTheme == index, compact = true,
                        avatarId = store.faceAvatarId ?: store.avatarId) {
                        onGallery()
                    }
                }
            }
        }

        if (query.isNotBlank()) Text("搜索结果", color = companionText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (filtered.isEmpty()) {
            Text("没有找到相关设置", color = companionSecondary, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
        } else {
            CompanionGroup(Modifier.padding(top = if (query.isBlank()) 8.dp else 0.dp)) {
                (if (query.isBlank()) settings else filtered).forEachIndexed { index, row ->
                    if (index > 0) GroupDivider()
                    CompanionRow(row.icon, row.title, row.subtitle, onClick = {
                        onOpen(row.screen)
                    })
                }
            }
            if (query.isBlank()) {
                Text("健康与陪伴", color = companionText, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp))
                CompanionGroup {
                    health.forEachIndexed { index, row ->
                        if (index > 0) GroupDivider()
                        CompanionRow(row.icon, row.title, row.subtitle, onClick = { onOpen(row.screen) })
                    }
                }
            }
        }
    }

}

@Composable
fun AllWatchesScreen(store: AppStore, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    var renaming by remember { mutableStateOf<WatchDevice?>(null) }
    LiquidGlassHeaderPage(
        title = "所有手表",
        onBack = onBack,
        actions = { headerBackdrop ->
            LiquidGlassTopBarIconButton(
                icon = Icons.Rounded.Add,
                label = "添加手表",
                onClick = { onOpen(Screen.Binding) },
                backdrop = headerBackdrop,
            )
        },
    ) {
        Text("我的手表", color = companionText, fontSize = 19.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp))
        if (store.watches.isEmpty()) {
            AllWatchesGroup {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsIconTile(Color(0xFF8E8E93)) {
                        WatchGlyph(Modifier.size(23.dp), Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("还没有保存的手表", color = companionText, fontSize = 16.sp)
                }
            }
        } else {
            AllWatchesGroup {
                store.watches.forEachIndexed { index, watch ->
                    if (index > 0) SettingsDivider()
                    val current = watch.id == store.device.id
                    Row(
                    Modifier.fillMaxWidth().motionClickable(onClick = { store.selectWatch(watch.id) })
                            .padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsIconTile(
                            if (current) Color(0xFF0A84FF) else Color(0xFF8E8E93),
                        ) {
                            WatchGlyph(Modifier.size(23.dp), Color.White)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(watch.name, color = companionText, fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(
                                if (current) "当前查看 · 未连接运动数据" else
                                    if (watch.source == "bluetooth") "蓝牙保存 · 未连接" else "本机保存 · 未连接",
                                color = companionSecondary, fontSize = 12.sp,
                            )
                        }
                        if (current) {
                            Icon(Icons.Rounded.Check, null, tint = Color(0xFF0A84FF),
                                modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        LiquidGlassButton(
                            "改名",
                            { renaming = watch },
                            height = 34.dp,
                            filled = true,
                            tint = Color(0xFF0A84FF),
                            fillAlpha = .84f,
                        )
                    }
                }
            }
        }

        Text("添加手表", color = companionText, fontSize = 19.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 18.dp))
        AllWatchesGroup {
            Row(
                Modifier.fillMaxWidth().motionClickable(onClick = { onOpen(Screen.Binding) })
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsIconTile(Color(0xFF34C759)) {
                    AddWatchGlyph(Modifier.size(23.dp), Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("添加手表", color = companionText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("手动输入 ID 或通过蓝牙搜索", color = companionSecondary, fontSize = 12.sp)
                }
                Text("›", color = companionSecondary, fontSize = 24.sp)
            }
        }
        Text("当前列表只显示本机保存的手表；切换只改变查看对象，真实蓝牙连接与同步尚未接入。",
            color = companionSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
        Spacer(Modifier.height(600.dp))
    }

    renaming?.let { watch ->
        RenameWatchDialog(
            watch = watch,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                store.renameWatch(watch.id, name)
                renaming = null
            },
        )
    }
}

@Composable
private fun SettingsIconTile(
    color: Color,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = .88f), color))),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SettingsDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = 66.dp).height(1.dp)
            .background(Color(0xFFE7E7EC)),
    )
}

@Composable
private fun AllWatchesGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White),
        content = content,
    )
}

@Composable
private fun WatchGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.9.dp.toPx()
        drawRoundRect(
            color = tint.copy(alpha = .32f),
            topLeft = Offset(w * .35f, h * .02f),
            size = Size(w * .30f, h * .27f),
            cornerRadius = CornerRadius(w * .07f),
        )
        drawRoundRect(
            color = tint.copy(alpha = .32f),
            topLeft = Offset(w * .35f, h * .71f),
            size = Size(w * .30f, h * .27f),
            cornerRadius = CornerRadius(w * .07f),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * .23f, h * .23f),
            size = Size(w * .54f, h * .54f),
            cornerRadius = CornerRadius(w * .15f),
            style = Stroke(stroke),
        )
        drawLine(tint, Offset(w * .77f, h * .48f), Offset(w * .82f, h * .48f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun AddWatchGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier) {
        val stroke = 1.9.dp.toPx()
        drawCircle(tint, radius = size.minDimension * .40f, style = Stroke(stroke))
        drawLine(tint, Offset(size.width * .30f, size.height * .5f),
            Offset(size.width * .70f, size.height * .5f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(size.width * .5f, size.height * .30f),
            Offset(size.width * .5f, size.height * .70f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun RenameWatchDialog(
    watch: WatchDevice,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(watch.id) { mutableStateOf(watch.name) }
    val colors = LocalWatchColors.current
    Dialog(onDismissRequest = onDismiss) {
        com.pointerwander.luminawatch.ui.GlassDialogWindowBlur()
        LiquidGlassPanel(Modifier.fillMaxWidth(), translucent = true) {
            Text("修改手表名称", color = companionText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(24) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("名称") },
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidGlassButton("取消", onDismiss, Modifier.weight(1f), tint = colors.danger)
                LiquidGlassButton("保存", { onConfirm(text) }, Modifier.weight(1f), tint = colors.accent)
            }
        }
    }
}

@Composable
fun FaceGalleryScreen(store: AppStore, onBack: (() -> Unit)? = null) {
    val current = store.faceTheme
    val colors = LocalWatchColors.current
    val avatarId = store.faceAvatarId ?: store.avatarId
    var previewTheme by remember { mutableStateOf<Int?>(null) }
    WatchSettingsPage("表盘图库", onBack ?: {}) {
        WatchSettingsSection("当前预览")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(25.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFE7EEFC), Color.White)))
            .motionClickable(onClick = { previewTheme = current })
            .padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            WatchFacePreview(current, compact = false, avatarId = avatarId)
            Spacer(Modifier.height(8.dp))
            Text(faceNames[current], color = companionText, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold)
            Text("手机预览 · 点按查看", color = companionSecondary, fontSize = 13.sp)
        }

        listOf("简约", "色彩", "健康", "指针", "数字", "角色").forEach { category ->
            WatchSettingsSection(category)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                modifier = Modifier.fillMaxWidth()) {
                items(faceStyles.indices.filter { faceStyles[it].category == category }) { theme ->
                    Column(Modifier.width(145.dp).clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = .82f))
                        .motionClickable(onClick = { previewTheme = theme })
                        .padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        WatchFacePreview(theme, compact = true, avatarId = avatarId)
                        Spacer(Modifier.height(5.dp))
                        Text(faceNames[theme], color = companionText, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text(if (current == theme) "当前预览" else "查看表盘",
                            color = if (current == theme) Color(0xFF0A84FF) else companionSecondary,
                            fontSize = 11.sp)
                    }
                }
            }
        }

        WatchSettingsSection("表盘上的小云")
        WatchSettingsGroup {
            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 14.dp)) {
                item {
                    Box(
                        Modifier.size(82.dp, 96.dp).clip(RoundedCornerShape(15.dp))
                            .background(if (store.faceAvatarId == null) colors.accentSoft else colors.surfaceMuted)
                            .clickable { store.updateFaceAvatar(null) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("跟随\n小云形象", color = colors.textPrimary, fontSize = 12.sp,
                            lineHeight = 17.sp)
                    }
                }
                items(CloudAvatars.all, key = { it.id }) { avatar ->
                    val selected = store.faceAvatarId == avatar.id
                    Column(
                        Modifier.width(82.dp).height(96.dp).clip(RoundedCornerShape(15.dp))
                            .background(if (selected) colors.accentSoft else colors.surfaceMuted)
                            .clickable { store.updateFaceAvatar(avatar.id) }
                            .padding(5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CloudImage(Modifier.size(58.dp), avatarId = avatar.id)
                        Text(if (selected) "✓ ${avatar.label}" else avatar.label,
                            color = colors.textPrimary, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        WatchSettingsNote("表盘与表情目前只在手机上预览，尚未下发到手表。")
    }
    previewTheme?.let { theme ->
        LiquidGlassBottomSheet(onDismiss = { previewTheme = null }) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(faceNames[theme], color = companionText, fontSize = 23.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                WatchFacePreview(theme, compact = false, avatarId = avatarId)
                Spacer(Modifier.height(10.dp))
                Text(faceStyles[theme].description, color = companionSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(18.dp))
                LiquidGlassButton(if (current == theme) "当前预览" else "设为当前预览", {
                    store.updateFaceTheme(theme)
                    previewTheme = null
                }, Modifier.fillMaxWidth(), enabled = current != theme, tint = Color(0xFF0A84FF))
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

@Composable
private fun FeaturedFaceCard(theme: Int, avatarId: String) {
    val background = when (theme) {
        1 -> listOf(Color(0xFFE53781), Color(0xFF71345C), Color(0xFF1D1D22))
        2 -> listOf(Color(0xFF50576D), Color(0xFF232633), Color(0xFF14151A))
        else -> listOf(Color(0xFF798EA3), Color(0xFF374454), Color(0xFF171D24))
    }
    Box(
        Modifier.fillMaxWidth().height(246.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(background)),
    ) {
        Text("小云表情表盘", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
        Box(Modifier.align(Alignment.Center).offset(x = (-84).dp, y = 10.dp)) {
            WatchFacePreview((theme + 1) % faceNames.size, compact = true, avatarId = avatarId)
        }
        Box(Modifier.align(Alignment.Center).offset(x = 84.dp, y = 10.dp)) {
            WatchFacePreview((theme + 2) % faceNames.size, compact = true, avatarId = avatarId)
        }
        Box(Modifier.align(Alignment.Center).offset(y = (-3).dp)) {
            WatchFacePreview(theme, compact = false, avatarId = avatarId)
        }
        Text("当前预览 · ${faceNames[theme]} · ${CloudAvatars.find(avatarId).label}",
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
    }
}

@Composable
private fun GalleryFaceCard(theme: Int, avatarId: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val background = when (theme) {
        1 -> Color(0xFF34232D)
        2 -> Color(0xFF1C2028)
        else -> Color(0xFF303C47)
    }
    Column(
        modifier.height(208.dp).clip(RoundedCornerShape(18.dp))
            .background(background).motionClickable(onClick).padding(12.dp),
    ) {
        Text(faceNames[theme], color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            WatchFacePreview(theme, compact = true, avatarId = avatarId)
        }
        Text("轻点预览", color = Color.White.copy(alpha = .76f), fontSize = 11.sp)
    }
}

@Composable
private fun MiniFace(theme: Int) {
    val face = when (theme) {
        1 -> listOf(Color(0xFFFF967A), Color(0xFFFA4264))
        2 -> listOf(Color(0xFF34363C), Color(0xFF07080A))
        else -> listOf(Color(0xFFFEFCF7), Color(0xFFE9EDF0))
    }
    val ink = if (theme == 2) Color.White else Color(0xFF24262A)
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0A0A0C)).padding(3.dp)
            .clip(RoundedCornerShape(13.dp)).background(Brush.verticalGradient(face)),
        contentAlignment = Alignment.Center,
    ) {
        Text("10:24", color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FaceTile(index: Int, name: String, selected: Boolean, compact: Boolean, avatarId: String, onClick: () -> Unit) {
    Column(
        Modifier.width(if (compact) 120.dp else 210.dp)
            .clip(RoundedCornerShape(14.dp))
            .motionClickable(onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WatchFacePreview(index, compact, avatarId)
        Spacer(Modifier.height(4.dp))
        Text(name, color = companionText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (selected) Text("当前预览", color = companionAccent, fontSize = 10.sp)
    }
}

@Composable
private fun WatchFacePreview(theme: Int, compact: Boolean, avatarId: String) {
    val shellWidth = if (compact) 104.dp else 174.dp
    val shellHeight = if (compact) 122.dp else 198.dp
    val face = when (theme) {
        1 -> listOf(Color(0xFFFF967A), Color(0xFFFA4264))
        2 -> listOf(Color(0xFF34363C), Color(0xFF07080A))
        3 -> listOf(Color(0xFFFFC88E), Color(0xFFFFE4BC))
        4 -> listOf(Color(0xFF28365D), Color(0xFF080D22))
        5 -> listOf(Color(0xFF222936), Color(0xFF080A10))
        6 -> listOf(Color(0xFF255582), Color(0xFF102038))
        7 -> listOf(Color(0xFF522F89), Color(0xFF130F2B))
        8 -> listOf(Color(0xFFD5C7FF), Color(0xFF847BD5))
        else -> listOf(Color(0xFFFEFCF7), Color(0xFFE9EDF0))
    }
    val ink = if (theme in setOf(2, 4, 5, 6, 7, 8)) Color.White else Color(0xFF24262A)
    Box(Modifier.size(shellWidth, shellHeight), contentAlignment = Alignment.Center) {
        Box(Modifier.width(shellWidth * .38f).height(shellHeight).clip(RoundedCornerShape(12.dp)).background(Color(0xFF48484D)))
        Box(
            Modifier.size(shellWidth, shellHeight * .76f)
                .clip(RoundedCornerShape(if (compact) 31.dp else 42.dp))
                .background(Color(0xFF696A70))
                .padding(4.dp)
                .clip(RoundedCornerShape(if (compact) 28.dp else 38.dp))
                .background(Color(0xFF050507))
                .padding(5.dp)
                .clip(RoundedCornerShape(if (compact) 23.dp else 32.dp))
                .background(Brush.verticalGradient(face)),
            contentAlignment = Alignment.Center,
        ) {
            when (theme) {
                4 -> {
                    Canvas(Modifier.fillMaxSize().padding(if (compact) 12.dp else 19.dp)) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(Color.White.copy(alpha = .72f), radius = size.minDimension * .44f,
                            style = Stroke(width = 1.5.dp.toPx()))
                        drawLine(Color.White, center,
                            Offset(center.x + size.width * .19f, center.y - size.height * .20f),
                            strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(Color.White, center,
                            Offset(center.x - size.width * .11f, center.y - size.height * .27f),
                            strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                        drawCircle(Color(0xFFFFB87A), radius = 3.dp.toPx(), center = center)
                    }
                    Text("星夜", color = ink, fontSize = if (compact) 7.sp else 10.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
                }
                5 -> {
                    Canvas(Modifier.size(if (compact) 66.dp else 112.dp)) {
                        val radius = size.minDimension * .43f
                        listOf(Color(0xFFFF5476), Color(0xFFB9E455), Color(0xFF52B9FF))
                            .forEachIndexed { index, color ->
                                drawCircle(color.copy(alpha = .95f), radius = radius - index * radius * .23f,
                                    style = Stroke(width = if (compact) 4.dp.toPx() else 6.dp.toPx()))
                            }
                    }
                    Text("活力", color = ink, fontSize = if (compact) 8.sp else 12.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
                }
                6 -> {
                    Column(Modifier.fillMaxSize().padding(7.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Text("10:24", color = ink, fontSize = if (compact) 17.sp else 30.sp,
                            fontWeight = FontWeight.Bold)
                        Text("♥ 78    ◉ 98%", color = Color(0xFF9FE8FF),
                            fontSize = if (compact) 8.sp else 13.sp)
                        Text("周四 29", color = Color.White.copy(alpha = .82f),
                            fontSize = if (compact) 7.sp else 11.sp)
                    }
                }
                7 -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("10", color = Color(0xFFECBAFF), fontSize = if (compact) 31.sp else 51.sp,
                            fontWeight = FontWeight.Black, lineHeight = if (compact) 31.sp else 51.sp)
                        Text("24", color = Color(0xFF88E4FF), fontSize = if (compact) 31.sp else 51.sp,
                            fontWeight = FontWeight.Black, lineHeight = if (compact) 31.sp else 51.sp)
                    }
                }
                8 -> {
                    CloudImage(Modifier.size(if (compact) 72.dp else 118.dp), avatarId = avatarId)
                    Text("10:24", color = ink, fontSize = if (compact) 10.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
                }
                3 -> {
                    Box(Modifier.align(Alignment.Center).size(if (compact) 57.dp else 96.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0xFFFF9B6B).copy(alpha = .78f)))
                    Text("10:24", color = ink, fontSize = if (compact) 22.sp else 35.sp,
                        fontWeight = FontWeight.Light)
                    Text("晨光", color = ink.copy(alpha = .72f), fontSize = if (compact) 8.sp else 12.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
                }
                else -> {
                    Text("10:24", color = ink, fontSize = if (compact) 20.sp else 31.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopCenter)
                            .padding(top = if (compact) 6.dp else 9.dp))
                    CloudImage(Modifier.size(if (compact) 61.dp else 106.dp)
                        .align(Alignment.BottomCenter), avatarId = avatarId)
                }
            }
        }
    }
}

@Composable
internal fun CompanionGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(companionGroup), content = content)
}

@Composable
internal fun GroupDivider() {
    HorizontalDivider(Modifier.padding(start = 58.dp), color = LocalWatchColors.current.outline, thickness = .5.dp)
}

@Composable
internal fun CompanionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).motionClickable(onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(LocalWatchColors.current.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = companionAccent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = companionText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = companionSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = companionSecondary, modifier = Modifier.size(17.dp))
    }
}
