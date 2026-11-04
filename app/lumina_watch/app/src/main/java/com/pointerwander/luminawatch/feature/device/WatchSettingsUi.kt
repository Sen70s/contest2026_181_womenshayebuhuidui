package com.pointerwander.luminawatch.feature.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.ui.LiquidGlassHeaderPage
import com.pointerwander.luminawatch.ui.motionClickable

internal val watchSettingsBackground = Color(0xFFF4F4F8)
private val watchSettingsText = Color(0xFF17171B)
private val watchSettingsSecondary = Color(0xFF8E8E93)

@Composable
internal fun WatchSettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    LiquidGlassHeaderPage(title, onBack, bounce = true,
        backgroundBrush = Brush.verticalGradient(listOf(Color(0xFFEAF1FC), watchSettingsBackground,
            watchSettingsBackground)),
        contentTopOffset = 68.dp) {
        content()
    }
}

@Composable
internal fun WatchSettingsSection(title: String) {
    Text(title, color = watchSettingsText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
internal fun WatchSettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().clip(shape)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .89f),
            Color.White.copy(alpha = .72f))))
        .border(1.dp, Color.White.copy(alpha = .88f), shape),
        content = content)
}

@Composable
internal fun WatchSettingsDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 62.dp).height(1.dp)
        .background(Color(0xFFE6E6EA)))
}

@Composable
internal fun WatchSettingsIcon(icon: ImageVector, color: Color) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
        .background(Brush.verticalGradient(listOf(color.copy(alpha = .85f), color))),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(21.dp))
    }
}

@Composable
internal fun WatchSettingsRow(
    title: String,
    icon: ImageVector,
    color: Color,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().height(60.dp)
        .then(if (onClick == null) Modifier else Modifier.motionClickable(onClick = onClick))
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        WatchSettingsIcon(icon, color)
        Spacer(Modifier.width(12.dp))
        Text(title, color = watchSettingsText, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = watchSettingsSecondary, fontSize = 14.sp,
            maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        if (onClick != null) Text("›", color = Color(0xFFC7C7CC), fontSize = 25.sp,
            modifier = Modifier.padding(start = 7.dp))
    }
}

@Composable
internal fun WatchSettingsNote(message: String) {
    Text(message, color = watchSettingsSecondary, fontSize = 12.sp, lineHeight = 17.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}
