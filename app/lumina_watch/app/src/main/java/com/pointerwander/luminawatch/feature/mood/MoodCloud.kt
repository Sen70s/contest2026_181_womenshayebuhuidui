package com.pointerwander.luminawatch.feature.mood

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.sin

/** 情绪视觉的统一颜色映射；列表、月历和记录页共用。 */
internal fun moodPaletteColor(value: Float, palette: List<Color>): Color {
    val position = value.coerceIn(0f, 1f) * 7f
    val first = position.toInt().coerceAtMost(6)
    return lerp(palette[first], palette[first + 1], position - first)
}

internal fun moodColor(value: Float): Color {
    return moodPaletteColor(value, listOf(
        Color(0xFF8664C6), Color(0xFF7289DA), Color(0xFF65A7DB), Color(0xFF71C1D4),
        Color(0xFFA6D1AB), Color(0xFFF4D168), Color(0xFFFFB563), Color(0xFFFF956A),
    ))
}

internal fun moodBackground(value: Float): Color {
    val v = value.coerceIn(0f, 1f)
    return when {
        v < .35f -> lerp(Color(0xFFDBE8FF), Color(0xFFDBF4F3), v / .35f)
        v < .7f -> lerp(Color(0xFFDBF4F3), Color(0xFFEEF6BE), (v - .35f) / .35f)
        else -> lerp(Color(0xFFEEF6BE), Color(0xFFFFC995), (v - .7f) / .3f)
    }
}

private fun cloudPath(center: Offset, radius: Float, value: Float, phase: Float = 0f): Path {
    val v = value.coerceIn(0f, 1f)
    val spread = .94f + v * .10f + sin(phase) * .025f
    val lift = (v - .5f) * .14f + sin(phase + .8f) * .035f
    val droop = (1f - v) * .10f
    val float = sin(phase + 1.7f) * .025f
    fun x(factor: Float) = center.x + radius * factor * spread
    fun y(factor: Float) = center.y + radius * (factor + float)
    return Path().apply {
        moveTo(x(-.72f), y(.48f + droop))
        cubicTo(x(-1.05f), y(.43f), x(-1.16f), y(.03f), x(-.88f), y(-.18f))
        cubicTo(x(-.90f), y(-.43f), x(-.69f), y(-.63f - lift), x(-.43f), y(-.54f - lift))
        cubicTo(x(-.26f), y(-.85f - lift), x(.04f), y(-.90f - lift), x(.22f), y(-.68f - lift))
        cubicTo(x(.48f), y(-.82f - lift), x(.75f), y(-.58f - lift), x(.73f), y(-.31f))
        cubicTo(x(1.10f), y(-.26f), x(1.18f), y(.18f), x(.89f), y(.40f))
        cubicTo(x(.70f), y(.59f + droop), x(.39f), y(.53f + droop), x(.16f), y(.59f + droop))
        cubicTo(x(-.14f), y(.67f + droop), x(-.48f), y(.59f + droop), x(-.72f), y(.48f + droop))
        close()
    }
}

/** 小图标保持原有云朵；记录流程使用柔和光晕和随情绪变化的表情。 */
@Composable
fun MoodCloud(
    value: Float,
    modifier: Modifier = Modifier,
    breathing: Boolean = true,
    expressive: Boolean = false,
) {
    val animated by animateFloatAsState(value.coerceIn(0f, 1f), tween(220), label = "情绪云变化")
    val phase = if (breathing) {
        val motion = rememberInfiniteTransition(label = "云朵呼吸")
        val p by motion.animateFloat(
            initialValue = 0f,
            targetValue = 6.283185f,
            animationSpec = infiniteRepeatable(tween(4400, easing = LinearEasing), RepeatMode.Restart),
            label = "云朵光泽流动",
        )
        p
    } else 0f
    val base = moodColor(animated)
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width * .44f, size.height * .48f)
        if (expressive && radius >= 16.dp.toPx()) {
            val floatY = sin(phase) * radius * .015f
            val faceCenter = Offset(center.x, center.y + floatY)
            val blobColor = moodPaletteColor(animated, listOf(
                Color(0xFF6094D5), Color(0xFF709EE0), Color(0xFF7EAAE8), Color(0xFF89B5EF),
                Color(0xFF97BFF0), Color(0xFFFFD777), Color(0xFFFFBE65), Color(0xFFFFA558),
            ))
            drawCircle(
                brush = Brush.radialGradient(
                    0f to blobColor.copy(alpha = .36f),
                    .38f to blobColor.copy(alpha = .28f),
                    .70f to blobColor.copy(alpha = .14f),
                    1f to blobColor.copy(alpha = 0f),
                    center = faceCenter,
                    radius = radius * 1.14f,
                ),
                radius = radius * 1.14f,
                center = faceCenter,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = .18f), Color.Transparent),
                    center = Offset(center.x - radius * .20f, faceCenter.y - radius * .22f),
                    radius = radius * .75f,
                ),
                radius = radius * .75f,
                center = Offset(center.x - radius * .20f, faceCenter.y - radius * .22f),
            )

            // 光晕只作为云朵的背景，保留原有的小云轮廓和层次。
            drawPath(
                cloudPath(Offset(center.x, center.y + radius * .055f), radius * 1.025f, animated, phase),
                blobColor.copy(alpha = .16f),
            )
            for (layer in 0..2) {
                val scale = 1f - layer * .16f
                val path = cloudPath(center, radius * scale, animated, phase + layer * .17f)
                drawPath(
                    path,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = .94f - layer * .05f),
                            lerp(blobColor, Color.White, .82f).copy(alpha = .91f),
                            lerp(blobColor, Color.White, .54f).copy(alpha = .80f - layer * .05f),
                            Color.White.copy(alpha = .62f),
                        ),
                        start = Offset(center.x - radius, center.y - radius),
                        end = Offset(center.x + radius, center.y + radius),
                    ),
                )
                drawPath(
                    path,
                    Color.White.copy(alpha = .76f - layer * .09f),
                    style = Stroke(width = 2.1f - layer * .2f),
                )
            }

            // 使用与滑块文案相同的八档索引，避免 .5 显示“有点愉快”却画成平嘴。
            val state = moodIndex(animated)
            val eyeY = faceCenter.y - radius * .10f
            val mouthY = faceCenter.y + radius * .20f
            val leftX = center.x - radius * .32f
            val rightX = center.x + radius * .32f
            run {
                val index = state
                val alpha = 1f
                val eyeColor = Color(0xFF211C1B).copy(alpha = alpha)
                val mouthColor = if (index <= 3) Color(0xFF2E609D).copy(alpha = alpha)
                    else Color(0xFFB36C2D).copy(alpha = alpha)
                listOf(leftX, rightX).forEachIndexed { side, x ->
                    if (index == 0) {
                        val eyelid = Path().apply {
                            moveTo(x - radius * .09f, eyeY)
                            quadraticTo(x, eyeY + radius * .075f, x + radius * .09f, eyeY)
                        }
                        drawPath(eyelid, eyeColor,
                            style = Stroke(radius * .042f, cap = StrokeCap.Round))
                    } else {
                        // 豆豆眼始终可见；不用闭眼动画，避免停在任一情绪时像没有眼睛。
                        drawOval(eyeColor,
                            topLeft = Offset(x - radius * .071f, eyeY - radius * .085f),
                            size = Size(radius * .142f, radius * .18f))
                        drawCircle(Color.White.copy(alpha = .92f), radius * .018f,
                            Offset(x - radius * .026f, eyeY - radius * .036f))
                    }
                    if (index in 1..3) {
                        val browY = eyeY - radius * .15f
                        val brow = Path().apply {
                            moveTo(x - radius * .09f, browY + if (side == 0) radius * .035f else 0f)
                            quadraticTo(x, browY - radius * .045f,
                                x + radius * .09f, browY + if (side == 1) radius * .035f else 0f)
                        }
                        drawPath(brow, eyeColor.copy(alpha = .8f),
                            style = Stroke(radius * .026f, cap = StrokeCap.Round))
                    }
                }
                if (index >= 4) {
                    listOf(center.x - radius * .50f, center.x + radius * .36f).forEach { x ->
                        drawOval(Color(0xFFFFA792).copy(alpha = if (index == 4) .24f else .40f),
                            topLeft = Offset(x, mouthY - radius * .09f),
                            size = Size(radius * .15f, radius * .09f))
                    }
                }
                if (index >= 5) {
                    val openSmile = Path().apply {
                        moveTo(center.x - radius * .17f, mouthY)
                        quadraticTo(center.x, mouthY + radius * .045f,
                            center.x + radius * .17f, mouthY)
                        cubicTo(center.x + radius * .12f, mouthY + radius * .20f,
                            center.x - radius * .12f, mouthY + radius * .20f,
                            center.x - radius * .17f, mouthY)
                        close()
                    }
                    drawPath(openSmile, Color(0xFF8B382B))
                    drawOval(Color(0xFFF28F74),
                        topLeft = Offset(center.x - radius * .09f, mouthY + radius * .10f),
                        size = Size(radius * .18f, radius * .07f))
                } else {
                    val mouth = Path().apply {
                        moveTo(center.x - radius * .17f, mouthY)
                        when (index) {
                            0 -> {
                                lineTo(center.x - radius * .105f, mouthY - radius * .045f)
                                lineTo(center.x - radius * .045f, mouthY + radius * .015f)
                                lineTo(center.x + radius * .015f, mouthY - radius * .035f)
                                lineTo(center.x + radius * .075f, mouthY + radius * .015f)
                                lineTo(center.x + radius * .17f, mouthY)
                            }
                            1 -> quadraticTo(center.x, mouthY - radius * .16f,
                                center.x + radius * .17f, mouthY)
                            2 -> quadraticTo(center.x, mouthY - radius * .025f,
                                center.x + radius * .17f, mouthY)
                            3 -> quadraticTo(center.x, mouthY - radius * .015f,
                                center.x + radius * .17f, mouthY)
                            else -> cubicTo(center.x - radius * .11f, mouthY + radius * .11f,
                                center.x + radius * .11f, mouthY + radius * .11f,
                                center.x + radius * .17f, mouthY)
                        }
                    }
                    drawPath(mouth, mouthColor,
                        style = Stroke(radius * .034f, cap = StrokeCap.Round))
                }
                if (index <= 1) {
                    val tearX = if (index == 0) listOf(leftX - radius * .11f, rightX + radius * .08f)
                        else listOf(rightX + radius * .09f)
                    tearX.forEach { x ->
                        drawOval(Color.White.copy(alpha = alpha * .90f),
                            topLeft = Offset(x, eyeY + radius * .015f + sin(phase * 2f) * radius * .013f),
                            size = Size(radius * .065f, radius * .14f))
                    }
                }
            }
            return@Canvas
        }

        val glowCenter = Offset(center.x + sin(phase) * radius * .16f, center.y - radius * .12f)
        drawPath(
            cloudPath(Offset(center.x, center.y + radius * .055f), radius * 1.025f, animated, phase),
            base.copy(alpha = .10f),
        )
        for (layer in 0..3) {
            val scale = 1f - layer * .14f
            val path = cloudPath(center, radius * scale, animated, phase + layer * .17f)
            val light = lerp(base, Color.White, .52f + layer * .06f)
            drawPath(
                path,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = .76f - layer * .07f),
                        light.copy(alpha = .67f),
                        base.copy(alpha = .62f - layer * .06f),
                        Color.White.copy(alpha = .35f),
                    ),
                    start = Offset(center.x - radius, center.y - radius),
                    end = Offset(center.x + radius, center.y + radius),
                ),
            )
            drawPath(
                path,
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = .25f), Color.Transparent),
                    center = glowCenter,
                    radius = radius * scale * 1.25f,
                ),
            )
            drawPath(path, Color.White.copy(alpha = .82f - layer * .09f), style = Stroke(width = 2.2f - layer * .2f))
        }
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = .82f), Color.White.copy(alpha = 0f)),
                center = glowCenter,
                radius = radius * .45f,
            ),
            radius = radius * .45f,
            center = glowCenter,
        )
        drawCircle(Color.White.copy(alpha = .92f), radius = radius * .022f, center = glowCenter)

    }
}
