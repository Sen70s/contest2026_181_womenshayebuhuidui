package com.pointerwander.luminawatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.pointerwander.luminawatch.ui.liquid.LiquidToggle

/**
 * 关怀规则开关：直接使用从 WanderIsland 移植的完整 LiquidToggle。
 * 轨道自采样 + 滑块玻璃 + DampedDragAnimation 的按压/拖拽/回弹。
 */
@Composable
fun CareGlassToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWatchColors.current
    val backdrop = rememberCanvasBackdrop { drawRect(colors.surface) }
    LiquidToggle(
        selected = { checked },
        onSelect = onCheckedChange,
        backdrop = backdrop,
        modifier = modifier,
        accentColor = androidx.compose.ui.graphics.Color(0xFF34C759),
    )
}