package com.pointerwander.luminawatch.feature.health

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import com.pointerwander.luminawatch.ui.motionClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.R
import kotlinx.coroutines.launch
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.ManualHeartEntry
import com.pointerwander.luminawatch.ui.LiquidGlassBottomSheet
import com.pointerwander.luminawatch.ui.LiquidGlassIconButton
import com.pointerwander.luminawatch.ui.LiquidGlassSurface
import com.pointerwander.luminawatch.ui.LiquidGlassSlidingSegmented
import com.pointerwander.luminawatch.ui.LiquidGlassStickyHeader
import com.pointerwander.luminawatch.ui.LiquidGlassTopBarIconButton
import com.pointerwander.luminawatch.ui.LocalBottomBarInset
import com.pointerwander.luminawatch.ui.LocalWatchColors
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.roundToInt

private val heartRed = Color(0xFFF43E67)
private val chartGray = Color(0xFFD8D8DD)
private val chartGrid = Color(0xFFE7E7EC)

private enum class HeartRange(val label: String) {
    HOUR("小时"), DAY("日"), WEEK("周"), MONTH("月"), SIX_MONTH("6个月"), YEAR("年")
}

private data class HeartPoint(
    val timestamp: Long,
    val label: String,
    val detail: String,
    val value: Float,
)

@Composable
fun HeartScreen(store: AppStore, onBack: () -> Unit) {
    val colors = LocalWatchColors.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val backdrop = rememberLayerBackdrop()
    var rangeIndex by rememberSaveable { mutableIntStateOf(1) }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var showAdd by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollScope = androidx.compose.runtime.rememberCoroutineScope()
    val range = HeartRange.entries[rangeIndex]
    val entries = store.manualHeartEntries.toList()
    val points = remember(rangeIndex, entries, store.canPreviewHealth) {
        heartPoints(range, entries, store.canPreviewHealth)
    }
    val minValue = points.minOfOrNull { it.value }?.roundToInt() ?: 0
    val maxValue = points.maxOfOrNull { it.value }?.roundToInt() ?: 0
    val latest = points.lastOrNull()
    val selectedPoint = points.getOrNull(selected)

    LaunchedEffect(rangeIndex) { selected = -1 }

    Box(Modifier.fillMaxSize().background(colors.groupedSurface)) {
        LazyColumn(
            Modifier.fillMaxSize().layerBackdrop(backdrop),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item { Spacer(Modifier.fillMaxWidth().height(84.dp + statusBarHeight).background(colors.surface)) }
            item {
                Column(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(14.dp))
                    HeartRangeSelector(rangeIndex) { rangeIndex = it }
                    Spacer(Modifier.height(20.dp))
                    if (selectedPoint != null) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val plotWidth = maxWidth - 38.dp
                            val cardWidth = 220.dp.coerceAtMost(maxWidth)
                            val fraction = (selected + .5f) / points.size
                            val lineX = plotWidth * fraction
                            val cardX = (lineX - cardWidth / 2)
                                .coerceIn(0.dp, (maxWidth - cardWidth).coerceAtLeast(0.dp))
                            HeartSelectedCard(
                                selectedPoint,
                                Modifier.width(cardWidth).offset(x = cardX),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    } else {
                        HeartRangeHeader(
                            min = minValue,
                            max = maxValue,
                            dateLabel = rangeDateLabel(range, points),
                            hasData = points.isNotEmpty(),
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    HeartChart(
                        points = points,
                        selectedIndex = selected,
                        accent = heartRed,
                        onSelected = { selected = it },
                    )
                    Spacer(Modifier.height(20.dp))
                    if (latest != null) HeartLatestPill(latest, selected == points.lastIndex) {
                        selected = points.lastIndex
                        scrollScope.launch { listState.animateScrollToItem(1) }
                    }
                    Text(
                        "显示更多心率数据",
                        Modifier.fillMaxWidth().height(56.dp)
                            .motionClickable(onClick = { showMore = true }).padding(top = 15.dp),
                        color = colors.accent,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("提要", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                    Text(
                        if (showAll) "收起" else "全部显示",
                        Modifier.motionClickable(onClick = { showAll = !showAll }),
                        color = colors.accent,
                        fontSize = 15.sp,
                    )
                }
            }
            item { HeartSummaryCard(points) }
            if (showAll) item { HeartRecordsCard(entries) }
            item {
                val uriHandler = LocalUriHandler.current
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("关于心率", color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(24.dp))
                            .padding(22.dp),
                    ) {
                        Text(
                            "你的心脏一天跳动大约 100,000 次，在运动和休息期间会相应加快和放慢。心率是指心脏每分钟跳动的次数，可被视为心血管健康的指标。",
                            color = colors.textPrimary,
                            fontSize = 17.sp,
                            lineHeight = 27.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "你可以手动记录心率，并按小时、日、周、月等时段查看变化。手表同步功能接入后，设备测量数据也会在这里显示。",
                            color = colors.textPrimary,
                            fontSize = 17.sp,
                            lineHeight = 27.sp,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Mayo Clinic",
                            modifier = Modifier.clickable {
                                uriHandler.openUri("https://www.mayoclinic.org/healthy-lifestyle/fitness/expert-answers/heart-rate/faq-20057979")
                            },
                            color = Color(0xFF1686D9),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            item {
                HeartEducationCard(
                    title = "了解你的心率",
                    subtitle = "了解高心率或低心率的含义。",
                    imageRes = R.drawable.heart_education_reference_a,
                    cropY = 360,
                )
            }
            item {
                HeartEducationCard(
                    title = "了解有氧适能",
                    subtitle = "测量方式及其重要性，以及如何改善。",
                    imageRes = R.drawable.heart_education_reference_a,
                    cropY = 1305,
                )
            }
            item {
                HeartEducationCard(
                    title = "有氧适能水平较低的含义",
                    subtitle = "你可以做些什么。",
                    imageRes = R.drawable.heart_education_reference_b,
                    cropY = 1429,
                )
            }
            item { Spacer(Modifier.height(36.dp + LocalBottomBarInset.current)) }
        }
        LiquidGlassStickyHeader(
            title = "心率",
            onBack = onBack,
            backdrop = backdrop,
            topInset = statusBarHeight,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LiquidGlassTopBarIconButton(Icons.Rounded.Add, "记录心率", { showAdd = true }, backdrop)
        }
    }

    if (showAdd) {
        AddHeartSheet(store, onDismiss = { showAdd = false })
    }
    if (showMore) {
        HeartMoreSheet(store, rangeIndex, onDismiss = { showMore = false })
    }
}

@Composable
private fun HeartEducationCard(title: String, subtitle: String, imageRes: Int, cropY: Int) {
    val colors = LocalWatchColors.current
    val image = ImageBitmap.imageResource(imageRes)
    var showDetail by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(26.dp)).background(colors.surface)
            .motionClickable(onClick = { showDetail = true }),
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.67f)) {
            drawImage(
                image = image,
                srcOffset = IntOffset(49, cropY),
                srcSize = IntSize(1108, 667),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.High,
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, lineHeight = 28.sp)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = colors.textPrimary, fontSize = 16.sp,
                lineHeight = 23.sp)
        }
    }
    if (showDetail) {
        LiquidGlassBottomSheet(onDismiss = { showDetail = false },
            surfaceColor = colors.surface) {
            Text(title, color = colors.textPrimary, fontSize = 23.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(subtitle, color = colors.textSecondary, fontSize = 17.sp,
                lineHeight = 25.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HeartRangeSelector(selected: Int, onSelected: (Int) -> Unit) {
    LiquidGlassSlidingSegmented(
        options = HeartRange.entries.map { it.label },
        selectedIndex = selected,
        onSelected = onSelected,
    )
}

@Composable
private fun HeartRangeHeader(min: Int, max: Int, dateLabel: String, hasData: Boolean) {
    val colors = LocalWatchColors.current
    Column {
        Text("范围", color = Color(0xFF86868B), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (hasData) "$min–$max" else "--", color = colors.textPrimary,
                fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text("次/分", color = Color(0xFF86868B), fontSize = 18.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        }
        Text(dateLabel, color = Color(0xFF86868B), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HeartSelectedCard(point: HeartPoint, modifier: Modifier = Modifier) {
    val colors = LocalWatchColors.current
    Column(
        modifier.background(colors.surfaceMuted, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(point.value.roundToInt().toString(), color = colors.textPrimary,
                fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("次/分", color = Color(0xFF86868B), fontSize = 14.sp,
                modifier = Modifier.padding(start = 5.dp, bottom = 5.dp))
        }
        Text(point.detail, color = Color(0xFF86868B), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HeartLatestPill(
    point: HeartPoint,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalWatchColors.current
    val contentColor = if (isSelected) Color.White else colors.textPrimary
    Row(
        Modifier.fillMaxWidth().height(56.dp)
            .background(if (isSelected) heartRed else colors.surfaceMuted, CircleShape)
            .motionClickable(onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("最新：${point.label}", color = contentColor, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${point.value.roundToInt()} 次/分", color = contentColor,
            fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeartChart(
    points: List<HeartPoint>,
    selectedIndex: Int,
    accent: Color,
    chartHeight: androidx.compose.ui.unit.Dp = 300.dp,
    onSelected: (Int) -> Unit,
) {
    if (points.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(chartHeight), contentAlignment = Alignment.Center) {
            Text("暂无心率数据", color = Color(0xFF86868B), fontSize = 15.sp)
        }
        return
    }
    val maxY = maxOf(150f, ceil(points.maxOf { it.value } / 50f) * 50f)
    Column {
        Row(Modifier.fillMaxWidth().height(chartHeight)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Canvas(
                    Modifier.fillMaxSize().pointerInput(points) {
                        detectTapGestures { offset ->
                            val index = ((offset.x / size.width) * points.size)
                                .toInt().coerceIn(points.indices)
                            onSelected(index)
                        }
                    },
                ) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    for (i in 0..3) {
                        val y = size.height * i / 3f
                        drawLine(chartGrid, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = dash)
                        val x = size.width * i / 3f
                        drawLine(chartGrid, Offset(x, 0f), Offset(x, size.height), 1f, pathEffect = dash)
                    }
                    points.forEachIndexed { index, point ->
                        val x = size.width * (index + .5f) / points.size
                        val y = size.height * (1f - (point.value / maxY).coerceIn(0f, 1f))
                        val highlighted = index == selectedIndex ||
                            (selectedIndex !in points.indices && index == points.lastIndex)
                        if (highlighted) {
                            if (index == selectedIndex) {
                                drawLine(chartGray, Offset(x, 0f), Offset(x, size.height), 1.5f)
                            }
                            drawCircle(accent.copy(alpha = .18f), radius = 13.dp.toPx(), center = Offset(x, y))
                            drawCircle(accent, radius = 7.dp.toPx(), center = Offset(x, y))
                        } else {
                            drawCircle(chartGray, radius = 5.dp.toPx(), center = Offset(x, y))
                        }
                    }
                }
            }
            Column(
                Modifier.width(38.dp).fillMaxHeight().padding(vertical = 2.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                (3 downTo 0).forEach { tick ->
                    Text((maxY * tick / 3f).roundToInt().toString(),
                        color = Color(0xFFB0B0B6), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth().padding(end = 38.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            chartXLabels(points).forEach {
                Text(it, color = Color(0xFFB0B0B6), fontSize = 12.sp)
            }
        }
    }
}

private fun chartXLabels(points: List<HeartPoint>): List<String> {
    if (points.isEmpty()) return emptyList()
    val indices = listOf(0, points.size / 3, points.size * 2 / 3, points.lastIndex)
        .distinct().sorted()
    return indices.map { points[it].label }
}

@Composable
private fun HeartSummaryCard(points: List<HeartPoint>) {
    val colors = LocalWatchColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .background(colors.surface, RoundedCornerShape(24.dp)).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Favorite, contentDescription = null, tint = heartRed, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(5.dp))
            Text("心率", color = heartRed, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        if (points.isEmpty()) {
            Text("暂无心率记录。点击右上角的＋添加一条记录。", color = colors.textSecondary, fontSize = 15.sp)
        } else {
            val minimum = points.minOf { it.value }.roundToInt()
            val maximum = points.maxOf { it.value }.roundToInt()
            val average = points.map { it.value }.average().roundToInt()
            Text("这一时段的心率范围为 $minimum–$maximum 次/分。",
                color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(chartGrid))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HeartSummaryValue("最低", "$minimum")
                HeartSummaryValue("平均", "$average")
                HeartSummaryValue("最高", "$maximum")
            }
            Spacer(Modifier.height(8.dp))
            Text("单位：次/分", color = colors.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HeartSummaryValue(label: String, value: String) {
    val colors = LocalWatchColors.current
    Column {
        Text(label, color = colors.textSecondary, fontSize = 13.sp)
        Text(value, color = colors.textPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeartRecordsCard(entries: List<ManualHeartEntry>) {
    val colors = LocalWatchColors.current
    val formatter = remember { DateTimeFormatter.ofPattern("M月d日 HH:mm") }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .background(colors.surface, RoundedCornerShape(24.dp)).padding(20.dp),
    ) {
        Text("手动记录", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) {
            Text("暂无手动添加的心率记录", color = colors.textSecondary, fontSize = 15.sp)
        } else {
            entries.sortedByDescending { it.timestamp }.take(8).forEach { entry ->
                val time = Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.systemDefault())
                Row(
                    Modifier.fillMaxWidth().height(44.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(time.format(formatter), color = colors.textSecondary, fontSize = 14.sp)
                    Text("${entry.bpm} 次/分", color = colors.textPrimary,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (entries.size > 8) Text("仅显示最近 8 条", color = colors.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HeartMoreSheet(store: AppStore, initialRangeIndex: Int, onDismiss: () -> Unit) {
    val colors = LocalWatchColors.current
    var rangeIndex by rememberSaveable { mutableIntStateOf(initialRangeIndex) }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val entries = store.manualHeartEntries.toList()
    val points = remember(rangeIndex, entries, store.canPreviewHealth) {
        heartPoints(HeartRange.entries[rangeIndex], entries, store.canPreviewHealth)
    }
    val latest = points.lastOrNull()
    val selectedPoint = points.getOrNull(selected)
    val listState = rememberLazyListState()
    LaunchedEffect(rangeIndex) { selected = -1 }

    LiquidGlassBottomSheet(
        onDismiss = onDismiss,
        fullHeight = true,
        surfaceColor = colors.groupedSurface,
        contentPadding = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
          val chartHeight = (maxHeight - 550.dp).coerceIn(180.dp, 300.dp)
          Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(78.dp).background(colors.surface)) {
                LiquidGlassIconButton(
                    Icons.Rounded.Close, "关闭更多心率数据", onDismiss,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp), size = 48.dp,
                )
                Text("心率", Modifier.align(Alignment.Center), color = colors.textPrimary,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.fillMaxWidth().background(colors.surface)
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp)) {
                HeartRangeSelector(rangeIndex) { rangeIndex = it }
                Spacer(Modifier.height(20.dp))
                BoxWithConstraints(Modifier.fillMaxWidth().height(104.dp)) {
                    if (selectedPoint != null) {
                        val plotWidth = maxWidth - 38.dp
                        val cardWidth = 220.dp.coerceAtMost(maxWidth)
                        val lineX = plotWidth * ((selected + .5f) / points.size)
                        val cardX = (lineX - cardWidth / 2)
                            .coerceIn(0.dp, (maxWidth - cardWidth).coerceAtLeast(0.dp))
                        Box(Modifier.offset(x = lineX).width(1.dp).fillMaxHeight()
                            .background(chartGray))
                        HeartSelectedCard(selectedPoint, Modifier.width(cardWidth).offset(x = cardX))
                    } else {
                        HeartRangeHeader(
                            points.minOfOrNull { it.value }?.roundToInt() ?: 0,
                            points.maxOfOrNull { it.value }?.roundToInt() ?: 0,
                            rangeDateLabel(HeartRange.entries[rangeIndex], points),
                            points.isNotEmpty(),
                        )
                    }
                }
                HeartChart(points, selected, heartRed, chartHeight) { selected = it }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).background(colors.groupedSurface), state = listState) {
                item { Spacer(Modifier.height(14.dp)) }
                item {
                    HeartMoreStatRow(
                        label = "最新：${latest?.let { heartLatestDateLabel(it) } ?: "--"}",
                        value = latest?.value?.roundToInt()?.toString() ?: "--",
                        unit = "次/分",
                        onClick = if (latest == null) null else ({ selected = points.lastIndex }),
                    )
                }
                item {
                    val min = points.minOfOrNull { it.value }?.roundToInt()
                    val max = points.maxOfOrNull { it.value }?.roundToInt()
                    HeartMoreStatRow("范围", if (min != null && max != null) "$min–$max" else "--", "次/分")
                }
                item { HeartMoreStatRow("静息心率", "--", "次/分") }
                item { HeartMoreStatRow("步行平均心率", "--", "次/分") }
                item { HeartMoreStatRow("睡眠", "--", "次/分") }
                item { HeartMoreStatRow("呼吸", "--", "次/分") }
                item { HeartMoreStatRow("高心率通知", "--") }
                item { HeartMoreStatRow("低心率通知", "--") }
                item { Spacer(Modifier.height(28.dp)) }
            }
          }
        }
    }
}

private fun heartLatestDateLabel(point: HeartPoint): String {
    val date = Instant.ofEpochMilli(point.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
}

@Composable
private fun HeartMoreStatRow(
    label: String,
    value: String,
    unit: String = "",
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalWatchColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .height(68.dp).background(colors.surface, RoundedCornerShape(24.dp))
            .then(if (onClick != null) Modifier.motionClickable(onClick) else Modifier)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = if (value == "--") colors.textSecondary else colors.textPrimary,
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) Text(" $unit", color = colors.textSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun AddHeartSheet(store: AppStore, onDismiss: () -> Unit) {
    val colors = LocalWatchColors.current
    val now = remember { LocalDateTime.now() }
    var date by remember { mutableStateOf(now.toLocalDate()) }
    var hour by remember { mutableIntStateOf(now.hour) }
    var minute by remember { mutableIntStateOf(now.minute) }
    var bpmText by remember { mutableStateOf("") }
    var activePopup by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val bpm = bpmText.toIntOrNull()
    val timestamp = date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val valid = bpm != null && bpm in 30..220 && timestamp <= System.currentTimeMillis()
    val pickerBackdrop = rememberLayerBackdrop()
    var sheetTop by remember { mutableIntStateOf(0) }
    var dateBottom by remember { mutableIntStateOf(0) }
    var timeBottom by remember { mutableIntStateOf(0) }
    val popupGap = with(LocalDensity.current) { 8.dp.roundToPx() }

    LiquidGlassBottomSheet(onDismiss, fullHeight = true, surfaceColor = colors.groupedSurface) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { sheetTop = it.positionInRoot().y.toInt() }) {
            Column(Modifier.fillMaxSize().layerBackdrop(pickerBackdrop).padding(horizontal = 4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LiquidGlassIconButton(Icons.Rounded.Close, "取消添加", onDismiss, size = 48.dp)
                    LiquidGlassIconButton(Icons.Rounded.Check, "保存心率", {
                        if (valid && store.addManualHeart(timestamp, bpm!!)) {
                            keyboard?.hide()
                            onDismiss()
                        } else error = "请输入 30–220 次/分，并选择不晚于现在的时间"
                    }, enabled = valid, filled = true, tint = heartRed, size = 48.dp)
                }
                Spacer(Modifier.height(42.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(82.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Favorite, null, tint = heartRed, modifier = Modifier.size(46.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("心率", color = colors.textPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(40.dp))
                Column(
                    Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp),
                ) {
                    AddHeartRow("日期") {
                        LiquidGlassSurface({ focusManager.clearFocus(); keyboard?.hide(); activePopup = "date" },
                            modifier = Modifier.onGloballyPositioned {
                                dateBottom = it.positionInRoot().y.toInt() + it.size.height
                            }, height = 44.dp, glassAlpha = .88f, glassColor = Color(0xFFE5E5EA),
                            contentPadding = 12.dp) { _ ->
                            Text("${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                                color = if (activePopup == "date") Color(0xFF0787F7) else colors.textPrimary,
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    AddHeartDivider()
                    AddHeartRow("时间") {
                        LiquidGlassSurface({ focusManager.clearFocus(); keyboard?.hide(); activePopup = "time" },
                            modifier = Modifier.onGloballyPositioned {
                                timeBottom = it.positionInRoot().y.toInt() + it.size.height
                            }, height = 44.dp, glassAlpha = .88f, glassColor = Color(0xFFE5E5EA),
                            contentPadding = 12.dp) { _ ->
                            Text("%02d:%02d".format(hour, minute),
                                color = if (activePopup == "time") Color(0xFF0787F7) else colors.textPrimary,
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    AddHeartDivider()
                    AddHeartRow("次/分") {
                        BasicTextField(
                            value = bpmText,
                            onValueChange = { candidate ->
                                if (candidate.length <= 3 && candidate.all(Char::isDigit)) {
                                    bpmText = candidate
                                    error = null
                                }
                            },
                            modifier = Modifier.width(145.dp).height(48.dp).onFocusChanged {
                                if (it.isFocused) { activePopup = null; keyboard?.show() }
                            },
                            singleLine = true,
                            textStyle = TextStyle(color = colors.textPrimary, fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                    if (bpmText.isEmpty()) Text("点击输入", color = colors.textSecondary, fontSize = 15.sp)
                                    inner()
                                }
                            },
                        )
                    }
                }
                if (error != null) Text(error!!, Modifier.padding(top = 10.dp),
                    color = colors.danger, fontSize = 13.sp)
            }
            if (activePopup != null) {
                BackHandler { activePopup = null }
                Box(Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { activePopup = null })
                val pickerPosition = Modifier.offset {
                    IntOffset(0, ((if (activePopup == "date") dateBottom else timeBottom) - sheetTop + popupGap).coerceAtLeast(0))
                }
                Box((if (activePopup == "time") Modifier.align(Alignment.TopEnd).padding(end = 12.dp).width(230.dp)
                    else Modifier.fillMaxWidth().padding(horizontal = 12.dp)).then(pickerPosition)) {
                    when (activePopup) {
                        "date" -> HealthDatePicker(date, LocalDate.now(), pickerBackdrop,
                            onPreview = { date = it },
                            onDaySelected = { date = it; activePopup = null })
                        "time" -> HealthTimePicker(hour, minute, pickerBackdrop) { h, m ->
                            hour = h; minute = m
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddHeartRow(label: String, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(67.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(0xFF86868B), fontSize = 15.sp, modifier = Modifier.weight(1f))
        value()
    }
}

@Composable
private fun AddHeartDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE7E7EC)))
}

private fun heartPoints(range: HeartRange, entries: List<ManualHeartEntry>, preview: Boolean): List<HeartPoint> {
    val today = LocalDate.now()
    val now = LocalDateTime.now()
    val zone = ZoneId.systemDefault()
    val base = if (preview) when (range) {
        HeartRange.HOUR -> {
            val values = listOf(74, 76, 73, 80, 82, 79, 77, 81, 84, 80, 78, 80)
            values.mapIndexed { index, value ->
                val time = now.minusMinutes((values.lastIndex - index) * 5L)
                HeartPoint(
                    time.atZone(zone).toInstant().toEpochMilli(),
                    "%02d:%02d".format(time.hour, time.minute),
                    "${time.year}年${time.monthValue}月${time.dayOfMonth}日 " +
                        "%02d:%02d–%02d:%02d".format(
                            time.hour, time.minute,
                            time.plusMinutes(5).hour, time.plusMinutes(5).minute,
                        ),
                    value.toFloat(),
                )
            }
        }
        HeartRange.DAY -> {
            val values = listOf(
                64, 62, 60, 59, 58, 60, 66, 72, 78, 86, 92, 104,
                98, 88, 84, 82, 80, 79, 77, 76, 80, 78, 74, 72,
            )
            values.take(now.hour + 1).mapIndexed { hour, value ->
                val time = today.atTime(hour, 0)
                HeartPoint(
                    time.atZone(zone).toInstant().toEpochMilli(),
                    "${hour}时",
                    "${time.year}年${time.monthValue}月${time.dayOfMonth}日 " +
                        "%02d:00–%02d:00".format(hour, (hour + 1).coerceAtMost(24)),
                    value.toFloat(),
                )
            }
        }
        HeartRange.WEEK -> {
            val values = listOf(74, 76, 72, 81, 79, 75, 78)
            val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            values.mapIndexed { index, value ->
                val date = today.minusDays((6 - index).toLong())
                HeartPoint(
                    date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                    labels[index],
                    "${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                    value.toFloat(),
                )
            }
        }
        HeartRange.MONTH -> {
            val values = listOf(
                72, 74, 71, 76, 75, 78, 73, 70, 74, 77,
                79, 76, 75, 72, 80, 78, 74, 73, 77, 81,
                79, 75, 78, 76, 74, 80, 79, 77, 76, 78,
            )
            values.mapIndexed { index, value ->
                val date = today.minusDays((values.lastIndex - index).toLong())
                HeartPoint(
                    date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                    "${date.dayOfMonth}日",
                    "${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                    value.toFloat(),
                )
            }
        }
        HeartRange.SIX_MONTH -> {
            val values = listOf(
                72, 73, 75, 74, 76, 78, 77, 75, 74, 76, 79, 81, 80,
                78, 77, 76, 75, 74, 76, 78, 80, 79, 77, 76, 75, 78,
            )
            values.mapIndexed { index, value ->
                val date = today.minusWeeks((values.lastIndex - index).toLong())
                HeartPoint(
                    date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                    "${date.monthValue}月",
                    "${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                    value.toFloat(),
                )
            }
        }
        HeartRange.YEAR -> {
            val values = listOf(72, 74, 73, 76, 75, 78, 79, 77, 76, 74, 73, 75)
            values.take(today.monthValue).mapIndexed { index, value ->
                val date = today.withDayOfYear(1).plusMonths(index.toLong())
                HeartPoint(
                    date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                    "${date.monthValue}月",
                    "${date.year}年${date.monthValue}月",
                    value.toFloat(),
                )
            }
        }
    } else emptyList()
    val start = when (range) {
        HeartRange.HOUR -> now.minusHours(1).atZone(zone).toInstant().toEpochMilli()
        HeartRange.DAY -> today.atStartOfDay(zone).toInstant().toEpochMilli()
        HeartRange.WEEK -> today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        HeartRange.MONTH -> today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
        HeartRange.SIX_MONTH -> today.minusMonths(6).atStartOfDay(zone).toInstant().toEpochMilli()
        HeartRange.YEAR -> today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val manual = entries
        .filter { it.timestamp in start..System.currentTimeMillis() }
        .map { entry ->
            val time = java.time.Instant.ofEpochMilli(entry.timestamp)
                .atZone(zone).toLocalDateTime()
            HeartPoint(
                entry.timestamp,
                "%02d:%02d".format(time.hour, time.minute),
                "${time.monthValue}月${time.dayOfMonth}日 ${"%02d".format(time.hour)}:%02d".format(time.minute),
                entry.bpm.toFloat(),
            )
        }
    return (base + manual).sortedBy { it.timestamp }
}

private fun point(timestamp: Long, hour: Int, minute: Int, index: Int, value: Int): HeartPoint =
    HeartPoint(timestamp, "%02d:%02d".format(hour, minute), "今天 %02d:%02d".format(hour, minute), value.toFloat())

private fun rangeDateLabel(range: HeartRange, points: List<HeartPoint>): String {
    val last = points.lastOrNull() ?: return when (range) {
        HeartRange.HOUR -> "最近 1 小时"
        HeartRange.DAY -> "今天"
        HeartRange.WEEK -> "近 7 天"
        HeartRange.MONTH -> "近 30 天"
        HeartRange.SIX_MONTH -> "近 6 个月"
        HeartRange.YEAR -> "今年"
    }
    val date = java.time.Instant.ofEpochMilli(last.timestamp)
        .atZone(ZoneId.systemDefault()).toLocalDate()
    return when (range) {
        HeartRange.HOUR -> "今天 ${points.firstOrNull()?.label ?: ""}–${last.label}"
        HeartRange.DAY -> "今天"
        HeartRange.WEEK -> {
            val start = date.minusDays(6)
            "${start.year}年${start.monthValue}月${start.dayOfMonth}日–${date.monthValue}月${date.dayOfMonth}日"
        }
        HeartRange.MONTH -> "${date.year}年${date.monthValue}月"
        HeartRange.SIX_MONTH -> "近 6 个月"
        HeartRange.YEAR -> "${date.year}年"
    }
}
@Composable
private fun HeartCloseButton(onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp)
            .shadow(16.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFFAFAFC), Color(0xFFE3E3E9), Color(0xFFD1D1D9)),
                ),
            )
            .border(
                1.6.dp,
                Color(0xFF8E8E93),
                CircleShape,
            )
            .motionClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 4.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(
                size.width - inset * 2,
                size.height - inset * 2,
            )
            drawArc(
                color = Color.White.copy(alpha = .9f),
                startAngle = 195f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx()),
            )
            drawArc(
                color = Color(0xFF9F9FAA).copy(alpha = .28f),
                startAngle = 15f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx()),
            )
        }
        Icon(
            Icons.Rounded.Close,
            contentDescription = "关闭",
            tint = Color(0xFF1C1C1E),
            modifier = Modifier.size(22.dp),
        )
    }
}
