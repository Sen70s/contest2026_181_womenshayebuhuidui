package com.pointerwander.luminawatch.feature.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.pointerwander.luminawatch.ui.motionClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.pointerwander.luminawatch.ui.LiquidGlassStickyHeader
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.pointerwander.luminawatch.app.Screen
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.ui.LiquidGlassSurface
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.LocalBottomBarInset
import java.text.NumberFormat

private val activityOrange = Color(0xFFFF7A1A)
private val activityInk = Color(0xFF1C1C1E)
private val activityGray = Color(0xFF86868B)

@Composable
fun ActivityHomeScreen(store: AppStore, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val colors = LocalWatchColors.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val steps = activityValues("步数", 0).sum()
    val standing = activityValues("站立时间", 0).sum()
    val calories = activityValues("活动消耗", 0).sum()
    val scrollState = rememberScrollState()
    val headerBackdrop = rememberLayerBackdrop()
    val showHeader by remember { derivedStateOf { scrollState.value > 40 } }
    val backDrop = rememberCanvasBackdrop {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFFFFD1AE), Color(0xFFFFEBDD), Color(0xFFF3F3F8)),
            ),
        )
    }

    Box(Modifier.fillMaxSize().background(colors.groupedSurface)) {
        Box(Modifier.fillMaxSize().layerBackdrop(headerBackdrop)) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            Box(
                Modifier.fillMaxWidth().height(330.dp + statusBarHeight).background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFFFD0A8), Color(0xFFFFEADB), colors.groupedSurface),
                    ),
                ),
            ) {
                Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 136.dp + statusBarHeight)) {
                    Text("活动", color = activityInk, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(26.dp))
                    Text("过去 7 天", color = activityInk, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(10.dp))
                ActivityGlanceCard(
                    title = "步数", icon = Icons.Rounded.LocalFireDepartment, accent = activityOrange,
                    value = NumberFormat.getIntegerInstance().format(steps), unit = "步",
                    bars = activityValues("步数", 0).takeLast(8).map { it.toFloat() },
                ) { onOpen(Screen.ActivityDetail("步数")) }
                Spacer(Modifier.height(14.dp))
                ActivityGlanceCard(
                    title = "站立活动", icon = Icons.Rounded.DirectionsWalk, accent = Color(0xFF20C66B),
                    value = standing.toString(), unit = "次",
                    bars = activityValues("站立时间", 0).takeLast(8).map { it.toFloat() },
                ) { onOpen(Screen.ActivityDetail("站立时间")) }
                Spacer(Modifier.height(14.dp))
                ActivityGlanceCard(
                    title = "活动消耗", icon = Icons.Rounded.LocalFireDepartment, accent = Color(0xFFFF7043),
                    value = calories.toString(), unit = "千卡",
                    bars = activityValues("活动消耗", 0).takeLast(8).map { it.toFloat() },
                ) { onOpen(Screen.ActivityDetail("活动消耗")) }
                Spacer(Modifier.height(28.dp))
                Text("记录与回顾", color = activityInk, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(colors.surface),
                ) {
                    ActivityRow("步数", "查看每日步数变化", Icons.Rounded.LocalFireDepartment, activityOrange) {
                        onOpen(Screen.ActivityDetail("步数"))
                    }
                    ActivityDivider()
                    ActivityRow("站立活动", "查看站立次数", Icons.Rounded.DirectionsWalk, Color(0xFF20C66B)) {
                        onOpen(Screen.ActivityDetail("站立时间"))
                    }
                    ActivityDivider()
                    ActivityRow("活动消耗", "查看活动消耗热量", Icons.Rounded.Timer, Color(0xFFFF7043)) {
                        onOpen(Screen.ActivityDetail("活动消耗"))
                    }
                }
                Spacer(Modifier.height(36.dp + LocalBottomBarInset.current + navigationBarHeight))
            }
        }
        }
        AnimatedVisibility(
            visible = showHeader,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LiquidGlassStickyHeader(
                title = "活动",
                onBack = onBack,
                backdrop = headerBackdrop,
                topInset = statusBarHeight,
            )
        }
        AnimatedVisibility(
            visible = !showHeader,
            modifier = Modifier.align(Alignment.TopStart)
                .padding(start = 18.dp, top = statusBarHeight + 12.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ActivityBackButton(
                onBack = onBack,
                backdrop = backDrop,
            )
        }
    }
}

@Composable
private fun ActivityGlanceCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    value: String,
    unit: String,
    bars: List<Float>,
    onClick: () -> Unit,
) {
    val colors = LocalWatchColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(colors.surface)
            .motionClickable(onClick).padding(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = .15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = accent, modifier = Modifier.size(21.dp)) }
            Spacer(Modifier.width(9.dp))
            Text(title, color = accent, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text("今天", color = activityGray, fontSize = 13.sp)
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color(0xFFB0B0B6),
                modifier = Modifier.padding(start = 3.dp).size(17.dp))
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, color = activityInk, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(unit, color = activityGray, fontSize = 14.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 5.dp))
                }
                Text("过去 7 天", color = activityGray, fontSize = 12.sp)
            }
            ActivityMiniBars(bars, accent, Modifier.size(92.dp, 48.dp))
        }
    }
}

@Composable
private fun ActivityMiniBars(values: List<Float>, accent: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val maxValue = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val gap = size.width / (values.size * 1.5f)
        val barWidth = gap * .9f
        values.forEachIndexed { index, value ->
            val ratio = (value / maxValue).coerceIn(0f, 1f)
            val height = size.height * (.12f + .88f * ratio)
            drawRoundRect(
                color = if (index == values.lastIndex) accent else accent.copy(alpha = .38f),
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}

@Composable
private fun ActivityRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().motionClickable(onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = .14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = accent, modifier = Modifier.size(21.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = activityInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = activityGray, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color(0xFFB0B0B6),
            modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ActivityDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 70.dp).height(1.dp).background(Color(0xFFE7E7EC)))
}

@Composable
private fun ActivityBackButton(
    onBack: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop,
    modifier: Modifier = Modifier,
) {
    LiquidGlassSurface(
        onClick = onBack,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        height = 48.dp,
        backdrop = backdrop,
        glassAlpha = .52f,
        border = Color.White.copy(alpha = .6f),
        contentPadding = 0.dp,
    ) { _ ->
        Canvas(Modifier.size(22.dp)) {
            drawLine(Color(0xFF0B0B0F), Offset(size.width * .66f, size.height * .14f),
                Offset(size.width * .32f, size.height * .5f), 2.8.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0xFF0B0B0F), Offset(size.width * .32f, size.height * .5f),
                Offset(size.width * .66f, size.height * .86f), 2.8.dp.toPx(), StrokeCap.Round)
        }
    }
}
