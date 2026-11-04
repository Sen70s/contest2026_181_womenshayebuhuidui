package com.pointerwander.luminawatch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule

/** 底部导航和分段时间选择器共用的可按压、可拉伸玻璃透镜。 */
@Composable
fun Modifier.slidingGlassLens(
    backdrop: Backdrop,
    press: Float,
    stretch: Float,
    surfaceColor: Color,
    restingAlpha: Float,
    activeAlpha: Float,
    activeScaleX: Float = 1.30f,
    activeScaleY: Float = 1.30f,
    shape: Shape = Capsule(),
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        lens(6.dp.toPx() * press, 8.dp.toPx() * press, chromaticAberration = true)
    },
    highlight = { Highlight.Default.copy(alpha = press) },
    shadow = { Shadow(radius = 14.dp, alpha = press) },
    innerShadow = { InnerShadow(radius = 8.dp, alpha = press) },
    layerBlock = {
        scaleX = lerp(1f, activeScaleX, press) / (1f - stretch * .75f)
        scaleY = lerp(1f, activeScaleY, press) * (1f - stretch * .25f)
    },
    onDrawSurface = {
        drawRect(surfaceColor.copy(alpha = lerp(restingAlpha, activeAlpha, press)))
    },
)
