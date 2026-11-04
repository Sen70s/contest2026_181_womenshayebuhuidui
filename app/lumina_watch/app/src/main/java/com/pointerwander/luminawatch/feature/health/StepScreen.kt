package com.pointerwander.luminawatch.feature.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.ui.LiquidGlassSurface
import com.pointerwander.luminawatch.ui.LiquidGlassSlidingSegmented
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.pointerwander.luminawatch.ui.LiquidGlassBottomSheet
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassStickyHeader
import com.pointerwander.luminawatch.ui.LiquidGlassTopBarIconButton
import com.pointerwander.luminawatch.ui.LocalBottomBarInset
import com.pointerwander.luminawatch.ui.LocalWatchColors
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.ceil

private val stepOrange = Color(0xFFFF6B35)
private val stepGray = Color(0xFF86868B)
private val weekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private data class StepBucket(val label: String, val detail: String, val value: Int)

@Composable
fun StepScreen(store: AppStore, onBack: () -> Unit) {
    val colors = LocalWatchColors.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val backdrop = rememberLayerBackdrop()
    var period by rememberSaveable { mutableIntStateOf(1) }
    var selected by remember(period) { mutableIntStateOf(-1) }
    var showAdd by remember { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()
    // Reading the state list here ensures the chart refreshes immediately after a manual save.
    val entries = store.manualStepEntries.toList()
    val buckets = remember(period, today, entries, store.canPreviewHealth) {
        stepBuckets(period, today, store.canPreviewHealth, entries)
    }
    val average = if (buckets.isEmpty()) 0 else if (period == 0) buckets.sumOf { it.value }
        else buckets.sumOf { it.value } / buckets.size
    val range = when (period) {
        0 -> "今天"
        1 -> "${today.minusDays(6).year}年${today.minusDays(6).monthValue}月${today.minusDays(6).dayOfMonth}日—${today.monthValue}月${today.dayOfMonth}日"
        2 -> "${today.minusDays(30).year}年${today.minusDays(30).monthValue}月${today.minusDays(30).dayOfMonth}日—${today.monthValue}月${today.dayOfMonth}日"
        3 -> "${today.minusDays(181).year}年${today.minusDays(181).monthValue}月${today.minusDays(181).dayOfMonth}日—${today.monthValue}月${today.dayOfMonth}日"
        else -> "${today.year}年"
    }

    Box(Modifier.fillMaxSize().background(colors.groupedSurface)) {
        LazyColumn(
            Modifier.fillMaxSize().layerBackdrop(backdrop),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item { Spacer(Modifier.fillMaxWidth().height(84.dp + statusBarHeight).background(colors.surface)) }
            item {
                Column(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(14.dp))
                    StepPeriodSelector(period) { period = it }
                    Spacer(Modifier.height(16.dp))
                    StepChartHeading(buckets, period, selected, average, range)
                    StepChart(buckets, period, selected) { selected = it }
                    Spacer(Modifier.height(24.dp))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("提要", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                    Text(if (showAll) "收起" else "全部显示",
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) { showAll = !showAll },
                        color = colors.accent, fontSize = 15.sp)
                }
            }
            if (store.canPreviewHealth) {
                item {
                    StepComparisonCard(
                        "今年到目前为止，你一天完成的步数比去年少。",
                        "${today.year}年", 5632 + entries.filter { yearOf(it.timestamp) == today.year }.sumOf { it.steps } / max(1, today.dayOfYear),
                        "${today.year - 1}年", 6045,
                    )
                }
                if (showAll) item {
                    val addedToday = entries.filter {
                        Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() == today
                    }.sumOf { it.steps }
                    StepTodayComparisonCard(todaySteps = 12_612 + addedToday, averageSteps = 6_852)
                }
            } else {
                item {
                    Text("暂无可比较的历史数据", Modifier.padding(22.dp), color = stepGray, fontSize = 14.sp)
                }
            }
            item {
                val uriHandler = LocalUriHandler.current
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)) {
                    Text("关于步数", color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(24.dp))
                        .padding(20.dp)) {
                        Text("步数是指你一天中走了多少步。计步器和数字运动追踪器可帮助你确定步数。这类设备会为所有产生步数的活动计算步数，如走路、跑步、上下楼梯、越野滑雪，甚至做家务活等活动。",
                            color = colors.textPrimary, fontSize = 17.sp, lineHeight = 26.sp,
                            fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(20.dp))
                        Text("Mayo Clinic", Modifier.clickable {
                            uriHandler.openUri("https://www.mayoclinic.org/healthy-lifestyle/fitness/in-depth/walking/art-20047880")
                        }, color = Color(0xFF1686D9), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            item { Spacer(Modifier.height(36.dp + LocalBottomBarInset.current)) }
        }
        LiquidGlassStickyHeader(
            title = "步数", onBack = onBack, backdrop = backdrop,
            topInset = statusBarHeight,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LiquidGlassTopBarIconButton(Icons.Rounded.Add, "添加步数", { showAdd = true }, backdrop)
        }
    }
    if (showAdd) AddStepsSheet(store, onDismiss = { showAdd = false })
}

@Composable
private fun StepChartHeading(
    buckets: List<StepBucket>, period: Int, selected: Int, average: Int, range: String,
) {
    val colors = LocalWatchColors.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(112.dp)) {
        if (selected in buckets.indices) {
            val popupWidth = minOf(if (period == 3) 238.dp else 220.dp, maxWidth)
            val plotWidth = (maxWidth - 48.dp).coerceAtLeast(0.dp)
            val barCenter = plotWidth * (selected + .5f) / buckets.size
            val popupX = (barCenter - popupWidth / 2).coerceIn(0.dp, maxWidth - popupWidth)
            Canvas(Modifier.fillMaxSize()) {
                val x = barCenter.toPx()
                drawLine(Color(0xFFC8C8CD), Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
            }
            Column(
                Modifier.offset(x = popupX, y = (-12).dp).width(popupWidth)
                    .align(Alignment.TopStart)
                    .background(colors.surfaceMuted, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(if (period >= 3) "日均" else "总计", color = stepGray,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatSteps(buckets[selected].value), color = colors.textPrimary,
                        fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("步", Modifier.padding(start = 6.dp, bottom = 5.dp),
                        color = stepGray, fontSize = 16.sp)
                }
                Text(buckets[selected].detail, color = stepGray, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        } else {
            Column(Modifier.align(Alignment.TopStart)) {
                Text(if (period == 0) "总计" else if (period >= 3) "日均" else "平均",
                    color = stepGray, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatSteps(average), fontSize = 42.sp, fontWeight = FontWeight.Bold,
                        color = colors.textPrimary)
                    Text("步", Modifier.padding(start = 6.dp, bottom = 8.dp),
                        fontSize = 18.sp, color = stepGray)
                }
                Text(range, color = stepGray, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StepPeriodSelector(selected: Int, onSelected: (Int) -> Unit) {
    LiquidGlassSlidingSegmented(
        options = listOf("日", "周", "月", "6个月", "年"),
        selectedIndex = selected,
        onSelected = onSelected,
    )
}

@Composable
private fun StepChart(buckets: List<StepBucket>, period: Int, selected: Int, onSelected: (Int) -> Unit) {
    val colors = LocalWatchColors.current
    val peak = buckets.maxOfOrNull { it.value } ?: 0
    val step = if (period == 0) 1_000 else if (period == 3) 5_000 else 10_000
    val minimum = if (period == 0) 1_000 else if (period == 3) 10_000 else 20_000
    val maxValue = max(minimum, ceil(peak.toDouble() / step).toInt() * step)
    val ticks = when (period) {
        0 -> listOf(0, 6, 12, 18)
        1 -> buckets.indices.toList()
        2 -> buckets.indices.filter { it % 7 == 0 }
        3 -> buckets.indices.filter { it == 0 || buckets[it].label != buckets[it - 1].label }
            .let { starts -> if (starts.size > 1 && starts[1] - starts[0] < 3) starts.drop(1) else starts }
        else -> buckets.indices.filter { it % 2 == 0 }
    }
    Column {
        Row(Modifier.fillMaxWidth().height(290.dp)) {
            Canvas(Modifier.weight(1f).fillMaxSize().pointerInput(buckets) {
                detectTapGestures { point ->
                    if (buckets.isNotEmpty()) onSelected(((point.x / size.width) * buckets.size).toInt().coerceIn(buckets.indices))
                }
            }) {
                val chartHeight = size.height - 12.dp.toPx()
                repeat(3) { line ->
                    val y = chartHeight * line / 2f
                    drawLine(Color(0xFFE8E8ED), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                if (buckets.isNotEmpty()) {
                    val slot = size.width / buckets.size
                    val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
                    ticks.drop(1).forEach { index ->
                        val x = index * slot
                        drawLine(Color(0xFFDADAE0), Offset(x, 0f), Offset(x, chartHeight),
                            0.7.dp.toPx(), pathEffect = dash)
                    }
                    if (selected in buckets.indices) {
                        val selectedBucket = buckets[selected]
                        val x = (selected + .5f) * slot
                        val barTop = chartHeight * (1f - selectedBucket.value.toFloat() / maxValue)
                        drawLine(Color(0xFFC8C8CD), Offset(x, 0f), Offset(x, barTop), 1.5.dp.toPx())
                    }
                    buckets.forEachIndexed { index, bucket ->
                        val width = (slot * if (period == 1) .62f else .54f)
                            .coerceIn(2.dp.toPx(), if (period == 1) 32.dp.toPx() else 10.dp.toPx())
                        val height = chartHeight * bucket.value / maxValue
                        if (height > 0f) drawRoundRect(
                            color = Color(0xFFFF5A00),
                            topLeft = Offset(index * slot + (slot - width) / 2f, chartHeight - height),
                            size = Size(width, height), cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                }
            }
            Column(Modifier.width(48.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(formatSteps(maxValue), color = stepGray, fontSize = 11.sp)
                Text(formatSteps(maxValue / 2), color = stepGray, fontSize = 11.sp)
                Text("0", color = stepGray, fontSize = 11.sp)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(end = 48.dp).height(22.dp)) {
            if (buckets.isNotEmpty()) {
                val slot = maxWidth / buckets.size
                val labelWidth = 44.dp
                ticks.forEach { index ->
                    val offset = (slot * (index + .5f) - labelWidth / 2)
                        .coerceIn(0.dp, (maxWidth - labelWidth).coerceAtLeast(0.dp))
                    Text(buckets[index].label, Modifier.offset(x = offset).width(labelWidth),
                        color = stepGray, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun StepTodayComparisonCard(todaySteps: Int, averageSteps: Int) {
    val colors = LocalWatchColors.current
    val todayLine = listOf(
        0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, .06f, .15f, .32f, .34f,
        .35f, .35f, .35f, .35f, .46f, .53f, .64f, .64f, .70f, .70f, .86f, 1f,
    )
    val averageLine = listOf(
        0f, .01f, .015f, .02f, .02f, .025f, .03f, .04f, .08f, .12f, .16f, .19f,
        .25f, .27f, .30f, .33f, .37f, .40f, .44f, .49f, .54f, .58f, .61f, .64f,
    )
    val grayLine = Color(0xFFD0D0D4)
    val chartMaximum = max(1, max(todaySteps, averageSteps))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .background(colors.surface, RoundedCornerShape(24.dp)).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null,
                tint = Color(0xFFFF5A00), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(4.dp))
            Text("步数", color = Color(0xFFFF5A00), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(if (todaySteps >= averageSteps) "你步行比平时多。" else "你步行比平时少。",
            color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Spacer(Modifier.height(18.dp))
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE6E6E8)))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("● 今天", color = Color(0xFFFF5A00), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("${formatSteps(todaySteps)}步", color = Color(0xFFFF5A00),
                    fontWeight = FontWeight.Bold, fontSize = 26.sp, maxLines = 1)
            }
            Column(Modifier.weight(1f)) {
                Text("● 平均", color = stepGray, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("${formatSteps(averageSteps)}步", color = stepGray,
                    fontWeight = FontWeight.Bold, fontSize = 26.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(14.dp))
        Canvas(Modifier.fillMaxWidth().height(205.dp)) {
            val left = 4.dp.toPx()
            val right = size.width - 4.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 20.dp.toPx()
            val chartHeight = bottom - top
            fun point(index: Int, value: Float) = Offset(
                left + (right - left) * index / 23f,
                bottom - value * chartHeight,
            )
            val orange = todayLine.mapIndexed { index, value ->
                point(index, value * todaySteps / chartMaximum)
            }
            val gray = averageLine.mapIndexed { index, value ->
                point(index, value / .64f * averageSteps / chartMaximum)
            }
            drawLine(grayLine, Offset(right, top), Offset(right, bottom), 1.5.dp.toPx())
            for (index in 0..23) {
                drawCircle(Color(0xFFC9C9CD), 1.dp.toPx(), Offset(point(index, 0f).x, bottom + 10.dp.toPx()))
            }
            gray.zipWithNext().forEach { (start, end) ->
                drawLine(grayLine, start, end, 2.5.dp.toPx(), cap = StrokeCap.Round)
            }
            orange.zipWithNext().forEach { (start, end) ->
                drawLine(Color(0xFFFF5A00), start, end, 3.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(colors.surface, 6.dp.toPx(), gray.last())
            drawCircle(stepGray, 4.5.dp.toPx(), gray.last())
            drawCircle(colors.surface, 7.dp.toPx(), orange.last())
            drawCircle(Color(0xFFFF5A00), 5.dp.toPx(), orange.last())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("00:00", color = stepGray, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("23:00", color = stepGray, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StepComparisonCard(text: String, currentLabel: String, current: Int, previousLabel: String, previous: Int) {
    val colors = LocalWatchColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        .background(colors.surface, RoundedCornerShape(24.dp)).padding(20.dp)) {
        Text("● 步数", color = stepOrange, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(14.dp))
        Text(text, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(18.dp))
        val maximum = max(1, max(current, previous))
        listOf(Triple(currentLabel, current, stepOrange), Triple(previousLabel, previous, Color(0xFFD1D1D6))).forEach { (label, value, color) ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatSteps(value), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 25.sp)
                Text(" 步/天", color = stepGray, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Box(Modifier.fillMaxWidth().height(28.dp).background(Color(0xFFF0F0F3), RoundedCornerShape(5.dp))) {
                Box(Modifier.fillMaxWidth(value.toFloat() / maximum).height(28.dp)
                    .background(color, RoundedCornerShape(5.dp)))
                Text(label, Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    color = if (color == stepOrange) Color.White else colors.textPrimary,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Spacer(Modifier.height(17.dp))
        }
    }
}

private fun stepBuckets(period: Int, today: LocalDate, preview: Boolean, entries: List<com.pointerwander.luminawatch.data.ManualStepEntry>): List<StepBucket> {
    val zone = ZoneId.systemDefault()
    fun manual(day: LocalDate): Int = entries.sumOf {
        if (Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == day) it.steps else 0
    }
    fun mock(day: LocalDate): Int {
        if (!preview || day.isAfter(today)) return 0
        if (day == today) return 2847
        val weekBase = Math.floorMod(day.toEpochDay() / 7L * 4237L + 521L, 8000L).toInt()
        val daySwing = Math.floorMod(day.toEpochDay() * 1723L + 2937L, 4000L).toInt()
        return (1000 + weekBase + daySwing - 1600).coerceAtLeast(500)
    }
    fun dayValue(day: LocalDate) = mock(day) + manual(day)
    fun detail(day: LocalDate) = "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
    return when (period) {
        0 -> (0..23).map { hour ->
            val added = entries.sumOf { entry ->
                val local = Instant.ofEpochMilli(entry.timestamp).atZone(zone)
                if (local.toLocalDate() == today && local.hour == hour) entry.steps else 0
            }
            val mockHour = if (preview) listOf(
                300, 0, 0, 0, 0, 0, 0, 240, 360, 370, 350, 70,
                0, 0, 0, 0, 450, 230, 0, 150, 0, 0, 327, 0,
            )[hour] else 0
            StepBucket("${hour}时", "${today.monthValue}月${today.dayOfMonth}日 ${hour}:00—${hour + 1}:00", mockHour + added)
        }
        1 -> {
            (0..6).map { index ->
                val day = today.minusDays((6 - index).toLong())
                StepBucket(weekdayLabels[day.dayOfWeek.value - 1], detail(day), dayValue(day))
            }
        }
        2 -> (0..30).map { index ->
            val day = today.minusDays((30 - index).toLong())
            StepBucket("${day.dayOfMonth}日", detail(day), dayValue(day))
        }
        3 -> (0 until 26).map { index ->
            val weekStart = today.minusDays(181).plusDays(index * 7L)
            val weekEnd = weekStart.plusDays(6)
            val value = (0..6).sumOf { dayValue(weekStart.plusDays(it.toLong())) }
            StepBucket("${weekStart.monthValue}月", "${weekStart.year}年${weekStart.monthValue}月${weekStart.dayOfMonth}日—${weekEnd.monthValue}月${weekEnd.dayOfMonth}日", value / 7)
        }
        else -> (1..12).map { monthNumber ->
            val month = today.withMonth(monthNumber).withDayOfMonth(1)
            val value = (0 until month.lengthOfMonth()).sumOf { dayValue(month.plusDays(it.toLong())) }
            StepBucket("${monthNumber}月", "${today.year}年${monthNumber}月", value / month.lengthOfMonth())
        }
    }
}

private fun yearOf(timestamp: Long) = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).year
private fun monthOf(timestamp: Long) = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).monthValue
private fun formatSteps(value: Int) = NumberFormat.getIntegerInstance().format(value)
