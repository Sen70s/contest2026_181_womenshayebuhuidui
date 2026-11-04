package com.pointerwander.luminawatch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 与底部导航同样的按压放大、折射、拖动吸附；文字不参与背景取样，避免重影。 */
@Composable
fun LiquidGlassSlidingSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val colors = LocalWatchColors.current
    // 只捕获轨道（不捕获文字和透镜），拖动时透出真实灰轨道且不会折射出重复文字。
    val pageBackdrop = rememberCanvasBackdrop { drawRect(colors.surface) }
    val trackBackdrop = rememberLayerBackdrop()
    val lensBackdrop = rememberCombinedBackdrop(pageBackdrop, trackBackdrop)
    val scope = rememberCoroutineScope()
    val position = remember { Animatable(selectedIndex.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var dragTarget by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var dragStretch by remember { mutableFloatStateOf(0f) }
    val press by animateFloatAsState(
        if (dragging || switching) 1f else 0f,
        spring(dampingRatio = .63f, stiffness = 480f), label = "periodPress",
    )
    val stretch by animateFloatAsState(
        dragStretch, spring(dampingRatio = .55f, stiffness = 450f), label = "periodStretch",
    )
    LaunchedEffect(selectedIndex) {
        dragTarget = selectedIndex.toFloat()
        if (position.targetValue != selectedIndex.toFloat()) switching = true
        position.animateTo(selectedIndex.toFloat(), spring(dampingRatio = .68f, stiffness = 500f))
        switching = false
    }

    BoxWithConstraints(modifier.fillMaxWidth().height(56.dp)) {
        val slotWidth = (maxWidth - 6.dp) / options.size
        val slotPx = with(LocalDensity.current) { slotWidth.toPx() }

        // 可见轨道与取样层分开，避免离屏采样层的矩形边界露在透镜后面。
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(34.dp)
            .background(Color(0xFFEDEDEF), CircleShape))
        Box(Modifier.fillMaxWidth().height(56.dp).alpha(0f).layerBackdrop(trackBackdrop)) {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(34.dp)
                .background(Color(0xFFEDEDEF), CircleShape))
        }
        Box(
            Modifier.padding(start = 3.dp, top = 14.dp)
                .graphicsLayer { translationX = position.value * slotPx }
                .slidingGlassLens(
                    backdrop = lensBackdrop,
                    press = press,
                    stretch = stretch,
                    surfaceColor = Color.White,
                    restingAlpha = .98f,
                    activeAlpha = 0f,
                    activeScaleX = 1.30f,
                    activeScaleY = 1.82f,
                    shape = CircleShape,
                )
                .height(28.dp).fillMaxWidth(1f / options.size),
        )
        // 文字始终固定在五个槽位；透明透镜只在其下方移动。
        Row(Modifier.fillMaxWidth().height(56.dp)
            .pointerInput(slotPx, selectedIndex) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; dragTarget = position.value },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragTarget = (dragTarget + amount / slotPx)
                            .coerceIn(0f, options.lastIndex.toFloat())
                        dragStretch = (amount / slotPx * 4f).coerceIn(-.2f, .2f)
                        scope.launch {
                            position.animateTo(dragTarget, spring(dampingRatio = 1f, stiffness = 1000f))
                        }
                    },
                    onDragEnd = {
                        dragging = false; switching = true; dragStretch = 0f
                        val next = dragTarget.roundToInt().coerceIn(options.indices)
                        if (next == selectedIndex) {
                            scope.launch {
                                position.animateTo(next.toFloat(), spring(dampingRatio = .65f, stiffness = 500f))
                                switching = false
                            }
                        } else onSelected(next)
                    },
                    onDragCancel = {
                        dragging = false; switching = true; dragStretch = 0f
                        scope.launch {
                            position.animateTo(selectedIndex.toFloat(), spring(dampingRatio = .65f, stiffness = 500f))
                            switching = false
                        }
                    },
                )
            }.padding(horizontal = 3.dp)) {
            options.forEachIndexed { index, label ->
                Box(
                    Modifier.weight(1f).height(56.dp)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                        ) { onSelected(index) }
                        .semantics { selected = index == selectedIndex },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = colors.textPrimary, fontSize = 15.sp,
                        fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1)
                }
            }
        }
    }
}
