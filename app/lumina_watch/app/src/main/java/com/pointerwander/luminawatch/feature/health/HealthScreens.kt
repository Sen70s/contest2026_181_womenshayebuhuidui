package com.pointerwander.luminawatch.feature.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.app.Screen
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.Metric
import com.pointerwander.luminawatch.ui.DemoBadge
import com.pointerwander.luminawatch.ui.EmptyState
import com.pointerwander.luminawatch.ui.GlassCard
import com.pointerwander.luminawatch.ui.FrostedCard
import com.pointerwander.luminawatch.ui.GlassSegmented
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.MetricTile
import com.pointerwander.luminawatch.ui.PageColumn
import com.pointerwander.luminawatch.ui.PageHeader
import com.pointerwander.luminawatch.ui.PrimaryButton
import com.pointerwander.luminawatch.ui.SectionTitle
import com.pointerwander.luminawatch.ui.SecondaryButton
import com.pointerwander.luminawatch.ui.SourceTag
import com.pointerwander.luminawatch.ui.SurfaceCard
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HealthScreen(store: AppStore, onOpen: (Screen) -> Unit, onBack: (() -> Unit)? = null) {
    val colors = LocalWatchColors.current
    val heart = store.latestSample(Metric.HEART_RATE)
    val oxygen = store.latestSample(Metric.OXYGEN)
    val todayMood = store.todayMoods().firstOrNull()

    PageColumn {
        PageHeader(
            title = "健康",
            subtitle = "只展示小云手表支持的指标",
            onBack = onBack,
            actions = {
                com.pointerwander.luminawatch.ui.DarkCircleIconButton(
                    Icons.Rounded.Mood,
                    "心情月历",
                ) { onOpen(Screen.MoodCalendar) }
            },
        )
        DemoBadge()

        var periodIndex by rememberSaveable { mutableIntStateOf(0) }
        val period = Period.entries[periodIndex]
        val points = remember(period) { healthSeries(Metric.HEART_RATE, period) }
        var selectedIndex by remember(period) { mutableIntStateOf(points.lastIndex) }
        LaunchedEffect(period) { selectedIndex = points.lastIndex }

        SectionTitle("重点指标")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                metric = Metric.HEART_RATE,
                value = heart?.value?.toString() ?: "--",
                unit = "次/分",
                caption = heart?.timeLabel ?: "暂无数据",
                accent = colors.heart,
                onClick = { onOpen(Screen.HeartDetail) },
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                metric = Metric.OXYGEN,
                value = oxygen?.value?.toString() ?: "--",
                unit = "%",
                caption = oxygen?.timeLabel ?: "暂无数据",
                accent = colors.oxygen,
                onClick = { onOpen(Screen.OxygenDetail) },
                modifier = Modifier.weight(1f),
            )
        }

        GlassSegmented(
            options = Period.entries.map { it.label },
            selectedIndex = periodIndex,
            onSelected = { periodIndex = it },
        )
        FrostedCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("心率趋势", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text(
                    "${points[selectedIndex.coerceIn(points.indices)].label} · " +
                        "${points[selectedIndex.coerceIn(points.indices)].value.roundToInt()} 次/分",
                    color = colors.textSecondary, fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            HealthChart(
                points = points,
                color = colors.heart,
                unit = "次/分",
                discrete = false,
                selectedIndex = selectedIndex,
                onSelected = { selectedIndex = it },
            )
        }
        FrostedCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val values = points.map { it.value }
                listOf(
                    "最低" to values.minOrNull(),
                    "平均" to values.average().toFloat(),
                    "最高" to values.maxOrNull(),
                ).forEach { (label, value) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = colors.textSecondary, fontSize = 11.sp)
                        Text(
                            value?.roundToInt()?.toString() ?: "--",
                            color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        if (heart == null && oxygen == null) {
            EmptyState(
                title = "暂时没有测量记录",
                body = "连接手表并完成一次测量后，记录会显示在这里。",
            )
        } else {
            val result = guardResult(Metric.OXYGEN, oxygen?.value?.toFloat() ?: 98f)
            FrostedCard {
                Text("小云的观察", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text(result.message, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(6.dp))
                Text("健康提示不构成诊断。", color = colors.textSecondary, fontSize = 11.sp)
            }
        }

        SectionTitle("心理健康")
            FrostedCard(Modifier.clickable { onOpen(Screen.MoodCalendar) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(colors.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Mood, null, tint = colors.accent, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (todayMood == null) "今天还没有心情记录" else "今天已记录 ${store.todayMoods().size} 次",
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (todayMood == null) "记录连续的情绪值，不需要选择标签式答案" else
                                todayMood.words.joinToString("、"),
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("记录此刻", { onOpen(Screen.MoodRecord()) }, Modifier.weight(1f))
                    SecondaryButton("心情月历", { onOpen(Screen.MoodCalendar) }, Modifier.weight(1f))
                }
            }

            SectionTitle("了解这些数据")
            FrostedCard {
                Text(
                    "心率与血氧记录供日常参考，不替代专业医疗判断。连接手表后可查看实际测量。",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
    }
}

@Composable
fun MetricDetailScreen(
    store: AppStore,
    metric: Metric,
    onBack: () -> Unit,
    onOpen: (Screen) -> Unit,
) {
    val colors = LocalWatchColors.current
    val accent = if (metric == Metric.HEART_RATE) colors.heart else colors.oxygen
    val title = if (metric == Metric.HEART_RATE) "心率" else "血氧饱和度"
    val unit = if (metric == Metric.HEART_RATE) "次/分" else "%"
    val source = store.latestSample(metric)
    val scrollState = rememberScrollState()

    var periodIndex by rememberSaveable(metric) { mutableIntStateOf(0) }
    val period = Period.entries[periodIndex]
    val points = remember(metric, period) { healthSeries(metric, period) }
    var selectedIndex by remember(metric, period) { mutableIntStateOf(points.lastIndex) }

    LaunchedEffect(period) { selectedIndex = points.lastIndex }
    LaunchedEffect(metric) { scrollState.scrollTo(0) }

    val selectedPoint = points[selectedIndex.coerceIn(points.indices)]
    val result = guardResult(metric, selectedPoint.value)

    if (source == null) {
        PageColumn {
            PageHeader(title, "健康趋势", onBack = onBack)
            EmptyState("暂无测量记录", "连接手表并完成一次测量后，记录会显示在这里。")
        }
        return
    }

    PageColumn(scrollState = scrollState) {
        val today = LocalDate.now()
        PageHeader(title, "${today.monthValue}月${today.dayOfMonth}日", onBack = onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha = .12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (metric == Metric.HEART_RATE) Icons.Rounded.FavoriteBorder else Icons.Rounded.Bloodtype,
                        null,
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("当前读数", color = colors.textSecondary, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            source?.value?.toString() ?: "--",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Light,
                            fontSize = 52.sp,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(unit, color = colors.textSecondary, modifier = Modifier.padding(bottom = 9.dp))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source?.timeLabel ?: "尚无测量记录", color = colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
            }
        }

        GlassSegmented(
            options = Period.entries.take(3).map { it.label },
            selectedIndex = periodIndex,
            onSelected = { periodIndex = it },
        )

        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${period.label}趋势", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${selectedPoint.label} · ${selectedPoint.value.roundToInt()} $unit",
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Icon(Icons.Rounded.TouchApp, null, tint = colors.textSecondary, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.height(10.dp))
            if (store.showHealthData) {
                HealthChart(
                    points = points,
                    color = accent,
                    unit = unit,
                    discrete = isDiscrete(metric),
                    selectedIndex = selectedIndex,
                    onSelected = { selectedIndex = it },
                )
                Spacer(Modifier.height(8.dp))
                Text("点按或拖动图表，查看每个时间点的读数。", color = colors.textSecondary, fontSize = 12.sp)
            } else {
                Text("暂无数据。连接手表并完成一次测量后可查看趋势。", color = colors.textSecondary, fontSize = 13.sp)
            }
        }

        if (store.showHealthData) {
            val values = points.map { it.value }
            SectionTitle(if (period == Period.DAY) "今日概览" else if (period == Period.WEEK) "本周概览" else "本月概览")
            FrostedCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatColumn("最低", values.minOrNull()?.roundToInt() ?: 0, unit, colors.textPrimary, Modifier.weight(1f))
                    StatColumn("平均", values.average().roundToInt(), unit, colors.textPrimary, Modifier.weight(1f))
                    StatColumn("最高", values.maxOrNull()?.roundToInt() ?: 0, unit, colors.textPrimary, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "共 ${points.size} 条记录",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                )
            }

            InsightCard(result, accent)

            SectionTitle("了解$title")
            FrostedCard {
                Text(
                    if (metric == Metric.HEART_RATE) {
                        "心率会随活动、休息和情绪变化。请结合当时的状态理解记录。"
                    } else {
                        "血氧读数会受到佩戴、动作、皮肤温度等因素影响，请在合适条件下复测。"
                    },
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text("本应用只提供健康提示，不提供诊断或治疗建议。", color = colors.textSecondary, fontSize = 11.sp)
            }

            PrimaryButton(
                "记录今天的心情",
                { onOpen(Screen.MoodRecord()) },
                Modifier.fillMaxWidth(),
                Icons.Rounded.Mood,
            )
        }
    }
}

@Composable
private fun StatColumn(label: String, value: Int, unit: String, color: Color, modifier: Modifier = Modifier) {
    val colors = LocalWatchColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(label, color = colors.textSecondary, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$value", color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(2.dp))
            Text(unit, color = colors.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun InsightCard(result: GuardResult, accent: Color) {
    val colors = LocalWatchColors.current
    val tone = when (result.level) {
        GuardLevel.STABLE -> colors.oxygen
        GuardLevel.NOTICE -> colors.accent
        GuardLevel.IMPORTANT -> colors.danger
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(tone.copy(alpha = .12f), colors.surface)))
            .border(1.dp, tone.copy(alpha = .18f), RoundedCornerShape(22.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (result.level == GuardLevel.STABLE) Icons.Rounded.HealthAndSafety else Icons.Rounded.WarningAmber,
                null,
                tint = tone,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(result.title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(result.message, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(5.dp))
        Text(
            if (result.level == GuardLevel.STABLE) "状态平稳" else "健康提示 · 建议复测",
            color = tone,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun HealthChart(
    points: List<HealthPoint>,
    color: Color,
    unit: String,
    discrete: Boolean,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    val colors = LocalWatchColors.current
    val haptic = LocalHapticFeedback.current
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val range = (max - min).coerceAtLeast(1f)
    var lastHapticIndex by remember { mutableIntStateOf(selectedIndex) }

    fun selectFromX(x: Float, width: Float) {
        val inset = 18f
        val usable = (width - inset * 2f).coerceAtLeast(1f)
        val index = (((x - inset) / usable) * (points.size - 1)).roundToInt().coerceIn(points.indices)
        if (index != lastHapticIndex) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastHapticIndex = index
        }
        onSelected(index)
    }

    Canvas(
        Modifier.fillMaxWidth().height(240.dp)
            .semantics {
                contentDescription =
                    "可拖动趋势图，当前 ${points[selectedIndex.coerceIn(points.indices)].label}，" +
                        "${points[selectedIndex.coerceIn(points.indices)].value.roundToInt()} $unit"
            }
            .pointerInput(points) {
                detectHorizontalDragGestures(
                    onDragStart = { selectFromX(it.x, size.width.toFloat()) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        selectFromX(change.position.x, size.width.toFloat())
                    },
                )
            }
            .pointerInput(points) {
                detectTapGestures(onTap = { selectFromX(it.x, size.width.toFloat()) })
            },
    ) {
        val left = 18f
        val right = size.width - 18f
        val top = 16f
        val bottom = size.height - 22f
        val chartHeight = bottom - top
        repeat(3) { row ->
            val y = top + chartHeight * row / 2f
            drawLine(colors.outline.copy(alpha = .6f), Offset(left, y), Offset(right, y), strokeWidth = 1.2f)
        }
        val coordinates = points.mapIndexed { index, point ->
            val x = if (points.size == 1) (left + right) / 2f
            else left + (right - left) * index / (points.size - 1)
            val y = bottom - ((point.value - min) / range) * chartHeight
            Offset(x, y)
        }

        if (!discrete && coordinates.size > 1) {
            val fillPath = Path().apply {
                moveTo(coordinates.first().x, bottom)
                coordinates.forEach { lineTo(it.x, it.y) }
                lineTo(coordinates.last().x, bottom)
                close()
            }
            drawPath(
                fillPath,
                Brush.verticalGradient(listOf(color.copy(alpha = .22f), Color.Transparent), top, bottom),
            )
            val linePath = Path().apply {
                moveTo(coordinates.first().x, coordinates.first().y)
                coordinates.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(linePath, color, style = Stroke(width = 5.5f))
        } else {
            coordinates.forEachIndexed { index, point ->
                drawLine(
                    color.copy(alpha = if (index == selectedIndex) 1f else .82f),
                    Offset(point.x, point.y),
                    Offset(point.x, bottom),
                    strokeWidth = (if (index == selectedIndex) 9.dp else 7.dp).toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        coordinates.forEachIndexed { index, point ->
            val selected = index == selectedIndex
            if (discrete) {
                drawCircle(color, radius = (if (selected) 5.dp else 3.5.dp).toPx(), center = point)
            } else {
                drawCircle(if (selected) colors.surface else color, radius = if (selected) 9f else 4.5f, center = point)
            }
            if (selected && !discrete) {
                drawLine(color.copy(alpha = .4f), Offset(point.x, top), Offset(point.x, bottom), strokeWidth = 1.5f)
                drawCircle(color, radius = 5f, center = point)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(points.first().label, color = colors.textSecondary, fontSize = 10.sp)
        Text(points.last().label, color = colors.textSecondary, fontSize = 10.sp)
    }
}
