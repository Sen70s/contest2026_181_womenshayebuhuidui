package com.pointerwander.luminawatch.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.ui.CloudImage
import com.pointerwander.luminawatch.ui.DemoBadge
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.PrimaryButton

@Composable
fun WelcomeScreen(onStart: () -> Unit, onConnect: () -> Unit) {
    val colors = LocalWatchColors.current
    Box(
        Modifier.fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text("LuminaWatch", color = colors.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                Text("嗨，我是小云。", color = colors.textPrimary, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text("关心你的每一天，也陪你聊聊每一个当下。", color = colors.textSecondary, fontSize = 15.sp)
            }
            Box(
                Modifier.size(276.dp).clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(colors.surface, colors.accentSoft, colors.background),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CloudImage(Modifier.size(236.dp))
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                PrimaryButton("进入小云", onStart, Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                LiquidGlassButton("查看手表连接引导", onConnect,
                    Modifier.fillMaxWidth(), filled = false)
                Text(
                    "手表连接和云岸服务将在后续版本开放。",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
