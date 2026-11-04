package com.pointerwander.luminawatch.feature.health

import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.pointerwander.luminawatch.ui.LiquidGlassStickyHeader
import com.pointerwander.luminawatch.ui.LiquidGlassTopBarIconButton
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.app.Screen
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.MoodEntry
import com.pointerwander.luminawatch.data.MoodScope
import com.pointerwander.luminawatch.R
import com.pointerwander.luminawatch.feature.mood.MoodCloud
import com.pointerwander.luminawatch.feature.mood.moodRangeLabel
import com.pointerwander.luminawatch.ui.LiquidGlassBottomSheet
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassIconButton
import com.pointerwander.luminawatch.ui.LiquidGlassSlidingSegmented
import com.pointerwander.luminawatch.ui.LiquidGlassSurface
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.LocalBottomBarInset
import com.pointerwander.luminawatch.ui.motionClickable
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.launch

private val mentalGreen = Color(0xFFBFE8D8)
private val mentalMint = Color(0xFFE6F6EF)
private val mentalInk = Color(0xFF1C1C1E)
private val mentalGray = Color(0xFF86868B)

@Composable
fun MentalHealthHomeScreen(store: AppStore, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    val colors = LocalWatchColors.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val latest = store.moods.maxByOrNull { it.id }
    var showAssessment by rememberSaveable { mutableStateOf(false) }
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val compactTitleThreshold = with(LocalDensity.current) { 124.dp.roundToPx() }
    val showCompactTitle by remember(scrollState, compactTitleThreshold) {
        derivedStateOf { scrollState.value >= compactTitleThreshold }
    }
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize().background(colors.groupedSurface)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState),
        ) {
            Box(
                Modifier.fillMaxWidth().height(192.dp + statusBarHeight).background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFB9E6D4), Color(0xFFE4F4EE), colors.groupedSurface),
                    ),
                ),
            ) {
                Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 82.dp + statusBarHeight)) {
                    Text("心理健康", color = mentalInk, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text("过去 7 天", color = mentalInk, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(10.dp))
                MentalMoodSummary(latest) { onOpen(Screen.MoodStatus(latest?.epochDay)) }
                if (store.assessments.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    val latestAnxiety = store.assessments.filter { it.gadScore != null }
                        .maxByOrNull { it.timestamp }
                    val latestDepression = store.assessments.filter { it.phqScore != null }
                        .maxByOrNull { it.timestamp }
                    latestAnxiety?.gadScore?.let { score ->
                        MentalRiskSummary("焦虑风险", assessmentLevel(score, false)) {
                            onOpen(Screen.MentalRiskDetail(false))
                        }
                    }
                    if (latestAnxiety != null && latestDepression != null) Spacer(Modifier.height(12.dp))
                    latestDepression?.phqScore?.let { score ->
                        MentalRiskSummary("抑郁风险", assessmentLevel(score, true)) {
                            onOpen(Screen.MentalRiskDetail(true))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("记录与回顾", color = mentalInk, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(colors.surface),
                ) {
                    MentalRecordRow("记录心情") { onOpen(Screen.MoodRecord()) }
                    MentalRecordDivider()
                    MentalRecordRow("心情月历") { showCalendar = true }
                }
                Spacer(Modifier.height(28.dp))
                Text("了解更多“健康”功能", color = mentalInk, fontSize = 19.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                MentalQuestionnaireCard { showAssessment = true }
                Spacer(Modifier.height(30.dp))
                Text("关于心理健康", color = mentalInk, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            MentalArticleCard("了解心理健康", "了解心理健康的影响因素及其重要性。",
                R.drawable.mental_education_reference_a, cropX = 8, cropY = 137)
            MentalArticleCard("心理健康的常见问题", "了解常见的心理健康状况及需要留意的问题。",
                R.drawable.mental_education_reference_a, cropX = 8, cropY = 482)
            MentalArticleCard("心理健康问卷调查", "关爱心理健康，它们至关重要。",
                R.drawable.mental_education_reference_b, cropX = 16, cropY = 137)
            MentalArticleCard("情绪和心情的差异", "了解情绪和心情对你的影响。",
                R.drawable.mental_education_reference_b, cropX = 16, cropY = 483)
            Spacer(Modifier.height(36.dp + LocalBottomBarInset.current + navigationBarHeight))
        }
        }
        AnimatedVisibility(showCompactTitle, Modifier.align(Alignment.TopCenter),
            enter = fadeIn(), exit = fadeOut()) {
            LiquidGlassStickyHeader(title = "心理健康", onBack = onBack,
                backdrop = backdrop, topInset = statusBarHeight)
        }
        AnimatedVisibility(!showCompactTitle,
            Modifier.align(Alignment.TopStart)
                .padding(start = 18.dp, top = statusBarHeight + 12.dp),
            enter = fadeIn(), exit = fadeOut()) {
            LiquidGlassTopBarIconButton(Icons.AutoMirrored.Rounded.ArrowBack,
                "返回", onBack, backdrop)
        }
    }
    if (showAssessment) MentalAssessmentSheet(store) { showAssessment = false }
    if (showCalendar) MentalMoodCalendarSheet(
        store = store,
        selectedEpochDay = LocalDate.now().toEpochDay(),
        onSelect = {},
        onRecord = { day ->
            showCalendar = false
            onOpen(Screen.MoodRecord(day))
        },
        onDismiss = { showCalendar = false },
    )
}

@Composable
private fun MentalRiskSummary(title: String, level: String, onClick: () -> Unit) {
    val colors = LocalWatchColors.current
    Column(Modifier.fillMaxWidth().motionClickable(onClick).clip(RoundedCornerShape(28.dp))
        .background(colors.surface).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFE3F7F2)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Psychology, null, tint = Color(0xFF2CB9AD),
                    modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color(0xFF2CB9AD), fontSize = 17.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, tint = mentalGray,
                modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(36.dp))
        Text(level, color = mentalInk, fontSize = 27.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MentalRecordRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).motionClickable(onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = mentalInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null,
            tint = Color(0xFFB8B8BD), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MentalRecordDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .height(1.dp).background(Color(0xFFE7E7EC)))
}

@Composable
private fun MentalQuestionnaireCard(onClick: () -> Unit) {
    val colors = LocalWatchColors.current
    val teal = Color(0xFF69D3D0)
    Row(
        Modifier.fillMaxWidth().motionClickable(onClick).clip(RoundedCornerShape(22.dp))
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(72.dp).height(86.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(58.dp).height(74.dp)
                .border(3.dp, teal, RoundedCornerShape(7.dp)))
            Box(Modifier.align(Alignment.TopCenter).width(28.dp).height(8.dp)
                .clip(RoundedCornerShape(4.dp)).background(teal))
            Icon(Icons.Rounded.Psychology, contentDescription = null,
                tint = Color(0xFF23BBB6), modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("心理健康问卷调查", color = mentalInk, fontSize = 16.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("除了规律反思，评估自己当前出现常见状况的风险也是关注心理健康的重要组成部分。",
                color = mentalInk, fontSize = 14.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.height(32.dp).clip(CircleShape)
                .background(Color(0xFF0A84FF)).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center) {
                Text("进行问卷调查", color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MentalMoodSummary(entry: MoodEntry?, onClick: () -> Unit) {
    val colors = LocalWatchColors.current
    Column(
        Modifier.fillMaxWidth().motionClickable(onClick).clip(RoundedCornerShape(28.dp))
            .background(colors.surface).padding(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFE3F7F2)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Psychology, null, tint = Color(0xFF2CB9AD), modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(8.dp))
            Text("心理状态", color = Color(0xFF2CB9AD), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(entry?.let { moodDateLabel(it.epochDay) } ?: "过去 7 天", color = mentalGray, fontSize = 14.sp)
            Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFB8B8BD),
                modifier = Modifier.padding(start = 2.dp).size(18.dp))
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry?.let { it.words.joinToString("、").ifBlank { moodRangeLabel(it.value) } } ?: "还没有记录",
                    color = mentalInk,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    entry?.factors?.joinToString("、")?.ifBlank { "本机记录" } ?: "点击记录此刻的感受",
                    color = mentalGray,
                    fontSize = 14.sp,
                )
            }
            MoodCloud(entry?.value ?: .55f, Modifier.size(76.dp), breathing = false)
        }
    }
}

private fun moodDateLabel(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val today = LocalDate.now()
    return when {
        date == today -> "今天"
        date == today.minusDays(1) -> "昨天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
}

@Composable
fun MoodStatusScreen(
    store: AppStore,
    initialEpochDay: Long?,
    onBack: () -> Unit,
    onOpen: (Screen) -> Unit,
) {
    val colors = LocalWatchColors.current
    var anchorEpochDay by rememberSaveable { mutableLongStateOf(initialEpochDay ?: LocalDate.now().toEpochDay()) }
    val pager = rememberPagerState(initialPage = 365, pageCount = { 731 })
    val epochDay = anchorEpochDay + pager.currentPage - 365L
    val coroutineScope = rememberCoroutineScope()
    var showChart by remember { mutableStateOf(false) }
    var showDateSheet by remember { mutableStateOf(false) }
    val date = LocalDate.ofEpochDay(epochDay)
    val backdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize().background(colors.groupedSurface)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 84.dp)) {
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${date.monthValue}月${date.dayOfMonth}日 ${if (epochDay == LocalDate.now().toEpochDay()) "今天" else listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]}",
                    color = mentalInk,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clickable { showDateSheet = true },
                )
                LiquidGlassButton("记录", { onOpen(Screen.MoodRecord(epochDay)) }, height = 42.dp,
                    tint = Color(0xFF0A84FF))
            }
            Spacer(Modifier.height(16.dp))
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().height(520.dp),
                contentPadding = PaddingValues(horizontal = 18.dp),
                pageSpacing = 10.dp,
            ) { page ->
                val dayEntries = store.moodsOn(anchorEpochDay + page - 365L)
                val daily = dayEntries.firstOrNull { it.scope == MoodScope.DAY }
                val moments = dayEntries.filter { it.scope == MoodScope.MOMENT }
                val pageOffset = abs(pager.currentPage - page + pager.currentPageOffsetFraction)
                    .coerceIn(0f, 1f)
                Column(
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            val scale = 1f - .08f * pageOffset
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - .12f * pageOffset
                        }
                        .clip(RoundedCornerShape(28.dp))
                        .background(Brush.verticalGradient(
                            0f to Color(0xFFE4F8F3),
                            .55f to Color(0xFFF9FDFC),
                            1f to Color.White,
                        ))
                        .padding(20.dp),
                ) {
                    if (daily == null && moments.isEmpty()) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Psychology, null, tint = Color(0xFFDCEBE7),
                                modifier = Modifier.size(82.dp))
                            Spacer(Modifier.height(18.dp))
                            Text("无条目", color = Color(0xFFB0B0B6), fontSize = 22.sp,
                                fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().height(222.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("每日心情", color = mentalGray, fontSize = 13.sp)
                            Spacer(Modifier.height(12.dp))
                            if (daily == null) {
                                Spacer(Modifier.height(86.dp))
                                Text("无条目", color = Color(0xFFB0B0B6), fontSize = 18.sp)
                            } else {
                                MoodCloud(daily.value, Modifier.size(108.dp), breathing = false)
                                Spacer(Modifier.height(10.dp))
                                Text(daily.words.joinToString("、").ifBlank { moodRangeLabel(daily.value) },
                                    color = mentalInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text(daily.factors.joinToString("、").ifBlank { "本机记录" },
                                    color = mentalGray, fontSize = 14.sp)
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE7E7EC)))
                        Spacer(Modifier.height(14.dp))
                        Text("瞬时情绪", color = mentalGray, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        if (moments.isEmpty()) {
                            Text("无条目", color = Color(0xFFB0B0B6), fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold)
                        } else {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                moments.forEach { entry ->
                                    Row(Modifier.fillMaxWidth().height(72.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        MoodCloud(entry.value, Modifier.size(44.dp), breathing = false)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(entry.words.joinToString("、").ifBlank { moodRangeLabel(entry.value) },
                                                color = mentalInk, fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold)
                                            Text(entry.factors.joinToString("、").ifBlank { "本机记录" },
                                                color = mentalGray, fontSize = 13.sp)
                                        }
                                        Text(entry.timeLabel.takeLast(5), color = mentalGray,
                                            fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(52.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0xFFE5E5EA))
                    .clickable { showChart = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("在图表中显示", color = Color(0xFF0A84FF),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(28.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("关于心理状态", color = mentalInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).padding(22.dp)) {
                    Text(
                        "记录心理状态，能帮助你留意情绪变化，并思考影响心理状态的因素。记录时尽量选择最接近此刻感受的词。",
                        color = Colors.text,
                        fontSize = 16.sp,
                        lineHeight = 25.sp,
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
        }
        }
        LiquidGlassStickyHeader(
            title = "心理状态",
            onBack = onBack,
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LiquidGlassTopBarIconButton(
                icon = Icons.Rounded.CalendarMonth,
                label = "打开日历",
                onClick = { showDateSheet = true },
                backdrop = backdrop,
            )
        }
    }
    if (showChart) {
        MentalMoodInsightsSheet(store, onDismiss = { showChart = false },
            onRecord = { day ->
                showChart = false
                onOpen(Screen.MoodRecord(day))
            })
    }
    if (showDateSheet) {
        MentalMoodCalendarSheet(store, epochDay,
            onSelect = {
                anchorEpochDay = it
                coroutineScope.launch { pager.scrollToPage(365) }
            },
            onRecord = { day ->
                showDateSheet = false
                onOpen(Screen.MoodRecord(day))
            },
            onDismiss = { showDateSheet = false })
    }
}

@Composable
private fun MentalArticleCard(
    title: String,
    subtitle: String,
    imageRes: Int,
    cropX: Int,
    cropY: Int,
) {
    val colors = LocalWatchColors.current
    val image = ImageBitmap.imageResource(imageRes)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            .motionClickable(onClick = {})
            .clip(RoundedCornerShape(24.dp)).background(colors.surface),
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.675f)) {
            drawImage(
                image = image,
                srcOffset = IntOffset(cropX, cropY),
                srcSize = IntSize(407, 243),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.High,
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, lineHeight = 28.sp)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = colors.textPrimary, fontSize = 16.sp,
                lineHeight = 23.sp)
        }
    }
}

private object Colors {
    val text = Color(0xFF1C1C1E)
}

@Composable
private fun MentalMoodChartSheet(store: AppStore, onDismiss: () -> Unit) {
    val colors = LocalWatchColors.current
    var period by rememberSaveable { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf("周", "月", "6个月", "年")
    val days = when (period) { 0 -> 7; 1 -> 30; 2 -> 182; else -> 365 }
    val start = LocalDate.now().minusDays((days - 1).toLong()).toEpochDay()
    val entries = store.moods.filter { it.epochDay >= start }.sortedBy { it.id }
    val rangeLabel = when (period) {
        0 -> "2026年10月25日–31日"
        1 -> "${LocalDate.now().year}年${LocalDate.now().monthValue}月"
        2 -> "近 6 个月"
        else -> "${LocalDate.now().year}年"
    }

    LiquidGlassBottomSheet(onDismiss, fullHeight = true, surfaceColor = colors.groupedSurface,
        showNavigation = false, contentPadding = 0.dp) {
        Column(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LiquidGlassIconButton(Icons.Rounded.Close, "关闭", onDismiss, size = 48.dp, tint = mentalInk)
                Text("心理状态", color = mentalInk, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(48.dp))
            }
            Spacer(Modifier.height(18.dp))
            LiquidGlassSlidingSegmented(labels, period, { period = it })
            Spacer(Modifier.height(18.dp))
            Text("总计", color = mentalGray, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(entries.size.toString(), color = mentalInk, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(" 个条目", color = mentalGray, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(rangeLabel, color = mentalGray, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            MoodScatterChart(entries, Modifier.fillMaxWidth().height(300.dp))
            Spacer(Modifier.height(14.dp))
            LiquidGlassSlidingSegmented(
                options = listOf("状态", "关联", "生活因素"),
                selectedIndex = tab,
                onSelected = { tab = it },
            )
            Spacer(Modifier.height(12.dp))
            MentalChartRow("每日心情", "${entries.count { it.scope == MoodScope.DAY }} 个条目")
            Spacer(Modifier.height(10.dp))
            MentalChartRow("瞬时情绪", "${entries.count { it.scope == MoodScope.MOMENT }} 个条目")
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MoodScatterChart(entries: List<MoodEntry>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        for (i in 0..2) {
            val y = size.height * i / 2f
            drawLine(Color(0xFFE7E7EC), Offset(0f, y), Offset(size.width, y), 1f, pathEffect = dash)
        }
        if (entries.isEmpty()) {
            drawCircle(Color(0xFFD8D8DD), 5.dp.toPx(), Offset(size.width * .1f, size.height * .5f))
            return@Canvas
        }
        val minDay = entries.minOf { it.epochDay }
        val maxDay = entries.maxOf { it.epochDay }
        entries.forEach { entry ->
            val x = if (maxDay == minDay) size.width * .5f
            else size.width * ((entry.epochDay - minDay).toFloat() / (maxDay - minDay))
            val y = size.height * (1f - entry.value.coerceIn(0f, 1f))
            drawCircle(Color(0xFF2CB9AD), 6.dp.toPx(), center = Offset(x, y))
        }
    }
}

@Composable
private fun MentalChartRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(22.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = mentalInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Text(value, color = mentalInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MentalDateSheet(
    selected: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalWatchColors.current
    val today = LocalDate.now()
    val days = (0L..20L).map { today.minusDays(it).toEpochDay() }
    LiquidGlassBottomSheet(onDismiss, fullHeight = false, surfaceColor = colors.groupedSurface,
        showNavigation = false) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("选择日期", color = mentalInk, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            LiquidGlassIconButton(Icons.Rounded.Close, "关闭", onDismiss, size = 42.dp, tint = mentalInk)
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().height(360.dp).verticalScroll(rememberScrollState())) {
            days.forEach { day ->
                val date = LocalDate.ofEpochDay(day)
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(day) }
                        .background(if (day == selected) Color(0xFFE3F7F2) else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${date.monthValue}月${date.dayOfMonth}日", color = mentalInk, fontSize = 17.sp,
                        modifier = Modifier.weight(1f))
                    if (day == selected) Icon(Icons.Rounded.Check, null, tint = Color(0xFF2CB9AD))
                }
            }
        }
    }
}
@Composable
private fun MentalBackButton(
    onBack: () -> Unit,
    backdrop: Backdrop,
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
            drawLine(
                Color(0xFF0B0B0F),
                Offset(size.width * .66f, size.height * .14f),
                Offset(size.width * .32f, size.height * .5f),
                2.8.dp.toPx(),
                StrokeCap.Round,
            )
            drawLine(
                Color(0xFF0B0B0F),
                Offset(size.width * .32f, size.height * .5f),
                Offset(size.width * .66f, size.height * .86f),
                2.8.dp.toPx(),
                StrokeCap.Round,
            )
        }
    }
}
