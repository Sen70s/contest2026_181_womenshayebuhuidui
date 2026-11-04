package com.pointerwander.luminawatch.feature.health

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangle
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.ui.LiquidGlassBottomSheet
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassIconButton
import com.pointerwander.luminawatch.ui.LiquidGlassSurface
import com.pointerwander.luminawatch.ui.LocalWatchColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlin.math.abs

private val pickerBlue = Color(0xFF0787F7)
private val pickerOrange = Color(0xFFFF6B35)
private val pickerValueGray = Color(0xFFE5E5EA)
private val pickerGlassEffects: BackdropEffectScope.() -> Unit = {
    colorControls(brightness = .12f, saturation = 1.2f)
    blur(12.dp.toPx())
    lens(20.dp.toPx(), 36.dp.toPx(), depthEffect = true)
}

@Composable
fun AddStepsSheet(store: AppStore, onDismiss: () -> Unit) {
    val colors = LocalWatchColors.current
    val now = remember { LocalTime.now() }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var hour by remember { mutableIntStateOf(now.hour) }
    var minute by remember { mutableIntStateOf(now.minute) }
    var stepText by remember { mutableStateOf("") }
    var activePopup by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val steps = stepText.toIntOrNull()
    val timestamp = date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val valid = steps != null && steps in 1..100_000 && timestamp <= System.currentTimeMillis()
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
                LiquidGlassIconButton(Icons.Rounded.Check, "保存步数", {
                    if (valid && store.addManualSteps(timestamp, steps!!)) {
                        keyboard?.hide()
                        onDismiss()
                    } else error = "请输入 1 至 100,000 步，并选择不晚于现在的时间"
                }, enabled = valid, filled = true, tint = pickerBlue, size = 48.dp)
            }
            Spacer(Modifier.height(42.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(82.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null,
                        tint = pickerOrange, modifier = Modifier.size(48.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text("步数", color = colors.textPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(40.dp))
            Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp)) {
                StepEntryRow("日期") {
                    LiquidGlassSurface({ focusManager.clearFocus(); keyboard?.hide(); activePopup = "date" },
                        modifier = Modifier.onGloballyPositioned {
                            dateBottom = it.positionInRoot().y.toInt() + it.size.height
                        }, height = 44.dp,
                        glassAlpha = .88f, glassColor = pickerValueGray, contentPadding = 12.dp) { _ ->
                        Text("${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                            color = if (activePopup == "date") pickerBlue else colors.textPrimary,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                EntryDivider()
                StepEntryRow("时间") {
                    LiquidGlassSurface({ focusManager.clearFocus(); keyboard?.hide(); activePopup = "time" },
                        modifier = Modifier.onGloballyPositioned {
                            timeBottom = it.positionInRoot().y.toInt() + it.size.height
                        }, height = 44.dp,
                        glassAlpha = .88f, glassColor = pickerValueGray, contentPadding = 12.dp) { _ ->
                        Text("%02d:%02d".format(hour, minute),
                            color = if (activePopup == "time") pickerBlue else colors.textPrimary,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                EntryDivider()
                StepEntryRow("步数") {
                    BasicTextField(
                        value = stepText,
                        onValueChange = { candidate ->
                            if (candidate.length <= 6 && candidate.all(Char::isDigit)) {
                                stepText = candidate
                                error = null
                            }
                        },
                        modifier = Modifier.width(145.dp).height(48.dp).onFocusChanged {
                            if (it.isFocused) {
                                activePopup = null
                                keyboard?.show()
                            }
                        },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = colors.textPrimary, fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                if (stepText.isEmpty()) Text("点击输入", color = colors.textSecondary, fontSize = 15.sp)
                                inner()
                            }
                        },
                    )
                }
            }
            if (error != null) Text(error!!, Modifier.padding(top = 10.dp), color = colors.danger, fontSize = 13.sp)
        }
        if (activePopup != null) {
            BackHandler { activePopup = null }
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { activePopup = null }
            )
            val pickerPosition = Modifier.offset {
                IntOffset(0, ((if (activePopup == "date") dateBottom else timeBottom) - sheetTop + popupGap).coerceAtLeast(0))
            }
            Box((if (activePopup == "time") Modifier.align(Alignment.TopEnd).padding(end = 12.dp).width(230.dp)
                else Modifier.fillMaxWidth().padding(horizontal = 12.dp)).then(pickerPosition)) {
                when (activePopup) {
                    "date" -> HealthDatePicker(date, LocalDate.now(), pickerBackdrop,
                        onPreview = { date = it },
                        onDaySelected = { date = it; activePopup = null })
                    "time" -> HealthTimePicker(hour, minute, pickerBackdrop,
                        { h, m -> hour = h; minute = m })
                }
            }
        }
      }
    }
}

/** 与参考项目 LiquidDialog 相同的背景采样方式；只采样下层表单，避免玻璃采样自身。 */
@Composable
private fun StepPickerGlassPanel(backdrop: Backdrop, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedRectangle(30.dp)
    Column(
        Modifier.fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = pickerGlassEffects,
                highlight = { Highlight.Plain },
                shadow = { Shadow(radius = 24.dp, color = Color.Black.copy(alpha = .16f)) },
                onDrawSurface = { drawRect(Color.White.copy(alpha = .66f)) },
            )
            .border(1.dp, Brush.linearGradient(listOf(Color.White, Color(0xFFB9BBC5).copy(alpha = .65f), Color.White)), shape)
            .padding(20.dp),
        content = content,
    )
}

@Composable
private fun StepEntryRow(label: String, control: @Composable () -> Unit) {
    val colors = LocalWatchColors.current
    Row(Modifier.fillMaxWidth().height(67.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.textSecondary, fontSize = 15.sp)
        control()
    }
}

@Composable
private fun EntryDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE7E7EA)))
}

@Composable
internal fun HealthDatePicker(
    selected: LocalDate,
    latest: LocalDate,
    backdrop: Backdrop,
    onPreview: (LocalDate) -> Unit,
    onDaySelected: (LocalDate) -> Unit,
) {
    val colors = LocalWatchColors.current
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    var yearMonthWheel by remember { mutableStateOf(false) }
    var wheelYear by remember { mutableIntStateOf(month.year) }
    var wheelMonth by remember { mutableIntStateOf(month.monthValue) }
    val updateWheelSelection: (Int, Int) -> Unit = { year, monthValue ->
        val allowedMonth = monthValue.coerceAtMost(if (year == latest.year) latest.monthValue else 12)
        wheelYear = year
        wheelMonth = allowedMonth
        val chosen = YearMonth.of(year, allowedMonth)
        month = chosen
        onPreview(chosen.atDay(minOf(selected.dayOfMonth, chosen.lengthOfMonth())).coerceAtMost(latest))
    }
    StepPickerGlassPanel(backdrop) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.height(48.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (yearMonthWheel) {
                        updateWheelSelection(wheelYear, wheelMonth)
                        yearMonthWheel = false
                    } else {
                        wheelYear = month.year
                        wheelMonth = month.monthValue
                        yearMonthWheel = true
                    }
                }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${month.year}年${month.monthValue}月", color = if (yearMonthWheel) pickerBlue else colors.textPrimary,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Icon(if (yearMonthWheel) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight,
                        contentDescription = if (yearMonthWheel) "收起年份和月份" else "展开年份和月份",
                        tint = pickerBlue, modifier = Modifier.size(25.dp))
                }
                if (!yearMonthWheel) Row {
                    LiquidGlassIconButton(Icons.Rounded.KeyboardArrowLeft, "上个月", { month = month.minusMonths(1) }, size = 42.dp)
                    Spacer(Modifier.width(4.dp))
                    LiquidGlassIconButton(Icons.Rounded.KeyboardArrowRight, "下个月", { month = month.plusMonths(1) },
                        enabled = month < YearMonth.from(latest), size = 42.dp)
                }
            }
            Spacer(Modifier.height(14.dp))
            if (yearMonthWheel) {
                Box(Modifier.fillMaxWidth().height(236.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().height(44.dp)
                        .background(Color(0xFFE5E5EA), RoundedCornerShape(18.dp)))
                    Row(Modifier.width(276.dp), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        NumberWheel((2000..latest.year).toList(), wheelYear, { updateWheelSelection(it, wheelMonth) }, "年",
                            Modifier.weight(1f), showSelectionBar = false)
                        key(wheelYear) {
                            NumberWheel((1..if (wheelYear == latest.year) latest.monthValue else 12).toList(),
                                wheelMonth, { updateWheelSelection(wheelYear, it) }, "月",
                                Modifier.weight(1f), showSelectionBar = false)
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    listOf("日", "一", "二", "三", "四", "五", "六").forEach { name ->
                        Text(name, Modifier.weight(1f), color = colors.textSecondary,
                            textAlign = TextAlign.Center, fontSize = 12.sp)
                    }
                }
                val leading = month.atDay(1).dayOfWeek.value % 7
                val rows = (leading + month.lengthOfMonth() + 6) / 7
                repeat(rows) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        repeat(7) { column ->
                            val number = row * 7 + column - leading + 1
                            val day = if (number in 1..month.lengthOfMonth()) month.atDay(number) else null
                            val enabled = day != null && !day.isAfter(latest)
                            Box(Modifier.weight(1f).height(46.dp), contentAlignment = Alignment.Center) {
                                if (day != null) Box(
                                    Modifier.size(40.dp)
                                        .background(if (day == selected) Color.Black else Color.Transparent, CircleShape)
                                        .clickable(enabled = enabled) { onDaySelected(day) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("$number", color = when {
                                        day == selected -> Color.White
                                        !enabled -> Color(0xFFC7C7CC)
                                        else -> colors.textPrimary
                                    }, fontSize = 16.sp, fontWeight = if (day == selected) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }
    }
}

@Composable
internal fun HealthTimePicker(initialHour: Int, initialMinute: Int, backdrop: Backdrop, onChanged: (Int, Int) -> Unit) {
    var hour by remember { mutableIntStateOf(initialHour) }
    var minute by remember { mutableIntStateOf(initialMinute) }
    StepPickerGlassPanel(backdrop) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(36.dp)
                    .background(Color(0xFFE5E5EA), RoundedCornerShape(18.dp)))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NumberWheel((0..23).toList(), hour, { hour = it; onChanged(hour, minute) }, "时",
                        Modifier.weight(1f), showSelectionBar = false, rowHeight = 36.dp, cyclic = true)
                    NumberWheel((0..59).toList(), minute, { minute = it; onChanged(hour, minute) }, "分",
                        Modifier.weight(1f), showSelectionBar = false, rowHeight = 36.dp, cyclic = true)
                }
            }
    }
}

/** 五行可见、中心行选中；滚动停下时按最靠近中心的项目更新值。 */
@Composable
private fun NumberWheel(
    values: List<Int>, selected: Int, onSelected: (Int) -> Unit,
    suffix: String, modifier: Modifier = Modifier, showSelectionBar: Boolean = true,
    rowHeight: androidx.compose.ui.unit.Dp = 44.dp, cyclic: Boolean = false,
) {
    val colors = LocalWatchColors.current
    val wheelValues = if (cyclic) List(values.size * 5) { values[it % values.size] } else values
    val initialIndex = values.indexOf(selected).coerceAtLeast(0) + if (cyclic) values.size * 2 else 0
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val scrollScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress) {
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val nearest = info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }
            nearest?.index?.let { if (it in wheelValues.indices) onSelected(wheelValues[it]) }
        }
    }
    val info = state.layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val centerIndex = info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: initialIndex
    Box(modifier.height(rowHeight * 5), contentAlignment = Alignment.Center) {
        if (showSelectionBar) Box(Modifier.fillMaxWidth().height(rowHeight)
            .background(Color(0xFFE5E5EA), RoundedCornerShape(18.dp)))
        LazyColumn(
            Modifier.fillMaxWidth().height(rowHeight * 5),
            state = state,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = rowHeight * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(wheelValues) { index, number ->
                val distance = abs(index - centerIndex)
                Box(
                    Modifier.fillMaxWidth().height(rowHeight).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onSelected(number)
                        scrollScope.launch { state.animateScrollToItem(index) }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (suffix == "分" || suffix == "时") "%02d".format(number) else "$number$suffix",
                        color = if (distance == 0) Color.Black else colors.textSecondary.copy(alpha = when (distance) {
                            1 -> .62f; 2 -> .24f; else -> .08f
                        }),
                        fontSize = if (distance == 0) 23.sp else 19.sp,
                        fontWeight = if (distance == 0) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
