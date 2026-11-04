package com.pointerwander.luminawatch.feature.health

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.pointerwander.luminawatch.ui.motionClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close

import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import androidx.compose.foundation.shape.CircleShape
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.pointerwander.luminawatch.app.HealthSection
import com.pointerwander.luminawatch.app.Screen
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.Metric
import com.pointerwander.luminawatch.data.SummaryItem
import com.pointerwander.luminawatch.ui.ActionRow
import com.pointerwander.luminawatch.ui.AllWatchesGlassButton
import com.pointerwander.luminawatch.ui.EmptyState
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassIconButton
import com.pointerwander.luminawatch.ui.LiquidGlassSurface
import com.pointerwander.luminawatch.ui.PageColumn
import com.pointerwander.luminawatch.ui.PageHeader
import com.pointerwander.luminawatch.ui.PrimaryButton
import com.pointerwander.luminawatch.feature.mood.MoodCloud
import com.pointerwander.luminawatch.feature.mood.moodLabel
import com.pointerwander.luminawatch.ui.SectionTitle

private val pageGray = Color(0xFFF3F3F8)

@Composable
fun HealthDashboardScreen(store: AppStore, onOpen: (Screen) -> Unit) {
    val colors = LocalWatchColors.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val scrollState = rememberScrollState()
    val backdrop = rememberLayerBackdrop()
    val buttonVisibleThreshold = with(LocalDensity.current) { 16.dp.roundToPx() }
    val showAllWatchesButton by remember(scrollState, buttonVisibleThreshold) {
        derivedStateOf { scrollState.value <= buttonVisibleThreshold }
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var showSummaryEditor by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(pageGray)) {
        // 只捕获页面内容，玻璃按钮作为兄弟浮层采样；不能把按钮捕获进自己的 backdrop。
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            PageColumn(Modifier.background(pageGray), scrollState = scrollState, spacing = 12.dp) {
        Spacer(Modifier.height(statusBarHeight + 44.dp))
        Text(
            if (store.device.bound) store.device.name else "未选择手表",
            color = colors.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold,
        )
        HealthSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            expanded = searchExpanded,
            onExpandedChange = { searchExpanded = it },
            focusRequester = searchFocus,
            backdrop = null,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("摘要", color = colors.textPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            LiquidGlassButton("编辑", { showSummaryEditor = true }, height = 38.dp, filled = false)
        }
        if (store.summaryItems.isEmpty()) {
            EmptyState("摘要还没有卡片", "点右上角“编辑”添加你想置顶的内容。", "编辑摘要") {
                showSummaryEditor = true
            }
        } else {
            store.summaryItems.forEach { item ->
                SummaryPreviewCard(store, item) { onOpen(summaryDestination(item)) }
            }
        }

        Text("健康类别", color = colors.textPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp))
        val categories = listOf(
            CategorySpec("心脏", Icons.Rounded.FavoriteBorder, Color(0xFFF43E67), Screen.HeartDetail),
            CategorySpec("血氧", Icons.Rounded.Bloodtype, Color(0xFF21B8D6), Screen.OxygenDetail),
            CategorySpec("活动", Icons.Rounded.DirectionsWalk, Color(0xFFFF802B), Screen.HealthSectionPage(HealthSection.ACTIVITY)),
            CategorySpec("运动", Icons.Rounded.DirectionsRun, Color(0xFF70A827), Screen.RunningHome),
            CategorySpec("运动记录", Icons.Rounded.History, Color(0xFF5B8DEF), Screen.WorkoutHistory),
            CategorySpec("心理健康", Icons.Rounded.Mood, Color(0xFF2CB9AD), Screen.HealthSectionPage(HealthSection.MENTAL)),
            CategorySpec("小云关怀", Icons.Rounded.Spa, Color(0xFF7569DA), Screen.CareHome),
        )
        categories.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { category ->
                    CategoryTile(category, Modifier.weight(1f)) { onOpen(category.destination) }
                }
            }
        }
        Text("健康提示仅供日常参考，不构成医疗诊断。", color = colors.textSecondary,
            fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
            }
        }
        // 滚动卡片从状态栏后方经过，顶部以柔和渐隐保持系统图标清晰。
        Box(
            Modifier.fillMaxWidth().height(statusBarHeight + 18.dp)
                .background(Brush.verticalGradient(
                    0f to pageGray.copy(alpha = .9f),
                    1f to pageGray.copy(alpha = 0f),
                )),
        )
        AnimatedVisibility(
            visible = showAllWatchesButton,
            modifier = Modifier.align(Alignment.TopStart)
                .padding(start = 20.dp, top = statusBarHeight + 8.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            AllWatchesGlassButton(
                backdrop = backdrop,
                onClick = { onOpen(Screen.AllWatches) },
            )
        }
        if (showSummaryEditor) {
            SummaryEditorSheet(store) { showSummaryEditor = false }
        }
    }
}

private data class CategorySpec(
    val title: String,
    val icon: ImageVector,
    val color: Color,
    val destination: Screen,
)

@Composable
private fun CategoryTile(spec: CategorySpec, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(118.dp).clip(RoundedCornerShape(22.dp))
            .background(spec.color).motionClickable(onClick).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(spec.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(29.dp))
        Text(spec.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

private fun summaryDestination(item: SummaryItem): Screen = when (item) {
    SummaryItem.HEART -> Screen.HeartDetail
    SummaryItem.OXYGEN -> Screen.OxygenDetail
    SummaryItem.STEPS -> Screen.ActivityDetail("步数")
    SummaryItem.STANDING -> Screen.ActivityDetail("站立时间")
    SummaryItem.CALORIES -> Screen.ActivityDetail("活动消耗")
    SummaryItem.RUNNING -> Screen.RunningHome
    SummaryItem.MOOD -> Screen.HealthSectionPage(HealthSection.MENTAL)
    SummaryItem.CARE -> Screen.CareHome
}

@Composable
private fun SummaryPreviewCard(store: AppStore, item: SummaryItem, onClick: () -> Unit) {
    val colors = LocalWatchColors.current
    val mood = store.todayMoods().lastOrNull()
    val sample = when (item) {
        SummaryItem.HEART -> store.latestSample(Metric.HEART_RATE)
        SummaryItem.OXYGEN -> store.latestSample(Metric.OXYGEN)
        else -> null
    }
    val accent = when (item) {
        SummaryItem.HEART -> colors.heart
        SummaryItem.OXYGEN -> colors.oxygen
        SummaryItem.MOOD -> Color(0xFF24B4A6)
        SummaryItem.CARE -> Color(0xFF7569DA)
        SummaryItem.RUNNING -> Color(0xFF70A827)
        else -> Color(0xFFFF6B2C)
    }
    val icon = when (item) {
        SummaryItem.HEART -> Icons.Rounded.FavoriteBorder
        SummaryItem.OXYGEN -> Icons.Rounded.Bloodtype
        SummaryItem.MOOD -> Icons.Rounded.Psychology
        SummaryItem.CARE -> Icons.Rounded.Spa
        SummaryItem.RUNNING -> Icons.Rounded.DirectionsRun
        SummaryItem.STEPS -> Icons.Rounded.LocalFireDepartment
        else -> Icons.Rounded.LocalFireDepartment
    }
    val value: String
    val unit: String
    val note: String
    val time = sample?.timeLabel ?: if (item == SummaryItem.MOOD) mood?.timeLabel ?: "今天" else "今天"
    when (item) {
        SummaryItem.HEART -> {
            value = sample?.value?.toString() ?: "--"
            unit = "次/分"
            note = if (sample == null) "连接手表后查看记录" else ""
        }
        SummaryItem.OXYGEN -> {
            value = sample?.value?.toString() ?: "--"
            unit = "%"
            note = if (sample == null) "连接手表后查看记录" else ""
        }
        SummaryItem.STEPS -> {
            val today = java.time.LocalDate.now()
            val entered = store.manualStepEntries.sumOf { entry ->
                if (java.time.Instant.ofEpochMilli(entry.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == today) entry.steps else 0
            }
            value = if (store.canPreviewHealth || entered > 0) java.text.NumberFormat.getIntegerInstance().format((if (store.canPreviewHealth) 2847 else 0) + entered) else "--"
            unit = "步"
            note = if (store.canPreviewHealth || entered > 0) "" else "连接手表后查看记录"
        }
        SummaryItem.STANDING -> {
            value = if (store.canPreviewHealth) "8" else "--"
            unit = "次"
            note = if (store.canPreviewHealth) "" else "连接手表后查看记录"
        }
        SummaryItem.CALORIES -> {
            value = if (store.canPreviewHealth) "197" else "--"
            unit = "千卡"
            note = if (store.canPreviewHealth) "" else "连接手表后查看记录"
        }
        SummaryItem.RUNNING -> {
            value = "暂无跑步记录"
            unit = ""
            note = "手机端记录尚未接入"
        }
        SummaryItem.MOOD -> {
            value = mood?.words?.takeIf { it.isNotEmpty() }?.joinToString("、")
                ?: mood?.let { moodLabel(it.value) } ?: "今天尚未记录"
            unit = ""
            note = mood?.let { entry ->
                listOfNotNull(
                    entry.factors.takeIf { it.isNotEmpty() }?.joinToString("、"),
                    entry.note.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
            } ?: ""
        }
        SummaryItem.CARE -> {
            value = if (store.careEvents.isEmpty()) "暂无关怀记录" else "${store.careEvents.size} 条关怀记录"
            unit = ""
            note = "本机记录"
        }
    }
    val chartValues: List<Float> = when (item) {
        SummaryItem.HEART -> healthSeries(Metric.HEART_RATE, Period.DAY).map { it.value }
        SummaryItem.OXYGEN -> healthSeries(Metric.OXYGEN, Period.DAY).map { it.value }
        SummaryItem.STEPS -> chartBuckets(activityValues("步数", 0).map { it.toFloat() }, 8)
        SummaryItem.STANDING -> chartBuckets(activityValues("站立时间", 0).map { it.toFloat() }, 8)
        SummaryItem.CALORIES -> chartBuckets(activityValues("活动消耗", 0).map { it.toFloat() }, 8)
        else -> emptyList()
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Color.White).motionClickable(onClick).padding(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(23.dp))
            Text(item.label, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text(time, color = colors.textSecondary, fontSize = 12.sp)
            Icon(Icons.Rounded.ChevronRight, null, tint = colors.textSecondary,
                modifier = Modifier.padding(start = 4.dp).size(18.dp))
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, color = colors.textPrimary,
                        fontSize = if (value.startsWith("暂无") || value.startsWith("今天")) 22.sp else 34.sp,
                        fontWeight = FontWeight.Bold)
                    if (unit.isNotEmpty()) {
                        Text(unit, color = colors.textSecondary, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    }
                }
                if (note.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(note, color = colors.textSecondary, fontSize = 13.sp)
                }
            }
            if (item == SummaryItem.MOOD) {
                MoodCloud(mood?.value ?: .55f, Modifier.size(66.dp), breathing = false)
            } else {
                SummaryMiniBars(accent, chartValues, Modifier.size(86.dp, 46.dp))
                Spacer(Modifier.width(16.dp))
            }
        }
    }
}

@Composable
private fun SummaryMiniBars(
    accent: Color,
    values: List<Float>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val maxValue = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val heights = values.map { (it / maxValue).coerceIn(0f, 1f) }
        val gap = size.width / (heights.size * 1.55f)
        val barWidth = gap * .95f
        heights.forEachIndexed { index, h ->
            val x = index * (barWidth + gap)
            val barHeight = if (h == 0f) 3.dp.toPx() else size.height * (.14f + .86f * h)
            val color = when {
                h == 0f -> Color(0xFFE5E5EA)
                index == heights.lastIndex -> accent
                else -> accent.copy(alpha = .34f + .5f * h)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
private fun chartBuckets(values: List<Float>, count: Int): List<Float> {
    if (values.size <= count) return values
    val chunk = (values.size + count - 1) / count
    return (0 until count).map { index ->
        val start = index * chunk
        val end = minOf(start + chunk, values.size)
        if (start >= end) 0f else values.subList(start, end).sum()
    }
}

@Composable
fun SummaryEditorScreen(store: AppStore, onBack: () -> Unit) {
    SummaryEditorSheet(store, onBack)
}

@Composable
fun HealthSectionScreen(section: HealthSection, store: AppStore, onBack: () -> Unit, onOpen: (Screen) -> Unit) {
    if (section == HealthSection.MENTAL) {
        MentalHealthHomeScreen(store, onBack, onOpen)
        return
    }
    if (section == HealthSection.ACTIVITY) {
        ActivityHomeScreen(store, onBack, onOpen)
        return
    }
    val colors = LocalWatchColors.current
    val title = "活动"
    PageColumn(Modifier.background(pageGray)) {
        PageHeader(title, onBack = onBack)
        Text(if (section == HealthSection.MENTAL) "记录感受，了解自己的变化。" else "查看步数、站立和活动消耗。",
            color = colors.textSecondary, fontSize = 14.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).padding(12.dp)) {
            if (section == HealthSection.MENTAL) {
                ActionRow("记录心情", "保留原有三步记录流程", Icons.Rounded.Mood,
                    onClick = { onOpen(Screen.MoodRecord()) })
                ActionRow("心情月历", "查看和补记指定日期", Icons.Rounded.Mood,
                    onClick = { onOpen(Screen.MoodCalendar) })
                ActionRow("心理健康问卷", "结果仅供参考，不构成诊断", Icons.Rounded.Spa,
                    onClick = { onOpen(Screen.MentalAssessment) })
            } else {
                ActionRow("步数", if (store.canPreviewHealth) null else "暂无数据", Icons.Rounded.DirectionsWalk,
                    onClick = { onOpen(Screen.ActivityDetail("步数")) })
                ActionRow("站立活动", if (store.canPreviewHealth) null else "暂无数据", Icons.Rounded.DirectionsWalk,
                    onClick = { onOpen(Screen.ActivityDetail("站立时间")) })
                ActionRow("活动消耗", if (store.canPreviewHealth) null else "暂无数据", Icons.Rounded.DirectionsWalk,
                    onClick = { onOpen(Screen.ActivityDetail("活动消耗")) })
            }
        }
    }
}

@Composable
fun RunningHomeScreen(store: AppStore, onBack: () -> Unit) {
    FitnessTrainingScreen(store, onBack)
}
@Composable
fun HealthSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier,
    placeholder: String = "健康类别",
) {
    val colors = LocalWatchColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val inactiveBg = Color(0xFFEBEBED)
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                onQueryChange(spoken)
                onExpandedChange(true)
            }
        }
    }
    fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你要搜索的内容")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "当前设备没有可用的语音输入服务", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(expanded) {
        if (expanded) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (!expanded) {
            Row(
                Modifier.fillMaxWidth().height(50.dp).clip(Capsule()).background(inactiveBg)
                    .clickable {
                        onExpandedChange(true)
                        focusRequester.requestFocus()
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = Color(0xFF0B0B0F), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(placeholder, color = Color(0xFF8E8E93), fontSize = 20.sp, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(34.dp).clip(CircleShape).clickable { startVoiceInput() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Mic, null, tint = Color(0xFF0B0B0F), modifier = Modifier.size(24.dp))
                }
            }
        } else {
            LiquidGlassSurface(
                onClick = { focusRequester.requestFocus() },
                modifier = Modifier.weight(1f),
                height = 50.dp,
                shape = Capsule(),
                backdrop = backdrop,
                glassAlpha = .62f,
                border = Color.White.copy(alpha = .55f),
                contentPadding = 14.dp,
            ) { ink ->
                Icon(Icons.Rounded.Search, null, tint = Color(0xFF0B0B0F), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color(0xFF0B0B0F), fontSize = 17.sp),
                    cursorBrush = SolidColor(Color(0xFF0B0B0F)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.weight(1f)
                        .focusRequester(focusRequester),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text(placeholder, color = Color(0xFF8E8E93), fontSize = 17.sp)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(32.dp).clip(CircleShape).clickable { startVoiceInput() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Mic, null, tint = Color(0xFF0B0B0F), modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            LiquidGlassIconButton(
                icon = Icons.Rounded.Close,
                label = "关闭搜索",
                onClick = {
                    onQueryChange("")
                    onExpandedChange(false)
                    keyboard?.hide()
                    focusManager.clearFocus()
                },
                size = 46.dp,
                backdrop = backdrop,
                tint = Color(0xFF0B0B0F),
            )
        }
    }
}
