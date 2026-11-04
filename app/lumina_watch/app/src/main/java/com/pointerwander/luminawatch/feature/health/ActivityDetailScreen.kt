package com.pointerwander.luminawatch.feature.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.ui.EmptyState
import com.pointerwander.luminawatch.ui.FrostedCard
import com.pointerwander.luminawatch.ui.GlassSegmented
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.PageColumn
import com.pointerwander.luminawatch.ui.PageHeader
import com.pointerwander.luminawatch.ui.SurfaceCard
import com.pointerwander.luminawatch.ui.SourceTag
import java.time.LocalDate
import java.text.NumberFormat

private val stepDay = listOf(0, 0, 280, 0, 0, 0, 0, 380, 620, 220, 590, 0, 440, 0, 170, 0, 147, 0, 0, 0, 0, 0, 0, 0)
private val stepWeek = listOf(2847, 6200, 7900, 5100, 9200, 6700, 8100)
private val stepMonth = listOf(2800, 6100, 9700, 12400, 8200, 7300, 5600, 11300, 9200, 6400, 4100, 8000, 10100, 7300, 5400, 8900, 6300, 11800, 2847)
private val standingDay = listOf(0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0)

@Composable
fun ActivityDetailScreen(metric: String, store: AppStore, onBack: () -> Unit) {
    if (metric == "步数") {
        StepScreen(store, onBack)
        return
    }
    ActivityMetricScreen(metric, onBack)
    return
    val colors = LocalWatchColors.current
    if (!store.canPreviewHealth) {
        PageColumn {
            PageHeader(metric, onBack = onBack)
            EmptyState(
                "暂无手表数据",
                "这个指标的入口仍然保留。连接并同步小云手表后，可以在这里查看实际记录。",
            )
        }
        return
    }
    val isStanding = metric == "站立时间"
    val isSteps = metric == "步数"
    val accent = when {
        isStanding -> Color(0xFF20C66B)
        isSteps -> Color(0xFFFFB900)
        else -> Color(0xFFFF7043)
    }
    var period by rememberSaveable(metric) { mutableIntStateOf(0) }
    val values = remember(metric, period) { activityValues(metric, period) }
    var selected by remember(metric, period) { mutableIntStateOf(values.lastIndex) }
    val title = if (isStanding) "站立活动" else metric
    val today = LocalDate.now()
    val periodLabel = when (period) {
        0 -> "${today.monthValue}月${today.dayOfMonth}日"
        1 -> "本周"
        else -> "${today.year}年${today.monthValue}月"
    }
    val totalValue = values.sum()
    val total = NumberFormat.getIntegerInstance().format(totalValue)
    val unit = if (isStanding) "次" else if (isSteps) "步" else "千卡"

    PageColumn {
        PageHeader(title, periodLabel, onBack = onBack)
        GlassSegmented(listOf("日", "周", "月"), period, { period = it })
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                Text(total, color = colors.textPrimary, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(unit, color = colors.textPrimary, fontSize = 15.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 7.dp))
            }
            Text(periodLabel, color = colors.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            ActivityBars(values, accent, isStanding && period == 0, selected) { selected = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (period == 0) "00:00" else if (period == 1) "周一" else "1",
                    color = colors.textSecondary, fontSize = 10.sp)
                Text(if (period == 0) "12:00" else if (period == 1) "周四" else "15",
                    color = colors.textSecondary, fontSize = 10.sp)
                Text(if (period == 0) "24:00" else if (period == 1) "周日" else "31",
                    color = colors.textSecondary, fontSize = 10.sp)
            }
        }
        FrostedCard {
            Text(if (period == 0) "今日概览" else if (period == 1) "本周概览" else "本月概览",
                color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(total + unit, color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(if (period == 0) "今日累计" else "累计", color = colors.textSecondary, fontSize = 12.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            isSteps -> if (period == 0) "%.2f 公里".format(totalValue * .000635) else "${NumberFormat.getIntegerInstance().format(totalValue / values.size)} 步"
                            isStanding -> if (period == 0) "$totalValue / 12 小时" else "日均 ${totalValue / values.size} 次"
                            else -> if (period == 0) "目标 500 千卡" else "日均 ${totalValue / values.size} 千卡"
                        },
                        color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(if (isSteps) { if (period == 0) "估算距离" else "日均步数" } else "目标与趋势",
                        color = colors.textSecondary, fontSize = 12.sp)
                }
            }
        }
        if (isStanding) {
            FrostedCard {
                Text("站立提醒", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text("在久坐时，小云可以提醒你起身活动。连接手表后可查看实际记录。",
                    color = colors.textSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ActivityBars(
    values: List<Int>, color: Color, binary: Boolean, selected: Int, onSelected: (Int) -> Unit,
) {
    Canvas(
        Modifier.fillMaxWidth().height(230.dp)
            .pointerInput(values) {
                detectHorizontalDragGestures(onDragStart = {
                    onSelected(((it.x / size.width) * values.size).toInt().coerceIn(values.indices))
                }, onHorizontalDrag = { change, _ ->
                    onSelected(((change.position.x / size.width) * values.size).toInt().coerceIn(values.indices))
                    change.consume()
                })
            }
            .pointerInput(values) {
                detectTapGestures { onSelected(((it.x / size.width) * values.size).toInt().coerceIn(values.indices)) }
            },
    ) {
        val max = (values.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        repeat(5) { row ->
            val y = size.height * row / 4f
            drawLine(Color(0xFFE7E9EC), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        val slot = size.width / values.size
        values.forEachIndexed { index, value ->
            val width = (slot * if (binary) .28f else .55f).coerceAtLeast(3.dp.toPx())
            val barHeight = if (binary) size.height * .94f else size.height * (value / max * .82f)
            if (binary) {
                drawRoundRect(color.copy(alpha = .12f), Offset(index * slot + (slot - width) / 2f, 0f),
                    Size(width, size.height * .94f), CornerRadius(width / 2f))
            }
            if (value > 0) {
                drawRoundRect(color.copy(alpha = if (index == selected) 1f else .75f),
                    Offset(index * slot + (slot - width) / 2f, size.height * .94f - barHeight),
                    Size(width, barHeight), CornerRadius(width / 2f))
            }
        }
    }
}
/** Shared with the health summary cards so mini charts match the detail page. */
internal fun activityValues(metric: String, period: Int): List<Int> = when (metric) {
    "步数" -> when (period) { 0 -> stepDay; 1 -> stepWeek; else -> stepMonth }
    "站立时间" -> when (period) {
        0 -> standingDay
        1 -> listOf(7, 8, 5, 9, 7, 6, 8)
        else -> listOf(7, 8, 9, 6, 5, 8, 7, 9, 8, 6, 7, 8, 9, 5, 7, 8, 6, 9, 7)
    }
    else -> when (period) {
        0 -> listOf(2, 4, 3, 8, 15, 4, 9, 27, 31, 11, 18, 25, 17, 13, 10)
        1 -> listOf(197, 215, 174, 260, 228, 301, 192)
        else -> listOf(197, 210, 175, 230, 195, 260, 215, 280, 190, 245, 275, 220, 197)
    }
}
