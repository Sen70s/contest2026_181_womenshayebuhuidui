package com.pointerwander.luminawatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * iOS 风格的滚轮选择器：中间为选中项，上下拖动 + 惯性 + 吸附，
 * 远离中心的项缩小、变淡；中心项变化时给一次触觉反馈。
 */
@Composable
fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 44.dp,
    visibleCount: Int = 5,
    showSelectionBand: Boolean = true,
) {
    val colors = LocalWatchColors.current
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = state)
    val haptic = LocalHapticFeedback.current
    val half = visibleCount / 2

    val centerIndex by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .minByOrNull { abs(it.offset + it.size / 2 - center) }
                ?.index
                ?: selectedIndex
        }
    }

    LaunchedEffect(centerIndex) {
        if (state.isScrollInProgress) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        onSelected(centerIndex)
    }

    Box(modifier.height(itemHeight * visibleCount)) {
        if (showSelectionBand) {
            // 中间选中带
            Box(
                Modifier.align(Alignment.Center)
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceMuted.copy(alpha = .55f)),
            )
        }
        LazyColumn(
            state = state,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = itemHeight * half),
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(items, key = { index, _ -> index }) { index, value ->
                val distance = abs(index - centerIndex)
                val alpha = (1f - distance * .18f).coerceIn(.28f, 1f)
                val scale = (1f - distance * .06f).coerceIn(.75f, 1f)
                Box(
                    Modifier.height(itemHeight).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        value,
                        color = colors.textPrimary.copy(alpha = alpha),
                        fontSize = if (distance == 0) 30.sp else 24.sp,
                        fontWeight = if (distance == 0) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
            }
        }
    }
}

/** 24 小时制时间滚轮：左小时、右分钟，中间固定冒号。 */
@Composable
fun TimeWheelPicker(
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 44.dp,
    visibleCount: Int = 5,
) {
    val colors = LocalWatchColors.current
    val hours = remember { (0..23).map { "%02d".format(it) } }
    val minutes = remember { (0..59).map { "%02d".format(it) } }
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier.align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceMuted.copy(alpha = .55f)),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelPicker(
                items = hours,
                selectedIndex = hour.coerceIn(0, 23),
                onSelected = onHourChange,
                modifier = Modifier.weight(1f),
                itemHeight = itemHeight,
                visibleCount = visibleCount,
                showSelectionBand = false,
            )
            Text(
                ":",
                color = colors.textPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
            )
            WheelPicker(
                items = minutes,
                selectedIndex = minute.coerceIn(0, 59),
                onSelected = onMinuteChange,
                modifier = Modifier.weight(1f),
                itemHeight = itemHeight,
                visibleCount = visibleCount,
                showSelectionBand = false,
            )
        }
    }
}
