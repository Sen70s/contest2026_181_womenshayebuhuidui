package com.pointerwander.luminawatch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.pointerwander.luminawatch.app.Tab
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * WanderIsland 底栏的三层滑动方式：可见玻璃、染色内容副本、可拖动并放大的玻璃透镜。
 * 保留小云原有的三个 Tab 和紧凑尺寸；只有页面内容进入 [backdrop]，底栏不会采样自己。
 */
@Composable
fun WatchBottomBar(
    selected: Tab,
    dark: Boolean,
    backdrop: Backdrop,
    onSelect: (Tab) -> Unit,
    compact: Boolean = false,
) {
    val accent = if (dark) Color(0xFFFFB14A) else Color(0xFF007AFF)
    // 参考浅色健康页：白色玻璃胶囊仍透出背景，选中项是独立的浅灰透镜。
    val containerColor = if (dark) Color(0xFF121212).copy(alpha = 0.4f)
        else Color.White.copy(alpha = 0.4f)
    val tabsBackdrop = rememberLayerBackdrop()
    val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var isDragging by remember { mutableStateOf(false) }
    var isSwitching by remember { mutableStateOf(false) }
    var dragTarget by remember { mutableFloatStateOf(selected.ordinal.toFloat()) }
    var dragStretch by remember { mutableFloatStateOf(0f) }
    var previousIndex by remember { mutableIntStateOf(selected.ordinal) }
    val position = remember { Animatable(selected.ordinal.toFloat()) }
    val pressProgress by animateFloatAsState(
        if (isPressed || isDragging || isSwitching) 1f else 0f,
        spring(dampingRatio = 0.63f, stiffness = 480f),
        label = "bottomBarPress",
    )
    val stretch by animateFloatAsState(
        dragStretch,
        spring(dampingRatio = 0.55f, stiffness = 450f),
        label = "bottomBarStretch",
    )

    LaunchedEffect(selected) {
        dragTarget = selected.ordinal.toFloat()
        if (previousIndex != selected.ordinal) {
            isSwitching = true
            previousIndex = selected.ordinal
        }
        position.animateTo(
            selected.ordinal.toFloat(),
            spring(dampingRatio = 0.68f, stiffness = 500f),
        )
        isSwitching = false
    }

    val barHeight = if (compact) 48.dp else 64.dp
    val lensHeight = if (compact) 42.dp else 58.dp
    BoxWithConstraints(
        Modifier.width(280.dp).height(barHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        val tabWidth = with(LocalDensity.current) { (maxWidth - 6.dp).toPx() / Tab.entries.size }
        val pressState = androidx.compose.runtime.rememberUpdatedState(pressProgress)
        val stretchState = androidx.compose.runtime.rememberUpdatedState(stretch)
        val containerEffects: BackdropEffectScope.() -> Unit = remember {
            { vibrancy(); blur(4.dp.toPx()); lens(8.dp.toPx(), 8.dp.toPx()) }
        }
        val containerLayer: GraphicsLayerScope.() -> Unit = remember {
            {
                val scale = lerp(1f, 1.035f, pressState.value)
                scaleX = scale
                scaleY = scale
            }
        }

        // 1. 真正可见的背景胶囊和普通 Tab 内容。
        Row(
            Modifier.fillMaxWidth().height(barHeight)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = containerEffects,
                    shadow = { Shadow(radius = 12.dp, color = Color.Black.copy(alpha = 0.07f)) },
                    layerBlock = containerLayer,
                    onDrawSurface = { drawRect(containerColor) },
                )
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomTabContents(selected, dark, onSelect, previewScale = 1f, preview = false,
                compact = compact)
        }

        // 2. 隐形染色副本；选中透镜从这里采样放大的图标和文字。
        Row(
            Modifier.clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = containerEffects,
                    onDrawSurface = { drawRect(if (dark) Color.Black.copy(alpha = 0.5f)
                        else Color.White.copy(alpha = 0.5f)) },
                )
                .height(lensHeight).fillMaxWidth()
                .padding(horizontal = 3.dp)
                .graphicsLayer(colorFilter = ColorFilter.tint(accent)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomTabContents(selected, dark, onSelect,
                previewScale = lerp(1f, 1.18f, pressProgress), preview = true,
                compact = compact)
        }

        // 3. 玻璃透镜在三个位置间移动；按住它横拖，松手吸附并切换页面。
        Box(
            Modifier.padding(horizontal = 3.dp)
                .graphicsLayer { translationX = position.value * tabWidth }
                .pointerInput(tabWidth, selected) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            isDragging = true
                            dragTarget = position.value
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragTarget = (dragTarget + amount / tabWidth)
                                .coerceIn(0f, (Tab.entries.size - 1).toFloat())
                            // 与 Wander 一样让玻璃选择器阻尼追随手指，而非瞬移。
                            dragStretch = (amount / tabWidth * 4f).coerceIn(-0.2f, 0.2f)
                            scope.launch {
                                position.animateTo(dragTarget,
                                    spring(dampingRatio = 1f, stiffness = 1000f))
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            isSwitching = true
                            dragStretch = 0f
                            val next = Tab.entries[dragTarget.roundToInt()
                                .coerceIn(0, Tab.entries.lastIndex)]
                            if (next == selected) {
                                scope.launch {
                                    position.animateTo(next.ordinal.toFloat(),
                                        spring(dampingRatio = 0.65f, stiffness = 500f))
                                    isSwitching = false
                                }
                            } else {
                                onSelect(next)
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            isSwitching = true
                            dragStretch = 0f
                            scope.launch {
                                position.animateTo(selected.ordinal.toFloat(),
                                    spring(dampingRatio = 0.65f, stiffness = 500f))
                                isSwitching = false
                            }
                        },
                    )
                }
                .slidingGlassLens(
                    backdrop = combinedBackdrop,
                    press = pressState.value,
                    stretch = stretchState.value,
                    surfaceColor = if (dark) Color.White else Color.Black,
                    restingAlpha = if (dark) .12f else .095f,
                    activeAlpha = 0f,
                )
                .clickable(interactionSource = interactionSource, indication = null,
                    role = Role.Tab) { onSelect(selected) }
                .semantics { this.selected = true; contentDescription = selected.label }
                .height(lensHeight).fillMaxWidth(1f / Tab.entries.size),
        )
    }
}

@Composable
private fun RowScope.BottomTabContents(
    selected: Tab,
    dark: Boolean,
    onSelect: (Tab) -> Unit,
    previewScale: Float,
    preview: Boolean,
    compact: Boolean,
) {
    val ink = if (dark) Color(0xFFE4E4E7) else Color(0xFF0B0B0F)
    Tab.entries.forEach { tab ->
        val itemModifier = Modifier.weight(1f).fillMaxHeight()
            .clip(RoundedCornerShape(29.dp))
            .then(if (preview) Modifier else Modifier.clickable(role = Role.Tab) { onSelect(tab) })
            .semantics { this.selected = selected == tab }
        Column(
            itemModifier.graphicsLayer {
                scaleX = previewScale
                scaleY = previewScale
            },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(tab.icon, contentDescription = null, tint = ink, modifier = Modifier.size(23.dp))
            if (!compact) {
                Spacer(Modifier.height(2.dp))
                Text(tab.label, fontSize = 10.sp, lineHeight = 12.sp,
                    fontWeight = if (tab == selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = ink, maxLines = 1)
            }
        }
    }
}
