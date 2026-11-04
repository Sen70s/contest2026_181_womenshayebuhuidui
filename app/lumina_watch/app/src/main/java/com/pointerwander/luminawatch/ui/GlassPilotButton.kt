package com.pointerwander.luminawatch.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop

/** 健康页入口复用统一玻璃组件，并传入页面兄弟图层获得实时背景折射。 */
@Composable
fun AllWatchesGlassButton(
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiquidGlassSurface(
        onClick = onClick,
        modifier = modifier.width(150.dp),
        height = 36.dp,
        tint = Color(0xFF0B0B0F),
        backdrop = backdrop,
        filled = true,
        fillAlpha = .88f,
        border = Color.White.copy(alpha = .55f),
        blurRadius = 8.dp,
        refractionHeight = 7.dp,
        refractionAmount = 12.dp,
    ) { ink ->
        Text("所有 Watch", color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
