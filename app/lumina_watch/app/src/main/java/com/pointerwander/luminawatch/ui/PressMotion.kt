package com.pointerwander.luminawatch.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Small press response for non-glass cards; no large Material ripple over their content. */
@Composable
fun Modifier.motionClickable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role = Role.Button,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    var clickPulse by remember { mutableStateOf(false) }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if ((pressed || clickPulse) && enabled) .975f else 1f,
        animationSpec = spring(dampingRatio = .78f, stiffness = 700f),
        label = "cardPressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        role = role,
        onClick = {
            if (!clickPulse) scope.launch {
                clickPulse = true
                delay(55)
                onClick()
                clickPulse = false
            }
        },
    )
}
