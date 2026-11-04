package com.pointerwander.luminawatch.feature.device

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import com.pointerwander.luminawatch.ui.motionClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import com.pointerwander.luminawatch.app.Screen
import com.pointerwander.luminawatch.feature.health.HealthSearchBar
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.PageColumn
import com.pointerwander.luminawatch.ui.PageHeader
import com.pointerwander.luminawatch.ui.TopScrim
import com.pointerwander.luminawatch.ui.statusBarHeight

private val watchPageGray = Color(0xFFF4F4F8)
private val watchBlue = Color(0xFF1478E8)

@Composable
fun WatchDashboardScreen(store: AppStore, onOpen: (Screen) -> Unit) {
    val colors = LocalWatchColors.current
    val statusInset = statusBarHeight()
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val pageScroll = rememberScrollState()
    val showCompactTitle by remember(pageScroll) {
        derivedStateOf { pageScroll.value > 130 }
    }
    val groups = listOf(
        "手表" to listOf(
            WatchSetting("我的手表", "查看或添加手表", Icons.Rounded.Watch,
                Screen.AllWatches,
                Color(0xFF098CFA), if (store.device.bound) "已添加" else "未添加"),
            WatchSetting("表盘图库", "预览与编辑表盘", Icons.Rounded.Palette,
                Screen.FaceGallery, Color(0xFFDE4EAB), "3 款"),
        ),
        "设备与提醒" to listOf(
            WatchSetting("连接与同步", "连接状态与设备管理", Icons.Rounded.Watch,
                Screen.DeviceHome, Color(0xFF098CFA), if (store.device.connected) "已连接" else "未连接"),
            WatchSetting("闹钟", "本机保存的提醒", Icons.Rounded.Alarm,
                Screen.AlarmList, Color(0xFFFF9F0A)),
            WatchSetting("喝水提醒", "自定义手机提醒", Icons.Rounded.WaterDrop,
                Screen.HydrationInfo, Color(0xFF22B8CF),
                if (store.hydrationReminder.enabled) "已开启" else "已关闭"),
        ),
        "小云与设置" to listOf(
            WatchSetting("关怀规则", "久坐、安静时段与提醒偏好", Icons.Rounded.Spa,
                Screen.CareRules, Color(0xFF6E5CE7)),
            WatchSetting("小云形象", "选择陪伴你的角色", Icons.Rounded.Face,
                Screen.Appearance, Color(0xFF24BEA7)),
            WatchSetting("个人与隐私", "外观、隐私、数据与关于", Icons.Rounded.Settings,
                Screen.Settings, Color(0xFF8E8E93)),
        ),
    )

    Box(Modifier.fillMaxSize().background(watchPageGray)) {
    PageColumn(scrollState = pageScroll, spacing = 12.dp, bounce = true) {
        Text("手表", color = colors.textPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = statusInset + 22.dp, bottom = 10.dp))

        HealthSearchBar(
            query = query,
            onQueryChange = { query = it },
            expanded = searchExpanded,
            onExpandedChange = { searchExpanded = it },
            focusRequester = searchFocus,
            placeholder = "搜索手表设置",
        )

        Spacer(Modifier.height(12.dp))
        var found = false
        groups.forEachIndexed { index, (_, group) ->
            val shown = group.filter {
                query.isBlank() || it.title.contains(query, true) || it.subtitle.contains(query, true)
            }
            if (shown.isNotEmpty()) {
                if (index > 0) Spacer(Modifier.height(18.dp))
                found = true
                SettingGroup(shown, onOpen)
            }
        }
        if (!found) Text("没有找到相关设置", color = colors.textSecondary, fontSize = 14.sp,
            modifier = Modifier.padding(vertical = 16.dp))
    }
    // 背景已经铺到 y=0，滚动内容从状态栏后面经过时用同色渐隐收口。
    TopScrim(watchPageGray, modifier = Modifier.align(Alignment.TopCenter))
    AnimatedVisibility(
        visible = showCompactTitle,
        enter = fadeIn(), exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        Box(
            Modifier.fillMaxWidth().height(84.dp + statusInset).background(
                Brush.verticalGradient(
                    0f to watchPageGray,
                    .86f to watchPageGray,
                    1f to watchPageGray.copy(alpha = 0f),
                ),
            ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text("手表", color = colors.textPrimary, fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = statusInset + 20.dp))
        }
    }
    }
}

private data class WatchSetting(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val destination: Screen,
    val tint: Color,
    val status: String? = null,
)

@Composable
private fun SettingGroup(items: List<WatchSetting>, onOpen: (Screen) -> Unit) {
    val colors = LocalWatchColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White)) {
        items.forEachIndexed { index, item ->
            Row(
                Modifier.fillMaxWidth().height(62.dp)
                    .motionClickable(onClick = { onOpen(item.destination) })
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
                    .background(Brush.verticalGradient(
                        listOf(item.tint.copy(alpha = .84f), item.tint),
                    )), contentAlignment = Alignment.Center) {
                    Icon(item.icon, null, tint = Color.White, modifier = Modifier.size(21.dp))
                }
                Text(item.title, color = colors.textPrimary, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 1)
                if (item.status != null) {
                    Text(item.status, color = Color(0xFF8E8E93), fontSize = 14.sp,
                        modifier = Modifier.padding(end = 6.dp))
                }
                Text("›", color = Color(0xFFC7C7CC), fontSize = 25.sp)
            }
            if (index < items.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 62.dp)
                .height(1.dp).background(Color(0xFFE5E5EA)))
        }
    }
}

