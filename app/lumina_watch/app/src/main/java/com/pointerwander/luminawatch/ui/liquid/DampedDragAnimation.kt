package com.pointerwander.luminawatch.ui.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 阻尼拖拽动画（移植自 kyant backdrop catalog，适配当前 Compose 版本）。
 *
 * 驱动 [LiquidBottomTabs] 选择器的四类动画：
 * - `value`：当前所在的（浮点）Tab 索引，拖拽时连续变化、抬手吸附到最近整数。
 * - `velocity`：拖拽速度（归一化为「全程/秒」），用于选择器甩动时的挤压拉伸形变。
 * - `scaleX/scaleY`：按压缩放（默认 1f，按下到 `pressedScale`）。
 * - `pressProgress`：按压进度（0~1），驱动 lens/高光/阴影等玻璃效果的「生长」。
 *
 * 与 catalog 原版的差异：原版用 `androidx.compose.ui.input.pointer.util.VelocityTracker` +
 * `kotlin.time.Clock` 计算速度，而 `VelocityTracker.addPosition` 的签名在 Compose 1.11
 * 改成了基于内联值类（`Uptime`）的形参，原样移植无法编译。这里改为自维护一个 100ms
 * 滑动窗口的 (nanoTime, value) 采样手动求速度，语义与原版一致且不随版本漂移。
 */
internal class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    // 速度采样窗口：(nanoTime, value)。仅保留最近 100ms 内的样本。
    private val velocitySamples = ArrayDeque<Pair<Long, Float>>()

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            }
        ) { change, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocitySamples.clear()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            // 等一帧再开始回弹（原版用 awaitFrame()，本 Compose 版本改为 withFrameNanos）。
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun snapToValue(value: Float) {
        animationScope.launch {
            valueAnimation.snapTo(value.coerceIn(valueRange))
            velocityAnimation.snapTo(0f)
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        addVelocitySample(value)
        val range = valueRange.endInclusive - valueRange.start
        val targetVelocity = if (range > 0f) calculateVelocity() / range else 0f
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }

    private fun addVelocitySample(v: Float) {
        val now = System.nanoTime()
        val cutoff = now - VELOCITY_WINDOW_NANOS
        while (velocitySamples.size > 2 && velocitySamples.first().first < cutoff) {
            velocitySamples.removeFirst()
        }
        velocitySamples.addLast(now to v)
    }

    /** 窗口内 (Δvalue / Δtime)，单位「全程/秒」，与原版 `VelocityTracker` 口径一致。 */
    private fun calculateVelocity(): Float {
        if (velocitySamples.size < 2) return 0f
        val first = velocitySamples.first()
        val last = velocitySamples.last()
        val dtSec = (last.first - first.first) / 1_000_000_000f
        if (dtSec <= 0f) return 0f
        return (last.second - first.second) / dtSec
    }

    private companion object {
        // 与 Compose VelocityTracker 默认窗口一致（~100ms）。
        private const val VELOCITY_WINDOW_NANOS = 100_000_000L
    }
}
