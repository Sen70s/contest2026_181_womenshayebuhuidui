package com.pointerwander.luminawatch.feature.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.pointerwander.luminawatch.ui.LiquidGlassBottomSheet
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassSlidingSegmented
import com.pointerwander.luminawatch.ui.LiquidGlassStickyHeader
import com.pointerwander.luminawatch.ui.LiquidGlassTopBarIconButton
import com.pointerwander.luminawatch.ui.LocalBottomBarInset
import com.pointerwander.luminawatch.ui.LocalWatchColors
import java.text.NumberFormat
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max

private val standingGreen = Color(0xFF20C66B)
private val caloriesOrange = Color(0xFFFF7043)
private val activityGray = Color(0xFF86868B)

@Composable
fun ActivityMetricScreen(metric: String, onBack: () -> Unit) {
    val colors = LocalWatchColors.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val standing = metric == "站立时间"
    val accent = if (standing) standingGreen else caloriesOrange
    val title = if (standing) "站立活动" else "活动消耗"
    val unit = if (standing) "次" else "千卡"
    val backdrop = rememberLayerBackdrop()
    var period by rememberSaveable { mutableIntStateOf(1) }
    var selected by remember(period) { mutableIntStateOf(-1) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var showAddNote by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val values = remember(metric, period) { metricValues(metric, period) }
    val labels = periodLabels(period, today)
    val summary = if (period == 0) values.sum() else values.average().toInt()
    val range = when (period) {
        0 -> "今天"
        1 -> "${today.minusDays(6).year}年${today.minusDays(6).monthValue}月${today.minusDays(6).dayOfMonth}日—${today.monthValue}月${today.dayOfMonth}日"
        2 -> "${today.minusDays(30).year}年${today.minusDays(30).monthValue}月${today.minusDays(30).dayOfMonth}日—${today.monthValue}月${today.dayOfMonth}日"
        3 -> "${today.minusDays(181).year}年${today.minusDays(181).monthValue}月${today.minusDays(181).dayOfMonth}日—${today.monthValue}月${today.dayOfMonth}日"
        else -> "${today.year}年"
    }

    Box(Modifier.fillMaxSize().background(colors.groupedSurface)) {
        LazyColumn(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            item { Spacer(Modifier.fillMaxWidth().height(84.dp + statusBarHeight).background(colors.surface)) }
            item {
                Column(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(14.dp))
                    LiquidGlassSlidingSegmented(
                        options = listOf("日", "周", "月", "6个月", "年"),
                        selectedIndex = period,
                        onSelected = { period = it },
                    )
                    Spacer(Modifier.height(16.dp))
                    ActivityChartHeading(values, labels, period, selected, summary, range, unit)
                    ActivityBarChart(values, labels, accent, selected) { selected = it }
                    Spacer(Modifier.height(24.dp))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("提要", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                    Text(if (showAll) "收起" else "全部显示", Modifier.clickable { showAll = !showAll },
                        color = colors.accent, fontSize = 15.sp)
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(colors.surface, RoundedCornerShape(24.dp)).padding(20.dp)) {
                    Text("● $title", color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(14.dp))
                    Text("${range}的${title}记录", color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Spacer(Modifier.height(18.dp))
                    listOf("最新" to (values.lastOrNull() ?: 0), "平均" to summary).forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, color = activityGray, fontSize = 15.sp)
                            Text("${NumberFormat.getIntegerInstance().format(value)} $unit",
                                color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                }
            }
            if (showAll) item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(colors.surface, RoundedCornerShape(24.dp)).padding(20.dp)) {
                    Text("● $title", color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(14.dp))
                    Text("记录范围", color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Spacer(Modifier.height(18.dp))
                    listOf("最高" to (values.maxOrNull() ?: 0),
                        "最低" to (values.minOrNull() ?: 0)).forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, color = activityGray, fontSize = 15.sp)
                            Text("${NumberFormat.getIntegerInstance().format(value)} $unit",
                                color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)) {
                    Text("关于$title", color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(24.dp)).padding(20.dp)) {
                        Text(if (standing) {
                            "站立活动记录你一天中起身活动的次数。久坐后起身活动，有助于缓解身体僵硬，也能打断长时间的静态姿势。"
                        } else {
                            "活动消耗记录你在日常活动和运动中消耗的热量，会随活动强度和时长变化。"
                        }, color = colors.textPrimary, fontSize = 17.sp, lineHeight = 26.sp,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
            item { Spacer(Modifier.height(36.dp + LocalBottomBarInset.current)) }
        }
        LiquidGlassStickyHeader(
            title = title,
            onBack = onBack,
            backdrop = backdrop,
            topInset = statusBarHeight,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LiquidGlassTopBarIconButton(
                icon = Icons.Rounded.Add,
                label = "记录$title",
                onClick = { showAddNote = true },
                backdrop = backdrop,
            )
        }
    }

    if (showAddNote) {
        LiquidGlassBottomSheet({ showAddNote = false }, fullHeight = false) {
            Text("记录$title", color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("$title 的手动记录尚未接入，目前只展示本机预览数据。", color = colors.textSecondary,
                fontSize = 15.sp, lineHeight = 23.sp)
            Spacer(Modifier.height(16.dp))
            LiquidGlassButton("知道了", { showAddNote = false }, Modifier.fillMaxWidth(), tint = accent)
        }
    }
}

@Composable
private fun ActivityChartHeading(
    values: List<Int>, labels: List<String>, period: Int, selected: Int,
    summary: Int, range: String, unit: String,
) {
    val colors = LocalWatchColors.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(112.dp)) {
        if (selected in values.indices) {
            val popupWidth = minOf(220.dp, maxWidth)
            val plotWidth = (maxWidth - 48.dp).coerceAtLeast(0.dp)
            val barCenter = plotWidth * (selected + .5f) / values.size
            val popupX = (barCenter - popupWidth / 2).coerceIn(0.dp, maxWidth - popupWidth)
            Canvas(Modifier.fillMaxSize()) {
                val x = barCenter.toPx()
                drawLine(Color(0xFFC8C8CD), Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
            }
            Column(Modifier.offset(x = popupX, y = (-12).dp).width(popupWidth)
                .align(Alignment.TopStart).background(colors.surfaceMuted, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("总计", color = activityGray, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(NumberFormat.getIntegerInstance().format(values[selected]), color = colors.textPrimary,
                        fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(unit, Modifier.padding(start = 6.dp, bottom = 5.dp), color = activityGray, fontSize = 16.sp)
                }
                Text(labels.getOrElse(selected) { "" }, color = activityGray, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        } else {
            Column(Modifier.align(Alignment.TopStart)) {
                Text(if (period == 0) "总计" else "平均", color = activityGray,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(NumberFormat.getIntegerInstance().format(summary), color = colors.textPrimary,
                        fontSize = 42.sp, fontWeight = FontWeight.Bold)
                    Text(unit, Modifier.padding(start = 6.dp, bottom = 8.dp),
                        color = activityGray, fontSize = 18.sp)
                }
                Text(range, color = activityGray, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ActivityBarChart(
    values: List<Int>,
    labels: List<String>,
    accent: Color,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    val colors = LocalWatchColors.current
    val peak = values.maxOrNull() ?: 0
    val step = if (peak <= 12) 5 else if (peak <= 100) 25 else 100
    val maxValue = max(step, ceil(peak.toDouble() / step).toInt() * step)
    val ticks = when {
        values.size <= 7 -> values.indices.toList()
        values.size <= 12 -> values.indices.filter { it % 2 == 0 }
        else -> values.indices.filter { it % 5 == 0 }
    }
    Column {
        Row(Modifier.fillMaxWidth().height(290.dp)) {
            Canvas(
                Modifier.weight(1f).fillMaxSize().pointerInput(values) {
                    detectTapGestures { offset ->
                        if (values.isNotEmpty()) {
                            onSelected(((offset.x / size.width) * values.size).toInt().coerceIn(values.indices))
                        }
                    }
                },
            ) {
                val chartHeight = size.height - 12.dp.toPx()
                repeat(3) { line ->
                    val y = chartHeight * line / 2f
                    drawLine(Color(0xFFE8E8ED), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                if (values.isEmpty()) return@Canvas
                val slot = size.width / values.size
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
                ticks.drop(1).forEach { index ->
                    drawLine(Color(0xFFDADAE0), Offset(index * slot, 0f),
                        Offset(index * slot, chartHeight), .7.dp.toPx(), pathEffect = dash)
                }
                if (selected in values.indices) {
                    val x = (selected + .5f) * slot
                    val barTop = chartHeight * (1f - values[selected].toFloat() / maxValue)
                    drawLine(Color(0xFFC8C8CD), Offset(x, 0f), Offset(x, barTop), 1.5.dp.toPx())
                }
                values.forEachIndexed { index, value ->
                    val barWidth = (slot * if (values.size <= 7) .62f else .54f)
                        .coerceIn(2.dp.toPx(), if (values.size <= 7) 32.dp.toPx() else 10.dp.toPx())
                    val barHeight = chartHeight * value / maxValue
                    if (barHeight > 0f) drawRoundRect(accent,
                        Offset(index * slot + (slot - barWidth) / 2f, chartHeight - barHeight),
                        Size(barWidth, barHeight), CornerRadius(2.dp.toPx()))
                }
            }
            Column(Modifier.width(48.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Text("$maxValue", color = activityGray, fontSize = 11.sp)
                Text("${maxValue / 2}", color = activityGray, fontSize = 11.sp)
                Text("0", color = activityGray, fontSize = 11.sp)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(end = 48.dp).height(22.dp)) {
            if (values.isNotEmpty()) {
                val slot = maxWidth / values.size
                ticks.forEach { index ->
                    val labelWidth = 44.dp
                    val offset = (slot * (index + .5f) - labelWidth / 2)
                        .coerceIn(0.dp, (maxWidth - labelWidth).coerceAtLeast(0.dp))
                    Text(labels.getOrElse(index) { "" }, Modifier.offset(x = offset).width(labelWidth),
                        color = activityGray, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ActivityMoreSheet(
    title: String,
    values: List<Int>,
    labels: List<String>,
    unit: String,
    accent: Color,
    onDismiss: () -> Unit,
) {
    val colors = LocalWatchColors.current
    val min = values.minOrNull() ?: 0
    val max = values.maxOrNull() ?: 0
    val avg = if (values.isEmpty()) 0 else values.average().toInt()
    LiquidGlassBottomSheet(onDismiss, fullHeight = true, surfaceColor = colors.surface, contentPadding = 0.dp) {
        Column(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LiquidGlassButton("关闭", onDismiss, height = 40.dp, filled = false, tint = accent)
                Text("更多$title", color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(66.dp))
            }
            Spacer(Modifier.height(18.dp))
            ActivityBarChart(values, labels, accent, -1) {}
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFECECF0)).padding(20.dp)) {
            ActivityStatRow("范围", "$min–$max $unit")
            Spacer(Modifier.height(10.dp))
            ActivityStatRow("平均", "$avg $unit")
            Spacer(Modifier.height(10.dp))
            ActivityStatRow("最高", "$max $unit")
            Spacer(Modifier.height(10.dp))
            ActivityStatRow("最低", "$min $unit")
        }
    }
}

@Composable
private fun ActivityStatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(0xFF1C1C1E), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Text(value, color = Color(0xFF1C1C1E), fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

private fun metricValues(metric: String, period: Int): List<Int> = when (period) {
    0 -> activityValues(metric, 0)
    1 -> activityValues(metric, 1)
    2 -> activityValues(metric, 2)
    3 -> List(26) { index -> 5 + (index * 7 + metric.length * 3) % 9 }
    else -> List(12) { index -> 5 + (index * 5 + metric.length) % 8 }
}

private fun periodLabels(period: Int, today: LocalDate): List<String> = when (period) {
    0 -> (0..23).map { "${it}时" }
    1 -> listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    2 -> (1..30).map { "${it}日" }
    3 -> (0..25).map { "${today.minusWeeks((25 - it).toLong()).monthValue}月" }
    else -> (1..12).map { "${it}月" }
}
