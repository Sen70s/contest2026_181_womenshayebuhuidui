package com.pointerwander.luminawatch.feature.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.MoodEntry
import com.pointerwander.luminawatch.data.MoodScope
import com.pointerwander.luminawatch.feature.mood.MoodCloud
import com.pointerwander.luminawatch.feature.mood.moodColor
import com.pointerwander.luminawatch.feature.mood.moodRangeLabel
import com.pointerwander.luminawatch.ui.LiquidGlassBottomSheet
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassIconButton
import com.pointerwander.luminawatch.ui.LiquidGlassSlidingSegmented
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.launch

private val chartInk = Color(0xFF171719)
private val chartMuted = Color(0xFF85858A)
private val chartGroup = Color(0xFFF2F1F7)
private val chartBlue = Color(0xFF0A84FF)
private val chartTeal = Color(0xFF05C6B2)

private data class MoodPeriod(val start: LocalDate, val end: LocalDate, val label: String) {
    val startDay: Long get() = start.toEpochDay()
    val endDay: Long get() = end.toEpochDay()
    val dayCount: Long get() = endDay - startDay + 1
}

private fun moodPeriod(index: Int, today: LocalDate): MoodPeriod = when (index) {
    0 -> {
        val sunday = today.minusDays((today.dayOfWeek.value % 7).toLong())
        val saturday = sunday.plusDays(6)
        MoodPeriod(sunday, saturday,
            "${sunday.year}年${sunday.monthValue}月${sunday.dayOfMonth}日–${saturday.monthValue}月${saturday.dayOfMonth}日")
    }
    1 -> {
        val month = YearMonth.from(today)
        MoodPeriod(month.atDay(1), month.atEndOfMonth(), "${month.year}年${month.monthValue}月")
    }
    2 -> {
        val first = YearMonth.from(today).minusMonths(5)
        val last = YearMonth.from(today)
        MoodPeriod(first.atDay(1), last.atEndOfMonth(),
            "${first.year}年${first.monthValue}月–${last.year}年${last.monthValue}月")
    }
    else -> MoodPeriod(today.withDayOfYear(1),
        today.withDayOfYear(today.lengthOfYear()), "${today.year}年")
}

@Composable
internal fun MentalMoodInsightsSheet(
    store: AppStore,
    onDismiss: () -> Unit,
    onRecord: (Long) -> Unit,
) {
    var period by rememberSaveable { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedRow by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val today = LocalDate.now()
    val range = moodPeriod(period, today)
    val entries = store.moods.filter { it.epochDay in range.startDay..range.endDay }
    val factorLabels = listOf("伴侣", "健康", "健身", "天气", "旅行", "时事", "朋友", "爱好", "社群", "家人", "工作", "教育", "金钱")
    val associations = (factorLabels + entries.flatMap { it.factors }).distinct()
    val rowLabels = when (tab) {
        0 -> listOf("每日心情", "瞬时情绪")
        1 -> associations
        else -> listOf("日照下的时间", "正念分钟数", "睡眠", "锻炼分钟数")
    }
    val highlighted = when (tab) {
        0 -> when (selectedRow) {
            "每日心情" -> entries.filter { it.scope == MoodScope.DAY }
            "瞬时情绪" -> entries.filter { it.scope == MoodScope.MOMENT }
            else -> entries
        }
        1 -> if (selectedRow == null) entries else entries.filter { selectedRow in it.factors }
        else -> emptyList()
    }
    LaunchedEffect(period, tab) {
        selectedRow = null
        selectedDay = null
    }

    LiquidGlassBottomSheet(onDismiss, fullHeight = true,
        surfaceColor = Color.White, showNavigation = false, contentPadding = 0.dp) {
        Column(Modifier.fillMaxSize().background(Color.White)) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                LiquidGlassIconButton(Icons.Rounded.Close, "关闭图表", onDismiss,
                    size = 48.dp, tint = chartInk)
                Text("心理状态", Modifier.weight(1f), color = chartInk, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.width(48.dp))
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                LiquidGlassSlidingSegmented(listOf("周", "月", "6个月", "年"), period,
                    { period = it })
            }
            Spacer(Modifier.height(10.dp))
            val selectedEntries = highlighted.filter { it.epochDay == selectedDay }
            Box(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart) {
                if (selectedDay != null && selectedEntries.isNotEmpty()) {
                    Column(Modifier.clip(RoundedCornerShape(16.dp))
                        .background(chartGroup).clickable { detailDay = selectedDay }
                        .padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selectedRow ?: "总计", color = chartMuted, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold)
                            Icon(Icons.Rounded.ChevronRight, null, tint = chartMuted,
                                modifier = Modifier.size(17.dp))
                        }
                        Text("${selectedEntries.size} 个条目", color = chartInk,
                            fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        Text(dayLabel(selectedDay!!), color = chartMuted, fontSize = 14.sp)
                    }
                } else {
                    Column {
                        Text("总计", color = chartMuted, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${entries.size}", color = chartInk, fontSize = 42.sp,
                                fontWeight = FontWeight.Bold)
                            Text(" 个条目", color = chartMuted, fontSize = 18.sp,
                                modifier = Modifier.padding(bottom = 7.dp))
                        }
                        Text(range.label, color = chartMuted, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            MoodInsightChart(entries, highlighted, range, selectedDay,
                onDaySelected = { selectedDay = it }, Modifier.fillMaxWidth().height(230.dp))
            Column(Modifier.fillMaxWidth().weight(1f).background(chartGroup)) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                    LiquidGlassSlidingSegmented(listOf("状态", "关联", "生活因素"), tab,
                        { tab = it })
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                         start = 18.dp, end = 18.dp, bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rowLabels) { label ->
                        val count = when (tab) {
                            0 -> entries.count {
                                if (label == "每日心情") it.scope == MoodScope.DAY
                                else it.scope == MoodScope.MOMENT
                            }
                            1 -> entries.count { label in it.factors }
                            else -> 0
                        }
                        val selected = selectedRow == label
                        Row(Modifier.fillMaxWidth().height(58.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(if (selected) {
                                if (tab == 2) chartBlue else chartTeal
                            } else Color.White)
                            .clickable {
                                selectedRow = if (selected) null else label
                                selectedDay = null
                            }
                            .padding(horizontal = 17.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = if (selected && tab == 2) Color.White else chartInk,
                                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f))
                            Text(if (tab == 2 || count == 0) "--" else "$count 个条目",
                                color = if (selected && tab == 2) Color.White
                                    else if (count == 0) chartMuted else chartInk,
                                fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            if (tab == 2) {
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Rounded.Info, "暂无可用数据", tint = chartInk,
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (detailDay != null) MentalMoodDayDetailSheet(store, detailDay!!,
        onDismiss = { detailDay = null }, onRecord = onRecord)
}

@Composable
private fun MoodInsightChart(
    entries: List<MoodEntry>,
    highlighted: List<MoodEntry>,
    range: MoodPeriod,
    selectedDay: Long?,
    onDaySelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = if (range.dayCount == 7L) listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        else if (range.dayCount <= 31L) (0..6).map { "${1 + it * 5}日" }
        else (0..6).map { "${it + 1}" }
    Row(modifier.padding(start = 18.dp, end = 12.dp)) {
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(200.dp)
                .pointerInput(range, highlighted) {
                    detectTapGestures { point ->
                        if (highlighted.isNotEmpty()) {
                            val fraction = (point.x / size.width).coerceIn(0f, 1f)
                            val hitDay = range.startDay +
                                ((range.dayCount - 1) * fraction).toLong()
                            val nearest = highlighted.minByOrNull { abs(it.epochDay - hitDay) }
                            if (nearest != null) onDaySelected(nearest.epochDay)
                        }
                    }
                }) {
                val cellWidth = size.width / 7f
                for (i in 0..7) {
                    val x = i * cellWidth
                    drawLine(Color(0xFFE4E4E8), Offset(x, 0f), Offset(x, size.height),
                        1f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 6f)))
                }
                for (i in 0..2) {
                    val y = i * size.height / 2f
                    drawLine(Color(0xFFE5E5E8), Offset(0f, y), Offset(size.width, y), 1f)
                }
                val activeIds = highlighted.map { it.id }.toSet()
                entries.groupBy { it.epochDay }.forEach { (day, dayEntries) ->
                    val fraction = ((day - range.startDay).toFloat() / range.dayCount)
                        .coerceIn(0f, .999f)
                    val x = (fraction + .5f / range.dayCount) * size.width
                    val top = dayEntries.minOf { 1f - it.value }.coerceIn(0f, 1f) * size.height
                    val bottom = dayEntries.maxOf { 1f - it.value }.coerceIn(0f, 1f) * size.height
                    if (dayEntries.size > 1 && bottom - top > 5.dp.toPx()) {
                        val sorted = dayEntries.sortedByDescending { it.value }
                        drawRoundRect(brush = Brush.verticalGradient(
                            sorted.map { if (it.id in activeIds) moodColor(it.value).copy(alpha = .30f)
                                else Color(0xFFDDDDDF) }, startY = top, endY = bottom),
                            topLeft = Offset(x - 7.dp.toPx(), top),
                            size = Size(14.dp.toPx(), bottom - top),
                            cornerRadius = CornerRadius(7.dp.toPx()))
                    }
                    dayEntries.forEach { entry ->
                        val y = (1f - entry.value.coerceIn(0f, 1f)) * size.height
                        val color = if (entry.id in activeIds) moodColor(entry.value)
                            else Color(0xFFC8C8CA)
                        if (day == selectedDay) drawCircle(color.copy(alpha = .19f),
                            12.dp.toPx(), Offset(x, y))
                        drawCircle(color, 4.5.dp.toPx(), Offset(x, y))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                labels.forEach { Text(it, color = Color(0xFFC2C2C5), fontSize = 11.sp) }
            }
        }
        Column(Modifier.width(67.dp).height(200.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("非常愉快", color = Color(0xFFC1C1C4), fontSize = 11.sp)
            Text("不悲不喜", color = Color(0xFFC1C1C4), fontSize = 11.sp)
            Text("非常不愉快", color = Color(0xFFC1C1C4), fontSize = 11.sp)
        }
    }
}

@Composable
internal fun MentalMoodCalendarSheet(
    store: AppStore,
    selectedEpochDay: Long,
    onSelect: (Long) -> Unit,
    onRecord: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    val currentMonth = YearMonth.from(today)
    val months = remember(currentMonth) { (-60..12).map { currentMonth.plusMonths(it.toLong()) } }
    val todayIndex = 60
    val state = rememberLazyListState(initialFirstVisibleItemIndex = todayIndex)
    val coroutineScope = rememberCoroutineScope()
    var detailDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var focusedDay by rememberSaveable { mutableStateOf(selectedEpochDay) }

    LiquidGlassBottomSheet(onDismiss, fullHeight = true, surfaceColor = Color.White,
        showNavigation = false, contentPadding = 0.dp) {
        Column(Modifier.fillMaxSize().background(Color.White)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 18.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                LiquidGlassIconButton(Icons.Rounded.Close, "关闭日历", onDismiss,
                    size = 48.dp, tint = chartInk)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("心理状态", color = chartInk, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Info, "日历中的图案代表每日心情，圆点代表瞬时情绪",
                        tint = Color(0xFFD8D8DA), modifier = Modifier.size(19.dp))
                }
                Box(Modifier.clip(RoundedCornerShape(30.dp)).border(1.dp,
                    Color(0xFFE9E9EB), RoundedCornerShape(30.dp))
                    .clickable {
                        focusedDay = today.toEpochDay()
                        onSelect(focusedDay)
                        coroutineScope.launch { state.animateScrollToItem(todayIndex) }
                    }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text("今天", color = chartInk, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(35.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach { label ->
                    Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                        color = chartMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(months) { month ->
                    Column {
                        Text("${month.year}年${month.monthValue}月",
                            Modifier.padding(start = 12.dp, top = 22.dp, bottom = 8.dp),
                            color = chartInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        val offset = month.atDay(1).dayOfWeek.value % 7
                        val weekCount = (offset + month.lengthOfMonth() + 6) / 7
                        repeat(weekCount) { week ->
                            Row(Modifier.fillMaxWidth().height(88.dp)
                                .background(Color.White)
                                .border(.5.dp, Color(0xFFEAEAEA))) {
                                repeat(7) { weekday ->
                                    val dayNumber = week * 7 + weekday - offset + 1
                                    if (dayNumber in 1..month.lengthOfMonth()) {
                                        val date = month.atDay(dayNumber)
                                        val epoch = date.toEpochDay()
                                        val entries = store.moodsOn(epoch)
                                        val daily = entries.firstOrNull { it.scope == MoodScope.DAY }
                                        val moments = entries.any { it.scope == MoodScope.MOMENT }
                                        Column(Modifier.weight(1f).fillMaxSize()
                                            .clickable(interactionSource = null, indication = null) {
                                                focusedDay = epoch
                                                onSelect(epoch)
                                                detailDay = epoch
                                            }, horizontalAlignment = Alignment.CenterHorizontally) {
                                            Box(Modifier.size(28.dp)
                                                .background(if (epoch == focusedDay) Color(0xFFB6B6B9)
                                                    else Color.Transparent, CircleShape),
                                                contentAlignment = Alignment.Center) {
                                                Text("$dayNumber", color = if (epoch == focusedDay) Color.White
                                                    else if (date.isAfter(today)) Color(0xFFC4C4C7)
                                                    else chartMuted, fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold)
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            if (daily != null) MoodCloud(daily.value,
                                                Modifier.size(40.dp), breathing = false)
                                            else Box(Modifier.size(38.dp).border(1.dp,
                                                Color(0xFFE8E9EC), CircleShape))
                                            if (moments) Box(Modifier.size(6.dp)
                                                .background(Color(0xFFB8B8BB), CircleShape))
                                        }
                                    } else Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (detailDay != null) MentalMoodDayDetailSheet(store, detailDay!!,
        onDismiss = { detailDay = null }, onRecord = onRecord)
}

@Composable
private fun MentalMoodDayDetailSheet(
    store: AppStore,
    day: Long,
    onDismiss: () -> Unit,
    onRecord: (Long) -> Unit,
) {
    val date = LocalDate.ofEpochDay(day)
    val entries = store.moodsOn(day)
    val daily = entries.firstOrNull { it.scope == MoodScope.DAY }
    val moments = entries.filter { it.scope == MoodScope.MOMENT }
    LiquidGlassBottomSheet(onDismiss, fullHeight = true, surfaceColor = Color.White,
        showNavigation = false, contentPadding = 0.dp) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color(0xFFE3EEE9), Color(0xFFF9FCFA), Color.White, Color.White)))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(48.dp))
                Text(dayLabel(day), Modifier.weight(1f), textAlign = TextAlign.Center,
                    color = chartInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                LiquidGlassIconButton(Icons.Rounded.Check, "关闭日期详情", onDismiss,
                    size = 48.dp, filled = true, tint = chartBlue)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 22.dp, end = 22.dp, top = 20.dp, bottom = 20.dp)) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("每日心情", color = chartMuted, fontSize = 15.sp)
                        Spacer(Modifier.height(18.dp))
                        if (daily != null) {
                            MoodCloud(daily.value, Modifier.size(145.dp), breathing = false)
                            Spacer(Modifier.height(14.dp))
                            Text(daily.words.joinToString("、").ifBlank { moodRangeLabel(daily.value) },
                                color = chartInk, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center)
                            Text(daily.factors.joinToString("、"), color = chartMuted,
                                fontSize = 16.sp)
                        } else {
                            Box(Modifier.size(145.dp), contentAlignment = Alignment.Center) {
                                Text("无条目", color = chartMuted, fontSize = 17.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(66.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEAEAEA)))
                    Spacer(Modifier.height(18.dp))
                    Text("瞬时情绪", color = chartMuted, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                }
                if (moments.isEmpty()) item {
                    Text("无条目", color = chartMuted, fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 22.dp))
                }
                items(moments) { entry ->
                    Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically) {
                        MoodCloud(entry.value, Modifier.size(48.dp), breathing = false)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.words.joinToString("、").ifBlank { moodRangeLabel(entry.value) },
                                color = chartInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(moodRangeLabel(entry.value), color = chartInk, fontSize = 15.sp)
                            Text(entry.factors.joinToString("、"), color = chartMuted, fontSize = 14.sp)
                        }
                        Text(entry.timeLabel.takeLast(5), color = chartMuted, fontSize = 13.sp)
                    }
                }
            }
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 20.dp)
                .width(130.dp).height(44.dp).clip(CircleShape)
                .background(Color(0xFFD5EBFF)).clickable { onRecord(day) },
                contentAlignment = Alignment.Center) {
                Text("记录", color = chartBlue, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun dayLabel(day: Long): String {
    val date = LocalDate.ofEpochDay(day)
    val weekday = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]
    return "${date.monthValue}月${date.dayOfMonth}日 $weekday"
}
