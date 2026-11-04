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
import androidx.compose.material.icons.rounded.Bloodtype
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
import com.pointerwander.luminawatch.data.ManualOxygenEntry
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

private val oxygenAccent = Color(0xFF21B8D6)
private val chartGray = Color(0xFFD8D8DD)
private val chartGrid = Color(0xFFE7E7EC)

private enum class OxygenRange(val label: String) {
    HOUR("小时"), DAY("日"), WEEK("周"), MONTH("月"), SIX_MONTH("6个月"), YEAR("年")
}

private data class OxygenPoint(
    val timestamp: Long,
    val label: String,
    val detail: String,
    val value: Float,
)

@Composable
fun OxygenScreen(store: AppStore, onBack: () -> Unit) {
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
    val range = OxygenRange.entries[rangeIndex]
    val entries = store.manualOxygenEntries.toList()
    val points = remember(rangeIndex, entries, store.canPreviewHealth) {
        oxygenPoints(range, entries, store.canPreviewHealth)
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
                    OxygenRangeSelector(rangeIndex) { rangeIndex = it }
                    Spacer(Modifier.height(20.dp))
                    if (selectedPoint != null) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val plotWidth = maxWidth - 38.dp
                            val cardWidth = 220.dp.coerceAtMost(maxWidth)
                            val fraction = (selected + .5f) / points.size
                            val lineX = plotWidth * fraction
                            val cardX = (lineX - cardWidth / 2)
                                .coerceIn(0.dp, (maxWidth - cardWidth).coerceAtLeast(0.dp))
                            OxygenSelectedCard(
                                selectedPoint,
                                Modifier.width(cardWidth).offset(x = cardX),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    } else {
                        OxygenRangeHeader(
                            min = minValue,
                            max = maxValue,
                            dateLabel = rangeDateLabel(range, points),
                            hasData = points.isNotEmpty(),
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    OxygenChart(
                        points = points,
                        selectedIndex = selected,
                        accent = oxygenAccent,
                        onSelected = { selected = it },
                    )
                    Spacer(Modifier.height(20.dp))
                    if (latest != null) OxygenLatestPill(latest, selected == points.lastIndex) {
                        selected = points.lastIndex
                        scrollScope.launch { listState.animateScrollToItem(1) }
                    }
                    Text(
                        "显示更多血氧数据",
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
                    Text("关于血氧", color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(24.dp))
                            .padding(22.dp),
                    ) {
                        Text(
                            "血氧饱和度是血液中携带氧气的血红蛋白占全部血红蛋白的比例。它是了解呼吸与循环状态的一个参考指标。",
                            color = colors.textPrimary,
                            fontSize = 17.sp,
                            lineHeight = 27.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "你可以手动记录血氧，并按小时、日、周、月等时段查看变化。手表同步功能接入后，设备测量数据也会在这里显示。",
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
                OxygenEducationCard(
                    title = "了解血氧水平",
                    subtitle = "其含义及对健康的重要性。",
                ) { OxygenWavesIllustration() }
            }
            item {
                OxygenEducationCard(
                    title = "什么是睡眠呼吸暂停？",
                    subtitle = "呼吸紊乱如何影响睡眠。",
                ) { OxygenNightIllustration() }
            }
            item {
                OxygenEducationCard(
                    title = "如何测量血氧",
                    subtitle = "佩戴位置、温度和动作都会影响读数。",
                ) { OxygenMeasureIllustration() }
            }
            item { Spacer(Modifier.height(36.dp + LocalBottomBarInset.current)) }
        }
        LiquidGlassStickyHeader(
            title = "血氧",
            onBack = onBack,
            backdrop = backdrop,
            topInset = statusBarHeight,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LiquidGlassTopBarIconButton(Icons.Rounded.Add, "记录血氧", { showAdd = true }, backdrop)
        }
    }

    if (showAdd) {
        AddOxygenSheet(store, onDismiss = { showAdd = false })
    }
    if (showMore) {
        OxygenMoreSheet(store, rangeIndex, onDismiss = { showMore = false })
    }
}

@Composable
private fun HeartEducationCard(title: String, subtitle: String, imageRes: Int, cropY: Int) {
    val colors = LocalWatchColors.current
    val image = ImageBitmap.imageResource(imageRes)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(26.dp)).background(colors.surface),
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
}

@Composable
private fun OxygenRangeSelector(selected: Int, onSelected: (Int) -> Unit) {
    LiquidGlassSlidingSegmented(
        options = OxygenRange.entries.map { it.label },
        selectedIndex = selected,
        onSelected = onSelected,
    )
}

@Composable
private fun OxygenRangeHeader(min: Int, max: Int, dateLabel: String, hasData: Boolean) {
    val colors = LocalWatchColors.current
    Column {
        Text("范围", color = Color(0xFF86868B), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (hasData) "$min–$max" else "--", color = colors.textPrimary,
                fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text("%", color = Color(0xFF86868B), fontSize = 18.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        }
        Text(dateLabel, color = Color(0xFF86868B), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun OxygenSelectedCard(point: OxygenPoint, modifier: Modifier = Modifier) {
    val colors = LocalWatchColors.current
    Column(
        modifier.background(colors.surfaceMuted, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(point.value.roundToInt().toString(), color = colors.textPrimary,
                fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("%", color = Color(0xFF86868B), fontSize = 14.sp,
                modifier = Modifier.padding(start = 5.dp, bottom = 5.dp))
        }
        Text(point.detail, color = Color(0xFF86868B), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun OxygenLatestPill(
    point: OxygenPoint,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalWatchColors.current
    val contentColor = if (isSelected) Color.White else colors.textPrimary
    Row(
        Modifier.fillMaxWidth().height(56.dp)
            .background(if (isSelected) oxygenAccent else colors.surfaceMuted, CircleShape)
            .motionClickable(onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("最新：${point.label}", color = contentColor, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${point.value.roundToInt()} %", color = contentColor,
            fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OxygenChart(
    points: List<OxygenPoint>,
    selectedIndex: Int,
    accent: Color,
    chartHeight: androidx.compose.ui.unit.Dp = 300.dp,
    onSelected: (Int) -> Unit,
) {
    if (points.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(chartHeight), contentAlignment = Alignment.Center) {
            Text("暂无血氧数据", color = Color(0xFF86868B), fontSize = 15.sp)
        }
        return
    }
    val minY = 80f
    val maxY = 100f
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
                    for (i in 0..2) {
                        val y = size.height * i / 2f
                        drawLine(chartGrid, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = dash)
                        val x = size.width * i / 2f
                        drawLine(chartGrid, Offset(x, 0f), Offset(x, size.height), 1f, pathEffect = dash)
                    }
                    points.forEachIndexed { index, point ->
                        val x = size.width * (index + .5f) / points.size
                        val ratio = ((point.value - minY) / (maxY - minY)).coerceIn(0f, 1f)
                        val y = size.height * (1f - ratio)
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
                listOf("100", "90", "80").forEach { tick ->
                    Text(tick, color = Color(0xFFB0B0B6), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth().padding(end = 38.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            oxygenXLabels(points).forEach {
                Text(it, color = Color(0xFFB0B0B6), fontSize = 12.sp)
            }
        }
    }
}

private fun oxygenXLabels(points: List<OxygenPoint>): List<String> {
    if (points.isEmpty()) return emptyList()
    val indices = listOf(0, points.size / 3, points.size * 2 / 3, points.lastIndex)
        .distinct().sorted()
    return indices.map { points[it].label }
}

@Composable
private fun HeartSummaryCard(points: List<OxygenPoint>) {
    val colors = LocalWatchColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .background(colors.surface, RoundedCornerShape(24.dp)).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bloodtype, contentDescription = null, tint = oxygenAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(5.dp))
            Text("血氧", color = oxygenAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        if (points.isEmpty()) {
            Text("暂无血氧记录。点击右上角的＋添加一条记录。", color = colors.textSecondary, fontSize = 15.sp)
        } else {
            val minimum = points.minOf { it.value }.roundToInt()
            val maximum = points.maxOf { it.value }.roundToInt()
            val average = points.map { it.value }.average().roundToInt()
            Text("这一时段的血氧范围为 $minimum–$maximum%。",
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
            Text("单位：%", color = colors.textSecondary, fontSize = 12.sp)
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
private fun HeartRecordsCard(entries: List<ManualOxygenEntry>) {
    val colors = LocalWatchColors.current
    val formatter = remember { DateTimeFormatter.ofPattern("M月d日 HH:mm") }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .background(colors.surface, RoundedCornerShape(24.dp)).padding(20.dp),
    ) {
        Text("手动记录", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) {
            Text("暂无手动添加的血氧记录", color = colors.textSecondary, fontSize = 15.sp)
        } else {
            entries.sortedByDescending { it.timestamp }.take(8).forEach { entry ->
                val time = Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.systemDefault())
                Row(
                    Modifier.fillMaxWidth().height(44.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(time.format(formatter), color = colors.textSecondary, fontSize = 14.sp)
                    Text("${entry.percent} %", color = colors.textPrimary,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (entries.size > 8) Text("仅显示最近 8 条", color = colors.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OxygenMoreSheet(store: AppStore, initialRangeIndex: Int, onDismiss: () -> Unit) {
    val colors = LocalWatchColors.current
    var rangeIndex by rememberSaveable { mutableIntStateOf(initialRangeIndex) }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val entries = store.manualOxygenEntries.toList()
    val points = remember(rangeIndex, entries, store.canPreviewHealth) {
        oxygenPoints(OxygenRange.entries[rangeIndex], entries, store.canPreviewHealth)
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
                Box(Modifier.align(Alignment.CenterStart).padding(start = 20.dp)) {
                    OxygenCloseButton(onDismiss)
                }
                Text("血氧", Modifier.align(Alignment.Center), color = colors.textPrimary,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.fillMaxWidth().background(colors.surface)
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp)) {
                OxygenRangeSelector(rangeIndex) { rangeIndex = it }
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
                        OxygenSelectedCard(selectedPoint, Modifier.width(cardWidth).offset(x = cardX))
                    } else {
                        OxygenRangeHeader(
                            points.minOfOrNull { it.value }?.roundToInt() ?: 0,
                            points.maxOfOrNull { it.value }?.roundToInt() ?: 0,
                            rangeDateLabel(OxygenRange.entries[rangeIndex], points),
                            points.isNotEmpty(),
                        )
                    }
                }
                OxygenChart(points, selected, oxygenAccent, chartHeight) { selected = it }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).background(colors.groupedSurface), state = listState) {
                item { Spacer(Modifier.height(14.dp)) }
                item {
                    OxygenMoreStatRow(
                        label = "最新：${latest?.let { heartLatestDateLabel(it) } ?: "--"}",
                        value = latest?.value?.roundToInt()?.toString() ?: "--",
                        unit = "%",
                        onClick = if (latest == null) null else ({ selected = points.lastIndex }),
                    )
                }
                item {
                    val min = points.minOfOrNull { it.value }?.roundToInt()
                    val max = points.maxOfOrNull { it.value }?.roundToInt()
                    OxygenMoreStatRow("范围", if (min != null && max != null) "$min–$max" else "--", "%")
                }
                item { OxygenMoreStatRow("最低血氧", "--", "%") }
                item { OxygenMoreStatRow("平均血氧", "--", "%") }
                item { OxygenMoreStatRow("睡眠", "--", "%") }
                item { OxygenMoreStatRow("呼吸", "--", "%") }
                item { OxygenMoreStatRow("低血氧通知", "--") }
                item { OxygenMoreStatRow("正常范围", "--") }
                item { Spacer(Modifier.height(28.dp)) }
            }
          }
        }
    }
}

private fun heartLatestDateLabel(point: OxygenPoint): String {
    val date = Instant.ofEpochMilli(point.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
}

@Composable
private fun OxygenMoreStatRow(
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
private fun AddOxygenSheet(store: AppStore, onDismiss: () -> Unit) {
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
    val valid = bpm != null && bpm in 70..100 && timestamp <= System.currentTimeMillis()
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
                    LiquidGlassIconButton(Icons.Rounded.Check, "保存血氧", {
                        if (valid && store.addManualOxygen(timestamp, bpm!!)) {
                            keyboard?.hide()
                            onDismiss()
                        } else error = "请输入 70–100% 的血氧，并选择不晚于现在的时间"
                    }, enabled = valid, filled = true, tint = oxygenAccent, size = 48.dp)
                }
                Spacer(Modifier.height(42.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(82.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Bloodtype, null, tint = oxygenAccent, modifier = Modifier.size(46.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("血氧", color = colors.textPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
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
                    AddHeartRow("%") {
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

private fun oxygenPoints(range: OxygenRange, entries: List<ManualOxygenEntry>, preview: Boolean): List<OxygenPoint> {
    val today = LocalDate.now()
    val now = LocalDateTime.now()
    val zone = ZoneId.systemDefault()
    val base = if (preview) when (range) {
        OxygenRange.HOUR -> {
            val values = listOf(97, 98, 97, 99, 98, 97, 98, 99, 98, 97, 98, 99)
            values.mapIndexed { index, value ->
                val time = now.minusMinutes((values.lastIndex - index) * 5L)
                OxygenPoint(
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
        OxygenRange.DAY -> {
            val values = listOf(
                96, 95, 96, 97, 96, 95, 96, 97, 98, 97, 96, 97,
                98, 99, 98, 97, 96, 97, 98, 98, 97, 96, 97, 98,
            )
            values.take(now.hour + 1).mapIndexed { hour, value ->
                val time = today.atTime(hour, 0)
                OxygenPoint(
                    time.atZone(zone).toInstant().toEpochMilli(),
                    "${hour}时",
                    "${time.year}年${time.monthValue}月${time.dayOfMonth}日 " +
                        "%02d:00–%02d:00".format(hour, (hour + 1).coerceAtMost(24)),
                    value.toFloat(),
                )
            }
        }
        OxygenRange.WEEK -> {
            val values = listOf(97, 98, 97, 99, 98, 97, 98)
            val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            values.mapIndexed { index, value ->
                val date = today.minusDays((6 - index).toLong())
                OxygenPoint(
                    date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                    labels[index],
                    "${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                    value.toFloat(),
                )
            }
        }
        OxygenRange.MONTH -> {
            val values = listOf(
                96, 97, 96, 98, 97, 98, 96, 95, 97, 98,
                99, 97, 98, 96, 98, 97, 96, 97, 98, 99,
                98, 97, 98, 97, 96, 98, 99, 98, 97, 98,
            )
            values.mapIndexed { index, value ->
                val date = today.minusDays((values.lastIndex - index).toLong())
                OxygenPoint(
                    date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                    "${date.dayOfMonth}日",
                    "${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                    value.toFloat(),
                )
            }
        }
        OxygenRange.SIX_MONTH -> {
            val values = listOf(
                97, 98, 97, 98, 99, 98, 97, 98, 97, 98, 99, 98, 97,
                98, 97, 96, 97, 98, 99, 98, 97, 98, 97, 96, 97, 98,
            )
            values.mapIndexed { index, value ->
                val date = today.minusWeeks((values.lastIndex - index).toLong())
                OxygenPoint(
                    date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                    "${date.monthValue}月",
                    "${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                    value.toFloat(),
                )
            }
        }
        OxygenRange.YEAR -> {
            val values = listOf(97, 98, 97, 98, 97, 99, 98, 97, 98, 97, 98, 99)
            values.take(today.monthValue).mapIndexed { index, value ->
                val date = today.withDayOfYear(1).plusMonths(index.toLong())
                OxygenPoint(
                    date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                    "${date.monthValue}月",
                    "${date.year}年${date.monthValue}月",
                    value.toFloat(),
                )
            }
        }
    } else emptyList()
    val start = when (range) {
        OxygenRange.HOUR -> now.minusHours(1).atZone(zone).toInstant().toEpochMilli()
        OxygenRange.DAY -> today.atStartOfDay(zone).toInstant().toEpochMilli()
        OxygenRange.WEEK -> today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        OxygenRange.MONTH -> today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
        OxygenRange.SIX_MONTH -> today.minusMonths(6).atStartOfDay(zone).toInstant().toEpochMilli()
        OxygenRange.YEAR -> today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val manual = entries
        .filter { it.timestamp in start..System.currentTimeMillis() }
        .map { entry ->
            val time = java.time.Instant.ofEpochMilli(entry.timestamp)
                .atZone(zone).toLocalDateTime()
            OxygenPoint(
                entry.timestamp,
                "%02d:%02d".format(time.hour, time.minute),
                "${time.monthValue}月${time.dayOfMonth}日 ${"%02d".format(time.hour)}:%02d".format(time.minute),
                entry.percent.toFloat(),
            )
        }
    return (base + manual).sortedBy { it.timestamp }
}

private fun point(timestamp: Long, hour: Int, minute: Int, index: Int, value: Int): OxygenPoint =
    OxygenPoint(timestamp, "%02d:%02d".format(hour, minute), "今天 %02d:%02d".format(hour, minute), value.toFloat())

private fun rangeDateLabel(range: OxygenRange, points: List<OxygenPoint>): String {
    val last = points.lastOrNull() ?: return when (range) {
        OxygenRange.HOUR -> "最近 1 小时"
        OxygenRange.DAY -> "今天"
        OxygenRange.WEEK -> "近 7 天"
        OxygenRange.MONTH -> "近 30 天"
        OxygenRange.SIX_MONTH -> "近 6 个月"
        OxygenRange.YEAR -> "今年"
    }
    val date = java.time.Instant.ofEpochMilli(last.timestamp)
        .atZone(ZoneId.systemDefault()).toLocalDate()
    return when (range) {
        OxygenRange.HOUR -> "今天 ${points.firstOrNull()?.label ?: ""}–${last.label}"
        OxygenRange.DAY -> "今天"
        OxygenRange.WEEK -> {
            val start = date.minusDays(6)
            "${start.year}年${start.monthValue}月${start.dayOfMonth}日–${date.monthValue}月${date.dayOfMonth}日"
        }
        OxygenRange.MONTH -> "${date.year}年${date.monthValue}月"
        OxygenRange.SIX_MONTH -> "近 6 个月"
        OxygenRange.YEAR -> "${date.year}年"
    }
}
@Composable
private fun OxygenCloseButton(onClick: () -> Unit) {
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
@Composable
private fun OxygenEducationCard(
    title: String,
    subtitle: String,
    illustration: @Composable () -> Unit,
) {
    val colors = LocalWatchColors.current
    var showDetail by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp)).background(colors.surface)
            .motionClickable(onClick = { showDetail = true }),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.67f)) { illustration() }
        Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = colors.textSecondary, fontSize = 16.sp)
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
private fun OxygenWavesIllustration() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFFF5B183), Color(0xFFF26B4A), Color(0xFF3F9BE0), Color(0xFFBFE7F5)),
            ),
        )
        drawCircle(Color(0xFF2F7FD0).copy(alpha = .55f), size.width * .75f,
            center = Offset(size.width * .10f, size.height * .95f))
        drawCircle(Color(0xFF97DDF0).copy(alpha = .85f), size.width * .52f,
            center = Offset(size.width * .05f, size.height * .66f))
        drawCircle(Color(0xFFF3A07A).copy(alpha = .55f), size.width * .50f,
            center = Offset(size.width * .80f, size.height * .12f))
        drawCircle(Color(0xFF6EC2E8).copy(alpha = .45f), size.width * .42f,
            center = Offset(size.width * .90f, size.height * .78f))
    }
}

@Composable
private fun OxygenNightIllustration() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(listOf(Color(0xFF071A38), Color(0xFF123B6B), Color(0xFF315B9B))),
        )
        drawCircle(Color(0xFFF3D3AE), size.width * .18f,
            center = Offset(size.width * .52f, size.height * .42f))
        drawCircle(Color(0xFFE3B98B), size.width * .04f,
            center = Offset(size.width * .47f, size.height * .38f))
        drawCircle(Color(0xFF071A38).copy(alpha = .25f), size.width * .30f,
            center = Offset(size.width * .53f, size.height * .87f))
        drawCircle(Color(0xFFF0D0A7), size.width * .13f,
            center = Offset(size.width * .16f, size.height * .68f))
        drawCircle(Color(0xFFF0D0A7), size.width * .12f,
            center = Offset(size.width * .86f, size.height * .70f))
        drawCircle(Color(0xFFFF8A2A), size.width * .055f,
            center = Offset(size.width * .58f, size.height * .16f))
        listOf(
            Offset(.12f, .20f), Offset(.25f, .10f), Offset(.40f, .16f),
            Offset(.70f, .12f), Offset(.82f, .28f), Offset(.66f, .34f),
            Offset(.20f, .40f), Offset(.78f, .52f), Offset(.30f, .58f),
        ).forEach { (x, y) ->
            drawCircle(Color.White.copy(alpha = .75f), 1.8.dp.toPx(),
                center = Offset(size.width * x, size.height * y))
        }
    }
}

@Composable
private fun OxygenMeasureIllustration() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.linearGradient(
                listOf(Color(0xFF6ED6E8), Color(0xFF21B8D6), Color(0xFF0A84FF)),
            ),
        )
        drawCircle(Color.White.copy(alpha = .18f), size.width * .62f,
            center = Offset(size.width * .15f, size.height * .80f))
        drawCircle(Color.White.copy(alpha = .22f), size.width * .42f,
            center = Offset(size.width * .86f, size.height * .25f))
        drawCircle(Color(0xFF0B5FA8).copy(alpha = .35f), size.width * .35f,
            center = Offset(size.width * .55f, size.height * .60f))
        drawCircle(Color.White.copy(alpha = .75f), size.width * .12f,
            center = Offset(size.width * .58f, size.height * .55f))
    }
}
