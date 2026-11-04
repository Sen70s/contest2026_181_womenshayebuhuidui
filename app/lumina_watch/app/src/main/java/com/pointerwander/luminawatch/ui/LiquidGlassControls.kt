package com.pointerwander.luminawatch.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 可复用的 Backdrop 2.0 玻璃交互层。传入安全的兄弟图层 [backdrop] 时会实时折射页面；
 * 普通内联按钮使用页面色 CanvasBackdrop，避免采样包含自身的页面而造成循环绘制。
 * 数据卡片和列表行不应调用此组件，它们是内容容器，不是按钮。
 */
@Composable
fun LiquidGlassSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    tint: Color? = null,
    height: Dp = 52.dp,
    shape: Shape = Capsule(),
    backdrop: Backdrop? = null,
    glassAlpha: Float = .58f,
    glassColor: Color = Color.White,
    fillAlpha: Float = .92f,
    border: Color = Color.Transparent,
    contentPadding: Dp = 16.dp,
    blurRadius: Dp = 4.dp,
    refractionHeight: Dp = 8.dp,
    refractionAmount: Dp = 16.dp,
    chromaticAberration: Boolean = true,
    content: @Composable RowScope.(Color) -> Unit,
) {
    val colors = LocalWatchColors.current
    val baseColor = colors.background
    val fallback = rememberCanvasBackdrop { drawRect(baseColor) }
    val source = backdrop ?: fallback
    val actionTint = tint ?: colors.accent
    val interactionSource = remember { MutableInteractionSource() }
    val animationScope = rememberCoroutineScope()
    var clickPulse by remember { mutableStateOf(false) }
    val pressed by interactionSource.collectIsPressedAsState()
    val press by animateFloatAsState(
        if ((pressed || clickPulse) && enabled) 1f else 0f,
        spring(dampingRatio = 0.62f, stiffness = 430f),
        label = "liquidGlassButtonPress",
    )
    val contentColor = when {
        !enabled -> colors.textSecondary
        filled -> Color.White
        else -> actionTint
    }
    Row(
        modifier
            .height(height)
            .drawBackdrop(
                backdrop = source,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    lens(refractionHeight.toPx(), refractionAmount.toPx(), chromaticAberration = chromaticAberration)
                },
                shadow = {
                    Shadow(radius = 10.dp, color = Color.Black.copy(alpha = if (filled) .10f else .06f))
                },
                layerBlock = {
                    val scale = lerp(1f, 1f - 3.dp.toPx() / size.height, press)
                    scaleX = scale
                    scaleY = scale
                },
                onDrawSurface = {
                    when {
                        !enabled -> drawRect(colors.surfaceMuted.copy(alpha = .76f))
                        filled -> drawRect(actionTint.copy(alpha = fillAlpha))
                        else -> drawRect(glassColor.copy(alpha = glassAlpha))
                    }
                },
            )
            .border(1.dp, border, shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    if (!clickPulse) animationScope.launch {
                        clickPulse = true
                        delay(45)
                        onClick()
                        clickPulse = false
                    }
                },
            )
            .padding(horizontal = contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(contentColor)
    }
}

@Composable
fun LiquidGlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    filled: Boolean = true,
    tint: Color? = null,
    height: Dp = 52.dp,
    backdrop: Backdrop? = null,
    fillAlpha: Float = .92f,
) {
    LiquidGlassSurface(onClick, modifier, enabled, filled, tint, height,
        backdrop = backdrop, fillAlpha = fillAlpha) { ink ->
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(19.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        }
        Text(text, color = ink, fontSize = 15.sp,
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
fun LiquidGlassIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    tint: Color? = null,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    backdrop: Backdrop? = null,
) {
    val colors = LocalWatchColors.current
    LiquidGlassSurface(onClick, modifier.size(size), enabled, filled, tint, size,
        backdrop = backdrop,
        glassAlpha = .84f,
        border = colors.outline.copy(alpha = .55f),
        contentPadding = 0.dp) { ink ->
        Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun LiquidGlassChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    height: Dp = 44.dp,
) {
    LiquidGlassSurface(onClick, modifier, filled = selected, tint = tint, height = height) { ink ->
        Text(text, color = ink, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}
