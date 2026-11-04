package com.pointerwander.luminawatch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow

/** Scrollable page and sticky header share one sibling backdrop, so page content can pass beneath the glass. */
@Composable
fun LiquidGlassHeaderPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bounce: Boolean = false,
    backgroundBrush: Brush? = null,
    topInset: Dp = Dp.Unspecified,
    contentTopOffset: androidx.compose.ui.unit.Dp = 84.dp,
    actions: @Composable RowScope.(Backdrop) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalWatchColors.current
    val resolvedTopInset = if (topInset == Dp.Unspecified) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    } else topInset
    val headerBackdrop = rememberLayerBackdrop()
    Box(
        modifier.fillMaxSize().background(colors.groupedSurface)
            .then(if (backgroundBrush != null) Modifier.background(backgroundBrush) else Modifier),
    ) {
        Box(Modifier.fillMaxSize().layerBackdrop(headerBackdrop)) {
            PageColumn(bounce = bounce) {
                Spacer(Modifier.height(contentTopOffset + resolvedTopInset))
                content()
            }
        }
        LiquidGlassStickyHeader(
            title = title,
            onBack = onBack,
            backdrop = headerBackdrop,
            topInset = resolvedTopInset,
            modifier = Modifier.align(Alignment.TopCenter),
            actions = { actions(headerBackdrop) },
        )
    }
}

/**
 * 可复用的吸顶玻璃遮罩层。
 *
 * 与之前的胶囊不同：这里是一条从屏幕左到右铺满的玻璃层，内容从下方滚过；
 * [backdrop] 必须是页面内容的兄弟图层，标题栏本身不进入采样。
 */
@Composable
fun LiquidGlassStickyHeader(
    title: String,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    backdrop: Backdrop? = null,
    shape: Shape = RoundedCornerShape(1.dp),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalWatchColors.current
    val fallback = rememberCanvasBackdrop { drawRect(colors.background) }
    val source = backdrop ?: fallback
    val ink = Color(0xFF0B0B0F)

    Box(modifier.fillMaxWidth().height(84.dp + topInset)) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.White,
                            .86f to Color.White,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            Box(Modifier.fillMaxSize().drawBackdrop(
                backdrop = source,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(18.dp.toPx())
                    lens(1.dp.toPx(), 2.dp.toPx(), chromaticAberration = false)
                },
                shadow = { Shadow(radius = 12.dp, color = Color.Black.copy(alpha = .025f)) },
                onDrawSurface = {
                    drawRect(
                        Brush.verticalGradient(
                            0f to colors.surface.copy(alpha = .62f),
                            1f to colors.surface.copy(alpha = .38f),
                        )
                    )
                },
            ))
        }
        Box(Modifier.fillMaxWidth().padding(top = topInset).height(66.dp)
            .padding(horizontal = 16.dp)) {
            if (onBack != null) {
                TopBarGlassCircle(onBack, source, Modifier.align(Alignment.CenterStart)) {
                    if (leadingIcon == null) BackChevron(Modifier.size(23.dp), ink)
                    else Icon(leadingIcon, contentDescription = title, tint = ink,
                        modifier = Modifier.size(23.dp))
                }
            }
            Box(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 72.dp),
                contentAlignment = Alignment.Center) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        title,
                        color = ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            color = colors.textSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically,
                content = actions)
        }
    }
}

@Composable
fun LiquidGlassTopBarIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val ink = LocalWatchColors.current.textPrimary
    TopBarGlassCircle(onClick, backdrop, modifier) {
        Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun TopBarGlassCircle(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalWatchColors.current
    LiquidGlassSurface(
        onClick = onClick,
        modifier = modifier.size(52.dp),
        tint = colors.textPrimary,
        height = 52.dp,
        backdrop = backdrop,
        glassAlpha = .35f,
        border = Color(0xFFC5C7CE).copy(alpha = .75f),
        contentPadding = 0.dp,
        blurRadius = 8.dp,
        refractionHeight = 6.dp,
        refractionAmount = 8.dp,
        chromaticAberration = false,
    ) { content() }
}

@Composable
private fun BackChevron(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        drawLine(
            color,
            Offset(size.width * .68f, size.height * .14f),
            Offset(size.width * .30f, size.height * .50f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width * .30f, size.height * .50f),
            Offset(size.width * .68f, size.height * .86f),
            stroke,
            StrokeCap.Round,
        )
    }
}
