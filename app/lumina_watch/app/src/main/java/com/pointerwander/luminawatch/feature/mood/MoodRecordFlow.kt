package com.pointerwander.luminawatch.feature.mood

import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.pointerwander.luminawatch.ui.motionClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.MoodScope
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassIconButton
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val recordLabels = listOf(
    "非常不愉快", "不愉快", "有点不愉快", "不悲不喜",
    "有点愉快", "愉快", "很愉快", "非常愉快",
)
private val cheerfulWords = listOf(
    "惊奇", "兴奋", "惊讶", "热忱", "开心", "欢乐", "勇敢", "自豪", "自信",
    "满怀希望", "愉悦", "满意", "如释重负", "感恩", "满足", "平静", "平和",
)
private val difficultWords = listOf(
    "不堪重负", "挫败", "烦躁", "愤怒", "尴尬", "孤独", "害怕", "嫉妒", "焦虑",
    "疲惫", "担心", "悲伤", "失落", "沮丧", "压力大", "困惑",
)
private val influenceGroups = listOf(
    listOf("健康", "健身", "自我照顾", "爱好", "身份", "心灵"),
    listOf("社群", "家人", "朋友", "伴侣", "约会"),
    listOf("家务", "工作", "教育", "旅行", "天气", "时事", "金钱"),
)

internal fun moodIndex(value: Float) = (value.coerceIn(0f, 1f) * 7f).roundToInt()
internal fun moodRangeLabel(value: Float): String = recordLabels[moodIndex(value)]

private fun buttonColor(value: Float): Color = moodPaletteColor(value, listOf(
    Color(0xFF361B84), Color(0xFF2947AA), Color(0xFF1876B4), Color(0xFF138DA7),
    Color(0xFF3B977D), Color(0xFFC18B0D), Color(0xFFF48616), Color(0xFFF16632),
))

private fun sheetColor(value: Float): Color = moodPaletteColor(value, listOf(
    Color(0xFFC8BCE5), Color(0xFFD2D6F1), Color(0xFFD9E7F6), Color(0xFFDCEEF1),
    Color(0xFFE1EFDF), Color(0xFFFFEABF), Color(0xFFFFD9A4), Color(0xFFFFC795),
))

@Composable
fun MoodRecordFlow(store: AppStore, selectedEpochDay: Long?, onBack: () -> Unit, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var scope by rememberSaveable { mutableStateOf(MoodScope.MOMENT) }
    var value by rememberSaveable { mutableFloatStateOf(.5f) }
    var words by remember { mutableStateOf(emptyList<String>()) }
    var factors by remember { mutableStateOf(emptyList<String>()) }
    var showMore by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val closeProgress by androidx.compose.animation.core.animateFloatAsState(
        if (closing) 1f else 0f, tween(340), label = "弹窗收起")
    val tint = buttonColor(value)
    val background by animateColorAsState(sheetColor(value), tween(260), label = "心情背景颜色")
    val isIntro = step == 0
    val sheetTop = if (isIntro) Color(0xFFDDE8E8) else lerp(background, Color.White, .58f)
    val sheetBottom = if (isIntro) Color(0xFFC6DDDF) else background
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BackHandler {
        when {
            showMore -> showMore = false
            step in 1..3 -> step--
            step < 4 -> onBack()
        }
    }
    LaunchedEffect(saved) {
        if (saved) {
            delay(900)
            step = 5
            delay(1100)
            closing = true
            delay(360)
            onDone()
        }
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.fillMaxSize().padding(top = 28.dp)
                .graphicsLayer {
                    translationY = size.height * closeProgress
                }
                .clip(RoundedCornerShape(topStart = 42.dp, topEnd = 42.dp))
                .background(Brush.verticalGradient(listOf(sheetTop, lerp(sheetTop, sheetBottom, .46f), sheetBottom)))
                .border(1.dp, Color.White.copy(alpha = .48f), RoundedCornerShape(topStart = 42.dp, topEnd = 42.dp))
                .padding(horizontal = 24.dp),
        ) {
            if (step < 4) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isIntro) Spacer(Modifier.size(48.dp)) else
                        LiquidGlassIconButton(Icons.Rounded.ChevronLeft, "上一步", { step-- }, tint = Color(0xFF192D33))
                    Text(if (isIntro) "" else if (scope == MoodScope.MOMENT) "情绪" else "心情",
                        color = Color(0xFF172127), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    LiquidGlassIconButton(Icons.Rounded.Close, "关闭记录", onBack, tint = Color(0xFF263C40))
                }
            }

            AnimatedContent(targetState = step, modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally(spring(dampingRatio = .88f, stiffness = 450f)) {
                        if (forward) it else -it / 4
                    } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(tween(240)) {
                            if (forward) -it / 4 else it
                        } + fadeOut(tween(170)))
                }, label = "记录流程内容") { current ->
                when (current) {
                    0 -> IntroStep(scope, onSelect = { scope = it })
                    1 -> FeelingStep(value, onValue = { value = it }, scope = scope)
                    2 -> WordStep(value, words, onWord = { word ->
                        words = if (word in words) words - word else words + word
                    }, onMore = { showMore = true })
                    3 -> InfluenceStep(value, words, factors, onFactor = { factor ->
                        factors = if (factor in factors) factors - factor else factors + factor
                    }, note = note, onNote = { note = it.take(200) })
                    4 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        MoodCloud(value, Modifier.size(295.dp), expressive = true)
                    }
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(230.dp).blur(34.dp).background(
                            Brush.radialGradient(listOf(
                                moodColor(value).copy(alpha = .62f),
                                Color.White.copy(alpha = .25f), Color.Transparent,
                            )), CircleShape))
                        val successInk = lerp(tint, Color(0xFF201B16), .42f)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).background(successInk, CircleShape),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, tint = Color.White,
                                    modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("已记录", color = successInk, fontSize = 29.sp,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (step < 4) {
                key(if (isIntro) -1 else moodIndex(value)) { LiquidGlassButton(
                    text = if (step == 3) "完成" else "下一步",
                    onClick = {
                        if (step == 3) {
                            if (!saved) {
                                store.addMood(scope, value, words, factors, note,
                                    epochDay = selectedEpochDay ?: LocalDate.now().toEpochDay())
                                saved = true
                            }
                            step = 4
                        } else step++
                    },
                    modifier = Modifier.fillMaxWidth(), tint = if (isIntro) Color(0xFF248FA9) else tint,
                    height = 58.dp, fillAlpha = .98f,
                ) }
                Spacer(Modifier.height(20.dp + navigationInset))
            }
        }

        AnimatedVisibility(
            visible = showMore,
            enter = slideInVertically(tween(280), initialOffsetY = { it }) + fadeIn(tween(220)),
            exit = slideOutVertically(tween(230), targetOffsetY = { it }) + fadeOut(tween(180)),
        ) {
            MoreWordsSheet(
                selected = words,
                options = if (value < .43f) difficultWords else cheerfulWords,
                onToggle = { word -> words = if (word in words) words - word else words + word },
                onDismiss = { showMore = false },
            )
        }
    }
}

@Composable
private fun IntroStep(scope: MoodScope, onSelect: (MoodScope) -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            listOf(.18f, .62f, 1f, .46f, .04f).forEachIndexed { index, mood ->
                MoodCloud(mood, Modifier.size(if (index == 2) 78.dp else 57.dp), expressive = true)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("记录情绪或心情", color = Color(0xFF0F2025), fontSize = 29.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        ScopeCard(MoodScope.MOMENT, scope == MoodScope.MOMENT, onSelect)
        Spacer(Modifier.height(14.dp))
        ScopeCard(MoodScope.DAY, scope == MoodScope.DAY, onSelect)
    }
}

@Composable
private fun ScopeCard(item: MoodScope, selected: Boolean, onSelect: (MoodScope) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = .91f))
            .border(1.dp, Color.White.copy(alpha = .78f), shape)
            .motionClickable(onClick = { onSelect(item) }, role = Role.RadioButton)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (item == MoodScope.MOMENT) "◷ 情绪" else "◌ 心情",
                color = Color(0xFF667176), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (selected) Icon(Icons.Rounded.Check, "已选择", tint = Color(0xFF138DF4),
                modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(if (item == MoodScope.MOMENT) "当下感受" else "今天整体感受",
            color = Color(0xFF1D2427), fontSize = 21.sp, fontWeight = FontWeight.Bold)
        if (item == MoodScope.MOMENT) {
            Spacer(Modifier.height(4.dp))
            Text(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                color = if (selected) Color(0xFF0F91EE) else Color(0xFF899294),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FeelingStep(value: Float, onValue: (Float) -> Unit, scope: MoodScope) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(42.dp))
        Text(if (scope == MoodScope.MOMENT) "选取当下感受" else "选取今天的心情",
            color = Color(0xFF354044), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            MoodCloud(value, Modifier.size(240.dp), expressive = true)
        }
        Text(recordLabels[moodIndex(value)], color = Color(0xFF344044), fontSize = 28.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(25.dp))
        EightStopSlider(value, onValue, buttonColor(value))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("非常不愉快", color = Color(0xFF697477), fontSize = 12.sp)
            Text("非常愉快", color = Color(0xFF697477), fontSize = 12.sp)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun EightStopSlider(value: Float, onValue: (Float) -> Unit, tint: Color) {
    var widthPx by remember { mutableIntStateOf(1) }
    val currentValue by rememberUpdatedState(value)
    BoxWithConstraints(Modifier.fillMaxWidth().height(50.dp)
        .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
        .pointerInput(widthPx) {
            detectTapGestures { tap ->
                onValue((tap.x / widthPx).coerceIn(0f, 1f))
            }
        }
        .pointerInput(widthPx) {
            detectDragGestures(
                onDragEnd = { onValue(moodIndex(currentValue) / 7f) },
            ) { change, _ ->
                onValue((change.position.x / widthPx).coerceIn(0f, 1f))
                change.consume()
            }
        }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(40.dp).clip(CircleShape)
            .background(Color(0x3D7E8997)))
        Box(Modifier.offset(x = (maxWidth - 36.dp) * value.coerceIn(0f, 1f))
            .size(36.dp).shadow(4.dp, CircleShape)
            .background(Color.White, CircleShape)
            .border(1.dp, tint.copy(alpha = .08f), CircleShape))
    }
}

@Composable
private fun WordStep(value: Float, selected: List<String>, onWord: (String) -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CompactMood(value, selected.firstOrNull())
        Spacer(Modifier.height(28.dp))
        Text("哪一项最能描述这种感受？", color = Color(0xFF263237),
            fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x296A6B6B)))
        Spacer(Modifier.height(18.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            (if (value < .43f) difficultWords else cheerfulWords).take(17).forEach { word ->
                PillChoice(word, word in selected, buttonColor(value)) { onWord(word) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clickable { onMore() }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("更多内容", color = Color(0xFF6C7578), fontSize = 16.sp)
            Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFF6C7578))
        }
    }
}

@Composable
private fun InfluenceStep(
    value: Float, words: List<String>, selected: List<String>, onFactor: (String) -> Unit,
    note: String, onNote: (String) -> Unit,
) {
    val fieldRequester = remember { BringIntoViewRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(fieldFocused, imeBottom) {
        if (fieldFocused && imeBottom > 0) {
            delay(120)
            fieldRequester.bringIntoView()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CompactMood(value, words.firstOrNull())
        Spacer(Modifier.height(28.dp))
        Text("对你影响最大的是？", color = Color(0xFF263237),
            fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(11.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x296A6B6B)))
        Spacer(Modifier.height(18.dp))
        influenceGroups.forEach { group ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                group.forEach { factor ->
                    PillChoice(factor, factor in selected, buttonColor(value)) { onFactor(factor) }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        OutlinedTextField(note, onNote, modifier = Modifier.fillMaxWidth()
            .bringIntoViewRequester(fieldRequester)
            .onFocusChanged { fieldFocused = it.isFocused },
            placeholder = { Text("更多情境…（可选）") }, maxLines = 2,
            shape = RoundedCornerShape(22.dp))
    }
}

@Composable
private fun CompactMood(value: Float, word: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MoodCloud(value, Modifier.size(132.dp), expressive = true)
        Text(recordLabels[moodIndex(value)], color = Color(0xFF2E3336),
            fontSize = 23.sp, fontWeight = FontWeight.Bold)
        if (word != null) Text(word, color = Color(0xFF4A5154), fontSize = 17.sp)
    }
}

@Composable
private fun PillChoice(label: String, selected: Boolean, tint: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.clip(shape)
        .background(if (selected) tint.copy(alpha = .75f) else Color.White.copy(alpha = .82f))
        .motionClickable(onClick = onClick, role = Role.Checkbox)
        .padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, color = if (selected) Color.White else Color(0xFF22292C),
            fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MoreWordsSheet(selected: List<String>, options: List<String>, onToggle: (String) -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x780F1420))) {
        Column(Modifier.fillMaxSize().padding(top = 46.dp)
            .clip(RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp))
            .background(Color(0xFFF5F5F9)).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                LiquidGlassIconButton(Icons.Rounded.Close, "关闭更多内容", onDismiss)
                LiquidGlassIconButton(Icons.Rounded.Check, "完成选择", onDismiss,
                    filled = true, tint = Color(0xFF128DF4))
            }
            Spacer(Modifier.height(22.dp))
            Text("哪一项最能描述这种感受？", color = Color.Black, fontSize = 22.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(30.dp))
                .background(Color.White).verticalScroll(rememberScrollState())) {
                val allWords = (options + difficultWords + cheerfulWords +
                    listOf("感恩", "满足", "平静", "平和")).distinct()
                allWords.forEachIndexed { index, word ->
                    val isSelected = word in selected
                    Row(Modifier.fillMaxWidth().height(54.dp)
                        .background(if (isSelected) Color(0xFFD1D1D6) else Color.White)
                        .clickable { onToggle(word) }
                        .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp)
                            .background(if (isSelected) Color(0xFF0A84FF) else Color.White, CircleShape)
                            .border(if (isSelected) 1.dp else 2.dp,
                                if (isSelected) Color(0xFF0A84FF) else Color(0xFFB9BEC3), CircleShape),
                            contentAlignment = Alignment.Center) {
                            if (isSelected) Icon(Icons.Rounded.Check, null,
                                tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(word, color = Color(0xFF17191A), fontSize = 17.sp)
                    }
                    if (index < allWords.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 56.dp)
                        .height(1.dp).background(Color(0xFFE9EAEB)))
                }
            }
        }
    }
}
