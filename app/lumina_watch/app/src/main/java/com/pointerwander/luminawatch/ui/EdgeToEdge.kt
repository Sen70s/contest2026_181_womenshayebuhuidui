package com.pointerwander.luminawatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全屏页（edge-to-edge）的三个小工具。
 *
 * 约定：页面背景从屏幕最顶端 y=0 铺满（含状态栏后面的区域），
 * 只把**内容**整体下移一个状态栏高度。这样状态栏与页面是同一层，
 * 不会再出现"上面一条壳层色带、下面一块页面"的分层。
 */

/** 状态栏高度，用于内容避让（背景不避让）。 */
@Composable
fun statusBarHeight(): Dp = with(LocalDensity.current) {
    WindowInsets.statusBars.getTop(this).toDp()
}

/**
 * 顶部渐隐遮罩。背景铺到 y=0 之后，滚动内容会从状态栏后面经过，
 * 这条与背景同色的渐变把内容收口，避免在状态栏下沿出现硬切边。
 */
@Composable
fun TopScrim(
    color: Color,
    modifier: Modifier = Modifier,
    extraHeight: Dp = 0.dp,
    solidFraction: Float = .62f,
) {
    val inset = statusBarHeight()
    Box(
        modifier.fillMaxWidth().height(inset + extraHeight).background(
            Brush.verticalGradient(
                0f to color,
                solidFraction.coerceIn(0f, .98f) to color,
                1f to color.copy(alpha = 0f),
            ),
        ),
    )
}
