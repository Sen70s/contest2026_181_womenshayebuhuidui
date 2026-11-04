package com.pointerwander.luminawatch.ui

import androidx.compose.foundation.Image
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput


import androidx.compose.ui.unit.Velocity
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.R
import com.pointerwander.luminawatch.data.Metric

@Composable
fun PageColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    spacing: Dp = 12.dp,
    bounce: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!bounce) {
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(scrollState)
                .padding(horizontal = 20.dp).padding(bottom = 26.dp + LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        return
    }
    Box(
        modifier = modifier.fillMaxSize().bounceWhenContentFits(scrollState),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                .padding(horizontal = 20.dp).padding(bottom = 26.dp + LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/** 内容不足一屏时也能拖动并回弹；可滚动时交给正常的滚动容器处理。 */
@Composable
fun Modifier.bounceWhenContentFits(scrollState: ScrollState): Modifier {
    val reboundOffset = remember { Animatable(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canScroll = scrollState.maxValue > 0
    return this
            .pointerInput(canScroll) {
                if (canScroll) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var total = 0f
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dy = change.position.y - change.previousPosition.y
                        total += dy
                        if (!dragging && kotlin.math.abs(total) > viewConfiguration.touchSlop) {
                            dragging = true
                            dragOffset = reboundOffset.value
                            isDragging = true
                            scope.launch { reboundOffset.stop() }
                        }
                        if (dragging) {
                            change.consume()
                            dragOffset = (dragOffset + dy * .55f).coerceIn(-160f, 160f)
                        }
                    }
                    if (dragging) {
                        val releaseOffset = dragOffset
                        scope.launch {
                            reboundOffset.snapTo(releaseOffset)
                            isDragging = false
                            reboundOffset.animateTo(0f, spring(dampingRatio = .62f, stiffness = 260f))
                        }
                    }
                }
            }
            .graphicsLayer { translationY = if (isDragging) dragOffset else reboundOffset.value }
}

/** 底栏覆盖在内容上时，页面需要预留的底部空间。 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }

@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    accent: Color? = null,
) {
    val colors = LocalWatchColors.current
    val accentColor = accent ?: colors.accent
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            if (onBack != null) {
                LiquidGlassIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    label = "返回",
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart),
                    tint = accentColor,
                )
            }
            Text(title, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp))
            if (actions != null) Box(Modifier.align(Alignment.CenterEnd)) { actions() }
        }
        if (subtitle != null) {
            Text(subtitle, color = colors.textSecondary, fontSize = 12.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun CircleIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    LiquidGlassIconButton(icon, label, onClick)
}

@Composable
fun DarkCircleIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    LiquidGlassIconButton(icon, label, onClick)
}

@Composable
fun DemoBadge(text: String = "") { /* 旧页面兼容入口：产品界面不显示开发状态。 */ }

@Composable
fun SourceTag(text: String, color: Color? = null) {
    val colors = LocalWatchColors.current
    Text(
        text,
        color = color ?: colors.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background((color ?: colors.textSecondary).copy(alpha = .10f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // 玻璃只用于导航/分段等交互层；承载文字与数据的卡片使用实体表面，保证可读性。
    SurfaceCard(modifier, content)
}

/** Apple Watch 手机 App 风格的连续实体分组，不使用玻璃或悬浮阴影。 */
@Composable
fun FrostedCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalWatchColors.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier.fillMaxWidth()
            .clip(shape)
            .background(colors.groupedSurface)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    FrostedCard(modifier, content)
}

@Composable
private fun PlainSurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    FrostedCard(modifier, content)
}

@Composable
fun SectionTitle(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    val colors = LocalWatchColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (trailing != null && onTrailing != null) {
            LiquidGlassButton(trailing, onTrailing, height = 36.dp, filled = false)
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    tint: Color? = null,
    fillAlpha: Float = .92f,
) {
    LiquidGlassButton(text, onClick, modifier, icon, enabled, filled = true, tint = tint,
        fillAlpha = fillAlpha)
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    LiquidGlassButton(text, onClick, modifier, icon, enabled,
        filled = false, tint = tint, height = 50.dp)
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalWatchColors.current
    SurfaceCard {
        Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(body, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 21.sp)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            LiquidGlassButton(actionLabel, onAction, height = 38.dp, filled = false)
        }
    }
}

@Composable
fun ActionRow(
    title: String,
    detail: String? = null,
    icon: ImageVector,
    onClick: () -> Unit,
    trailing: String? = null,
    iconBackground: Color? = null,
) {
    val colors = LocalWatchColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .motionClickable(onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(
                if (iconBackground != null) Brush.verticalGradient(
                    listOf(iconBackground.copy(alpha = .82f), iconBackground),
                ) else Brush.verticalGradient(listOf(colors.accentSoft, colors.accentSoft)),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (iconBackground != null) Color.White else colors.accent,
                modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (detail != null) {
                Text(detail, color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Text(trailing, color = colors.textSecondary, fontSize = 12.sp)
        } else {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = colors.textSecondary, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    detail: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalWatchColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (detail != null) {
                Text(detail, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        WatchSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun WatchSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalWatchColors.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.accent,
            checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.surface,
            uncheckedTrackColor = colors.surfaceMuted,
            uncheckedBorderColor = colors.outline,
        ),
    )
}

@Composable
fun MetricTile(
    metric: Metric,
    value: String,
    unit: String,
    caption: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWatchColors.current
    FrostedCard(modifier = modifier.motionClickable(onClick)) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (metric == Metric.HEART_RATE) Icons.Rounded.FavoriteBorder else Icons.Rounded.Bloodtype,
                null,
                tint = Color.White,
                modifier = Modifier.size(25.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(if (metric == Metric.HEART_RATE) "心率" else "血氧", color = colors.textSecondary, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = colors.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(3.dp))
            Text(unit, color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        Text(caption, color = colors.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CloudImage(modifier: Modifier = Modifier, avatarId: String = "wei_xiao") {
    val avatar = CloudAvatars.find(avatarId)
    Image(
        painter = painterResource(avatar.drawable),
        contentDescription = "小云形象：${avatar.label}",
        modifier = modifier,
    )
}

@Composable
fun InfoRow(label: String, value: String) {
    val colors = LocalWatchColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
