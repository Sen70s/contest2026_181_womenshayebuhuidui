package com.pointerwander.luminawatch.feature.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.ui.LiquidGlassSlidingSegmented
import com.pointerwander.luminawatch.ui.LiquidGlassStickyHeader
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.motionClickable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MentalRiskDetailScreen(store: AppStore, depression: Boolean, onBack: () -> Unit) {
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val title = if (depression) "抑郁风险" else "焦虑风险"
    val colors = LocalWatchColors.current
    val ink = Color(0xFF171719)
    val muted = Color(0xFF85858A)
    val blue = Color(0xFF0A84FF)
    var period by rememberSaveable { mutableIntStateOf(3) }
    var showAssessment by rememberSaveable { mutableStateOf(false) }
    val backdrop = rememberLayerBackdrop()
    val now = LocalDate.now()
    val start = when (period) {
        0 -> now
        1 -> now.minusDays(6)
        2 -> now.minusMonths(1)
        3 -> now.minusMonths(6)
        else -> now.minusYears(1)
    }
    val history = store.assessments.mapNotNull { record ->
        val score = if (depression) record.phqScore else record.gadScore
        val date = Instant.ofEpochMilli(record.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        if (score != null && !date.isBefore(start) && !date.isAfter(now)) date to score else null
    }.sortedBy { it.first }
    val latest = history.lastOrNull()
    Box(Modifier.fillMaxSize().background(colors.groupedSurface)) {
        Column(Modifier.fillMaxSize().layerBackdrop(backdrop).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(90.dp + statusBarHeight))
            Column(Modifier.fillMaxWidth().background(Color.White)
                .padding(horizontal = 18.dp, vertical = 18.dp)) {
                LiquidGlassSlidingSegmented(listOf("日", "周", "月", "6个月", "年"), period,
                    { period = it })
                Spacer(Modifier.height(20.dp))
                Text(latest?.let { assessmentLevel(it.second, depression) } ?: "没有可用数据",
                    color = ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                val dateFormat = DateTimeFormatter.ofPattern("yyyy年M月d日")
                Text("${start.format(dateFormat)}–${now.format(dateFormat)}",
                    color = muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(18.dp))
                val maxScore = if (depression) 27 else 21
                val days = (now.toEpochDay() - start.toEpochDay()).coerceAtLeast(1).toFloat()
                Canvas(Modifier.fillMaxWidth().height(270.dp)) {
                    repeat(5) { line ->
                        val y = size.height * line / 4f
                        drawLine(Color(0xFFDDDEE3), Offset(0f, y), Offset(size.width, y), 1f)
                    }
                    repeat(7) { line ->
                        val x = size.width * line / 6f
                        drawLine(Color(0xFFE8E8EB), Offset(x, 0f), Offset(x, size.height), 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 7f)))
                    }
                    history.forEach { (date, score) ->
                        val x = ((date.toEpochDay() - start.toEpochDay()) / days)
                            .coerceIn(0f, 1f) * size.width
                        val y = (1f - score.toFloat() / maxScore) * size.height
                        drawCircle(blue.copy(alpha = .16f), 13.dp.toPx(), Offset(x, y))
                        drawCircle(Color(0xFF0CC7B4), 5.dp.toPx(), Offset(x, y))
                    }
                }
                if (history.isEmpty()) Text("完成问卷后，结果会显示在图表中。",
                    color = muted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                else Text("${history.size} 次测量 · 点位显示每次问卷结果",
                    color = muted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp)) {
                Row(Modifier.fillMaxWidth().motionClickable(onClick = { showAssessment = true })
                    .background(Color.White, RoundedCornerShape(26.dp)).padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Psychology, null, tint = Color(0xFF20BDB5),
                        modifier = Modifier.size(64.dp))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("${title}问卷调查", color = ink, fontSize = 18.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("再次测量，了解过去两周的变化", color = muted, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("进行问卷调查", color = blue, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(34.dp))
                Text("关于$title", color = ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(if (depression)
                    "抑郁风险图表显示已完成问卷的历史结果。它帮助你观察变化，但不能单独用于诊断。"
                    else "焦虑风险图表显示已完成问卷的历史结果。它帮助你观察变化，但不能单独用于诊断。",
                    color = ink, fontSize = 16.sp, lineHeight = 24.sp,
                    modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp))
                        .padding(20.dp))
            }
        }
        LiquidGlassStickyHeader(title = title, onBack = onBack, backdrop = backdrop,
            topInset = statusBarHeight,
            modifier = Modifier.align(Alignment.TopCenter))
    }
    if (showAssessment) MentalAssessmentSheet(store) { showAssessment = false }
}
