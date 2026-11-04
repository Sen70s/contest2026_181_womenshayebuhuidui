package com.pointerwander.luminawatch.feature.mood

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.app.Screen
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.MoodEntry
import com.pointerwander.luminawatch.data.MoodScope
import com.pointerwander.luminawatch.ui.DemoBadge
import com.pointerwander.luminawatch.ui.EmptyState
import com.pointerwander.luminawatch.ui.GlassCard
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassChoiceChip
import com.pointerwander.luminawatch.ui.LiquidGlassIconButton
import com.pointerwander.luminawatch.ui.PageColumn
import com.pointerwander.luminawatch.ui.PageHeader
import com.pointerwander.luminawatch.ui.PrimaryButton
import com.pointerwander.luminawatch.ui.SecondaryButton
import com.pointerwander.luminawatch.ui.SectionTitle
import com.pointerwander.luminawatch.ui.SourceTag
import com.pointerwander.luminawatch.ui.SurfaceCard
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val pleasantWords = listOf("开心", "兴奋", "放松", "满足", "有能量", "期待", "自信", "感恩", "平静")
private val unpleasantWords = listOf("疲惫", "焦虑", "委屈", "烦躁", "失落", "担心", "孤单", "压力大", "难过")
private val moodFactors = listOf("工作", "学习", "家庭", "睡眠", "健康", "天气")

internal fun moodLabel(value: Float): String = when {
    value >= .88f -> "非常愉快"
    value >= .72f -> "愉快"
    value >= .58f -> "有点愉快"
    value >= .42f -> "平静"
    value >= .28f -> "有点不愉快"
    value >= .12f -> "不愉快"
    else -> "非常不愉快"
}

private fun moodActionColor(value: Float): Color {
    val v = value.coerceIn(0f, 1f)
    return when {
        v < .35f -> lerp(Color(0xFF2456B1), Color(0xFF167B93), v / .35f)
        v < .7f -> lerp(Color(0xFF167B93), Color(0xFF668519), (v - .35f) / .35f)
        else -> lerp(Color(0xFF668519), Color(0xFFD85B2A), (v - .7f) / .3f)
    }
}

private fun moodScopeLabel(entry: MoodEntry): String {
    if (entry.epochDay == LocalDate.now().toEpochDay()) return entry.scope.label
    return if (entry.scope == MoodScope.DAY) "当日" else "当时"
}

@Composable
fun MoodRecordScreen(store: AppStore, selectedEpochDay: Long?, onBack: () -> Unit, onDone: () -> Unit) {
    val colors = LocalWatchColors.current
    val recordingDate = selectedEpochDay?.let(LocalDate::ofEpochDay)
    val isBackfill = recordingDate != null && recordingDate != LocalDate.now()
    var step by rememberSaveable { mutableStateOf(0) }
    var scope by rememberSaveable { mutableStateOf(if (isBackfill) MoodScope.DAY else MoodScope.MOMENT) }
    var value by rememberSaveable { mutableStateOf(.5f) }
    var words by remember { mutableStateOf(emptyList<String>()) }
    var factors by remember { mutableStateOf(emptyList<String>()) }
    var note by remember { mutableStateOf("") }
    val tint by animateColorAsState(moodBackground(value), tween(280), label = "心情背景")
    val accent by animateColorAsState(moodActionColor(value), tween(280), label = "心情按钮")
    BackHandler(enabled = step > 0) { step-- }
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(
                if (store.darkTheme) listOf(colors.background, lerp(colors.background, tint, .18f), colors.background)
                else listOf(colors.background, tint, tint),
            ))
            .padding(horizontal = 24.dp),
    ) {
        PageHeader("记录心情", "${recordingDate?.let { "${it.monthValue}月${it.dayOfMonth}日 · " } ?: ""}${step + 1} / 3 · ${listOf("选择感受", "描述感受", "补充背景")[step]}",
            onBack = if (step == 0) onBack else ({ step--; Unit }), accent = accent)
        Spacer(Modifier.height(10.dp))
        MoodStepBar(step, accent)
        if (step == 0) {
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MoodScope.entries.forEach { item ->
                    if (isBackfill && item == MoodScope.MOMENT) return@forEach
                    LiquidGlassChoiceChip(
                        if (item == MoodScope.MOMENT) "瞬时情绪" else "每日心情",
                        scope == item, { scope = item }, tint = accent,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                MoodCloud(value, Modifier.fillMaxWidth().height(300.dp))
            }
            Text(if (isBackfill) "那天你感觉如何？" else "你现在感觉如何？", color = colors.textSecondary, fontSize = 15.sp)
            Text(moodLabel(value), color = colors.textPrimary, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(colors.surface.copy(alpha = if (store.darkTheme) 1f else .68f))
                    .border(1.dp, colors.outline, RoundedCornerShape(28.dp))
                    .padding(horizontal = 17.dp, vertical = 14.dp),
            ) {
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = accent,
                        inactiveTrackColor = accent.copy(alpha = .22f),
                    ),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("非常不愉快", color = colors.textSecondary, fontSize = 12.sp)
                    Text("非常愉快", color = colors.textSecondary, fontSize = 12.sp)
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(185.dp), contentAlignment = Alignment.Center) {
                    MoodCloud(value, Modifier.size(178.dp))
                }
                Text(moodLabel(value), color = colors.textPrimary, fontSize = 24.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(30.dp))
                if (step == 1) {
                    Text("哪些词最贴近这种感受？", color = colors.textPrimary,
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("可选 1–3 个，也可以直接继续", color = colors.textSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    val options = if (value >= .5f) pleasantWords else unpleasantWords
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        options.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { word ->
                                    MoodChoice(word, words.contains(word), accent) {
                                        words = when {
                                            words.contains(word) -> words - word
                                            words.size >= 3 -> words
                                            else -> words + word
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text("什么影响了你的感受？", color = colors.textPrimary,
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("以下内容都可以跳过", color = colors.textSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        moodFactors.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { factor ->
                                    MoodChoice(factor, factors.contains(factor), accent) {
                                        factors = if (factors.contains(factor)) factors - factor else factors + factor
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(200) },
                        label = { Text("想补充的话（可选）") },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(colors.surface.copy(alpha = if (store.darkTheme) 1f else .55f)),
                        minLines = 2,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("这只是个人记录，不用于判断心理疾病。", color = colors.textSecondary, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        LiquidGlassButton(
            text = if (step == 2) "保存这次心情" else "下一步",
            onClick = {
                if (step < 2) step++ else {
                    store.addMood(scope, value, words, factors, note,
                        epochDay = selectedEpochDay ?: LocalDate.now().toEpochDay())
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            tint = accent,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MoodStepBar(step: Int, accent: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { index ->
            Box(
                Modifier.weight(1f).height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index <= step) accent else accent.copy(alpha = .18f)),
            )
        }
    }
}

@Composable
private fun MoodChoice(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val colors = LocalWatchColors.current
    LiquidGlassChoiceChip(label, selected, onClick, tint = accent, height = 46.dp)
}

@Composable
fun MoodCalendarScreen(
    store: AppStore,
    onBack: () -> Unit,
    onOpen: (Screen) -> Unit,
    selectedDate: Long?,
    onSelectedDate: (Long?) -> Unit,
) {
    val colors = LocalWatchColors.current
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    val month = YearMonth.now().plusMonths(monthOffset.toLong())
    val firstDay = month.atDay(1)
    val offset = firstDay.dayOfWeek.value - 1
    val days = month.lengthOfMonth()

    PageColumn {
        PageHeader(
            "心情月历",
            month.format(DateTimeFormatter.ofPattern("yyyy 年 M 月", Locale.CHINA)),
            onBack = onBack,
            actions = {
                LiquidGlassIconButton(Icons.Rounded.ChevronLeft, "上个月",
                    { monthOffset--; onSelectedDate(null) }, size = 42.dp)
                LiquidGlassIconButton(Icons.Rounded.ChevronRight, "下个月",
                    { monthOffset++; onSelectedDate(null) }, size = 42.dp)
            },
        )
        DemoBadge()

        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Text(it, color = colors.textSecondary, fontSize = 11.sp, modifier = Modifier.width(40.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            val rows = ((offset + days + 6) / 7)
            repeat(rows) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    repeat(7) { column ->
                        val cellIndex = row * 7 + column
                        val dayNumber = cellIndex - offset + 1
                        if (dayNumber in 1..days) {
                            val date = LocalDate.of(month.year, month.monthValue, dayNumber)
                            val entries = store.moodsOn(date.toEpochDay())
                            val avg = if (entries.isEmpty()) null else entries.map { it.value }.average().toFloat()
                            Box(
                                Modifier.width(44.dp).height(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (avg == null) colors.surfaceMuted else if (store.darkTheme)
                                        lerp(colors.surfaceMuted, moodBackground(avg), .25f) else moodBackground(avg))
                                    .border(
                                        if (selectedDate == date.toEpochDay()) 2.dp else 1.dp,
                                        if (selectedDate == date.toEpochDay()) colors.textPrimary
                                        else colors.textSecondary.copy(alpha = if (avg == null) .38f else .24f),
                                        RoundedCornerShape(12.dp),
                                    )
                                    .clickable { onSelectedDate(date.toEpochDay()) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$dayNumber", color = colors.textPrimary, fontSize = 11.sp,
                                        fontWeight = if (avg == null) FontWeight.Normal else FontWeight.Bold)
                                    if (entries.isNotEmpty()) {
                                        MoodCloud(entries.first().value, Modifier.size(20.dp), breathing = false)
                                    }
                                }
                                if (entries.isNotEmpty()) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).padding(2.dp)
                                            .size(15.dp).clip(CircleShape)
                                            .background(moodActionColor(avg ?: .5f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "${entries.size}",
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.width(44.dp).height(56.dp))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        SectionTitle(
            if (selectedDate == null) "本月记录" else "${LocalDate.ofEpochDay(selectedDate!!).monthValue}月${LocalDate.ofEpochDay(selectedDate!!).dayOfMonth}日记录",
            if (selectedDate == null) "记录心情" else "本月全部",
        ) { if (selectedDate == null) onOpen(Screen.MoodRecord()) else onSelectedDate(null) }
        if (selectedDate != null) {
            PrimaryButton("记录这一天的心情", { onOpen(Screen.MoodRecord(selectedDate)) }, Modifier.fillMaxWidth())
        }
        val displayedEntries = store.moods.filter {
            if (selectedDate != null) it.epochDay == selectedDate
            else it.epochDay >= firstDay.toEpochDay() && it.epochDay <= month.atEndOfMonth().toEpochDay()
        }.sortedByDescending { it.id }
        if (displayedEntries.isEmpty()) {
            EmptyState(
                title = if (selectedDate == null) "这个月还没有记录" else "这一天没有记录",
                body = "记录一次此刻或今日的心情，月历就会亮起对应的颜色。",
                actionLabel = "记录心情",
                onAction = { onOpen(Screen.MoodRecord(selectedDate)) },
            )
        } else {
            displayedEntries.forEach { entry ->
                SurfaceCard(Modifier.clickable { onOpen(Screen.MoodDetail(entry.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape)
                                .background(moodBackground(entry.value)),
                        ) { MoodCloud(entry.value, Modifier.size(30.dp), breathing = false) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${moodScopeLabel(entry)} · ${moodLabel(entry.value)}",
                                color = colors.textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                entry.words.joinToString("、").ifEmpty { "未选择情绪词" } + " · " + entry.timeLabel,
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MoodDetailScreen(
    store: AppStore,
    entryId: Long,
    onBack: () -> Unit,
    onOpen: (Screen) -> Unit,
    onChat: () -> Unit,
) {
    val colors = LocalWatchColors.current
    val entry = store.mood(entryId)

    PageColumn {
        PageHeader("心情详情", "一次连续值记录", onBack = onBack)
        DemoBadge()
        if (entry == null) {
            EmptyState(title = "记录不存在", body = "这条心情记录可能已被删除。", actionLabel = "返回", onAction = onBack)
            return@PageColumn
        }

        GlassCard {
            Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                MoodCloud(entry.value, Modifier.fillMaxWidth().height(160.dp))
            }
            Text(
                "${moodScopeLabel(entry)} · ${moodLabel(entry.value)}（${(entry.value * 100).roundToInt()} / 100）",
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(entry.timeLabel, color = colors.textSecondary, fontSize = 12.sp)
        }

        SurfaceCard {
            InfoLine("情绪词", entry.words.joinToString("、").ifEmpty { "未选择" })
            InfoLine("影响因素", entry.factors.joinToString("、").ifEmpty { "未选择" })
            InfoLine("备注", entry.note.ifEmpty { "无" })
            Spacer(Modifier.height(4.dp))
            SourceTag("本地保存 · 未同步", colors.textSecondary)
        }

        PrimaryButton("和小云聊聊这次记录", onChat, Modifier.fillMaxWidth(), Icons.AutoMirrored.Rounded.Chat)
        SecondaryButton("删除这条记录", {
            store.deleteMood(entry.id)
            onBack()
        }, Modifier.fillMaxWidth(), Icons.Rounded.DeleteOutline, tint = colors.danger)
        Text(
            "对话中是否引用这条记录，仍需你在对话页确认。",
            color = colors.textSecondary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val colors = LocalWatchColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        Text(value, color = colors.textPrimary, fontSize = 12.sp)
    }
}
