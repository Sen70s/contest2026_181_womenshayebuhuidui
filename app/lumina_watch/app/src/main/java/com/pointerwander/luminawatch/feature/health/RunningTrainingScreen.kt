package com.pointerwander.luminawatch.feature.health

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.WorkoutSession
import com.pointerwander.luminawatch.ui.LiquidGlassBottomSheet
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassChoiceChip
import com.pointerwander.luminawatch.ui.LiquidGlassStickyHeader
import com.pointerwander.luminawatch.ui.LiquidGlassSurface
import com.pointerwander.luminawatch.ui.LiquidGlassHeaderPage
import com.pointerwander.luminawatch.ui.LocalBottomBarInset
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.bounceWhenContentFits
import com.pointerwander.luminawatch.ui.motionClickable
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val fitnessGreen = Color(0xFF7CB518)
private val fitnessLime = Color(0xFFA8E10C)
private val fitnessInk = Color(0xFF1C1C1E)
private val fitnessGray = Color(0xFF86868B)
private val workoutHistoryBlue = Color(0xFF5B8DEF)

private enum class WorkoutKind(
    val title: String,
    val subtitle: String,
    val indoor: Boolean,
    val running: Boolean,
) {
    OUTDOOR_WALK("户外步行", "在户外轻松走一走", false, false),
    INDOOR_WALK("室内步行", "在室内完成步行训练", true, false),
    OUTDOOR_RUN("户外跑步", "在户外记录一次跑步", false, true),
    INDOOR_RUN("室内跑步", "在室内跑步机上训练", true, true),
}

private sealed interface Stage {
    data object Home : Stage
    data class Ready(val kind: WorkoutKind) : Stage
    data class Active(val kind: WorkoutKind) : Stage
    data class Summary(val kind: WorkoutKind, val seconds: Int) : Stage
    data class Detail(val session: WorkoutSession) : Stage
}

@Composable
fun FitnessTrainingScreen(store: AppStore, onBack: () -> Unit) {
    var stage by remember { mutableStateOf<Stage>(Stage.Home) }
    when (val current = stage) {
        Stage.Home -> FitnessHome(
            sessions = store.workoutSessions,
            onBack = onBack,
            onStart = { stage = Stage.Ready(it) },
            onOpenSession = { stage = Stage.Detail(it) },
        )
        is Stage.Ready -> WorkoutReady(
            kind = current.kind,
            onCancel = { stage = Stage.Home },
            onGo = { stage = Stage.Active(current.kind) },
        )
        is Stage.Active -> WorkoutActive(
            kind = current.kind,
            onCancel = { stage = Stage.Home },
            onFinish = { elapsed ->
                val distance = distanceFor(current.kind, elapsed)
                val calories = caloriesFor(current.kind, elapsed)
                store.addWorkoutSession(
                    type = current.kind.title,
                    durationSeconds = elapsed,
                    distanceMeters = distance,
                    calories = calories,
                    avgHeartRate = 110 + elapsed / 20,
                    steps = (elapsed * if (current.kind.running) 2.8 else 1.7).toInt(),
                )
                stage = Stage.Summary(current.kind, elapsed)
            },
        )
        is Stage.Summary -> WorkoutSummary(
            kind = current.kind,
            seconds = current.seconds,
            onDone = { stage = Stage.Home },
        )
        is Stage.Detail -> WorkoutSessionDetail(
            session = current.session,
            onBack = { stage = Stage.Home },
        )
    }
}

@Composable
private fun FitnessHome(
    sessions: List<WorkoutSession>,
    onBack: () -> Unit,
    onStart: (WorkoutKind) -> Unit,
    onOpenSession: (WorkoutSession) -> Unit,
) {
    val colors = LocalWatchColors.current
    val scrollState = rememberScrollState()
    val backdrop = rememberLayerBackdrop()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val showHeader by remember { derivedStateOf { scrollState.value > 40 } }
    val visible = WorkoutKind.entries
    val backDrop = rememberCanvasBackdrop {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFE8F7C9), Color(0xFFF7FBEC), colors.groupedSurface)))
    }

    Box(Modifier.fillMaxSize().background(colors.groupedSurface)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                Box(
                    Modifier.fillMaxWidth().height(300.dp).background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFB9D878), Color(0xFFE6F2C7), colors.groupedSurface),
                        ),
                    ),
                ) {
                    Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 126.dp)) {
                        Text("体能训练", color = fitnessInk, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(20.dp))
                        Text("选择一项训练", color = fitnessInk, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(8.dp))
                    visible.forEach { kind ->
                        WorkoutCard(kind) { onStart(kind) }
                        Spacer(Modifier.height(14.dp))
                    }
                    if (sessions.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        Text("最近训练", color = fitnessInk, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        sessions.take(3).forEach { session ->
                            RecentWorkoutRow(session) { onOpenSession(session) }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                    Spacer(Modifier.height(120.dp + LocalBottomBarInset.current))
                }
            }
        }
        AnimatedVisibility(showHeader, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            LiquidGlassStickyHeader("体能训练", onBack = onBack, backdrop = backdrop,
                topInset = statusBarHeight)
        }
        AnimatedVisibility(!showHeader, Modifier.align(Alignment.TopStart)
            .padding(start = 18.dp, top = statusBarHeight + 16.dp),
            enter = fadeIn(), exit = fadeOut()) {
            FitnessBackButton(onBack, backDrop)
        }
    }
}

@Composable
private fun WorkoutCard(kind: WorkoutKind, onStart: () -> Unit) {
    val colors = LocalWatchColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(colors.surface)
            .motionClickable(onClick = onStart).padding(22.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(fitnessGreen.copy(alpha = .14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (kind.running) Icons.Rounded.DirectionsRun else Icons.Rounded.DirectionsWalk,
                    null, tint = fitnessGreen, modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(58.dp).clip(CircleShape).background(fitnessLime)
                    .motionClickable(onClick = onStart),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, "开始${kind.title}", tint = fitnessInk, modifier = Modifier.size(34.dp)) }
        }
        Spacer(Modifier.height(30.dp))
        Text(kind.title, color = fitnessInk, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(kind.subtitle, color = fitnessGray, fontSize = 15.sp)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FitnessMeta(if (kind.indoor) "室内" else "户外")
            FitnessMeta(if (kind.running) "跑步" else "步行")
        }
    }
}

@Composable
private fun FitnessMeta(label: String) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFF1F5E8))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(label, color = fitnessGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun FitnessBackButton(
    onBack: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop,
    modifier: Modifier = Modifier,
) {
    LiquidGlassSurface(
        onClick = onBack,
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        height = 48.dp,
        backdrop = backdrop,
        glassAlpha = .52f,
        border = Color.White.copy(alpha = .6f),
        contentPadding = 0.dp,
    ) { _ ->
        Canvas(Modifier.size(22.dp)) {
            drawLine(Color(0xFF0B0B0F), Offset(size.width * .66f, size.height * .14f),
                Offset(size.width * .32f, size.height * .5f), 2.8.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0xFF0B0B0F), Offset(size.width * .32f, size.height * .5f),
                Offset(size.width * .66f, size.height * .86f), 2.8.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun WorkoutReady(kind: WorkoutKind, onCancel: () -> Unit, onGo: () -> Unit) {
    val colors = LocalWatchColors.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var countdown by remember { mutableIntStateOf(3) }
    LaunchedEffect(kind) {
        countdown = 3
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
        onGo()
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFFB9D878), Color(0xFFE6F2C7), colors.groupedSurface)),
        ),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top = statusBarHeight + 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                LiquidGlassButton("取消", onCancel, height = 40.dp, filled = false)
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(270.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(fitnessGreen.copy(alpha = .14f), style = Stroke(width = 22.dp.toPx()))
                    drawArc(
                        fitnessGreen, -90f, 360f * (3 - countdown + 1) / 3f, false,
                        style = Stroke(width = 22.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                Text(
                    if (countdown == 0) "开始" else countdown.toString(),
                    color = fitnessInk, fontSize = 58.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(30.dp))
            Text(kind.title, color = fitnessInk, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("准备", color = fitnessGray, fontSize = 17.sp)
            Spacer(Modifier.weight(1f))
            Text("即将开始", color = fitnessGray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 50.dp))
        }
    }
}

@Composable
private fun WorkoutActive(
    kind: WorkoutKind,
    onCancel: () -> Unit,
    onFinish: (Int) -> Unit,
) {
    val colors = LocalWatchColors.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var seconds by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }
    LaunchedEffect(paused) {
        if (!paused) {
            while (true) {
                delay(1000)
                seconds++
            }
        }
    }
    val distanceMeters = seconds * if (kind.running) 2.6f else 1.25f
    val paces = if (distanceMeters > 0) (seconds / 60f) / (distanceMeters / 1000f) else 0f
    val calories = (seconds * if (kind.running) .16f else .06f).toInt()
    val heart = 108 + (seconds / 12) % 24

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFFD8F0AC), colors.groupedSurface), endY = 620f),
        ),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = statusBarHeight + 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FitnessBackButton(onCancel, rememberCanvasBackdrop { drawRect(Color(0xFFEAF7CB)) })
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(fitnessGreen.copy(alpha = .16f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (kind.running) Icons.Rounded.DirectionsRun else Icons.Rounded.DirectionsWalk,
                    null, tint = fitnessGreen, modifier = Modifier.size(24.dp)) }
            }
            Spacer(Modifier.height(30.dp))
            Text("距离", color = fitnessGray, fontSize = 15.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (distanceMeters < 1000) "${distanceMeters.toInt()}米" else String.format(Locale.US, "%.2f公里", distanceMeters / 1000f),
                    color = fitnessInk, fontSize = 52.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                ActiveStat("平均配速", if (paces > 0) "${paces.toInt()}′${((paces % 1) * 60).toInt()}″" else "--", Modifier.weight(1f))
                ActiveStat("心率", "$heart", Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                ActiveStat("动态千卡", "$calories", Modifier.weight(1f))
                ActiveStat("总千卡", "${calories + 18}", Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF1C1C1E))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(54.dp).clip(CircleShape).background(Color.White.copy(alpha = .12f))
                        .clickable { paused = !paused },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null,
                    tint = Color.White, modifier = Modifier.size(28.dp)) }
                Text(
                    formatTime(seconds), color = Color.White, fontSize = 32.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(54.dp).clip(CircleShape).background(Color.White.copy(alpha = .12f))
                        .clickable { showEnd = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Stop, null, tint = Color(0xFFFF453A), modifier = Modifier.size(26.dp)) }
            }
            Spacer(Modifier.height(24.dp + LocalBottomBarInset.current))
        }
    }
    if (showEnd) {
        LiquidGlassBottomSheet({ showEnd = false }, fullHeight = false) {
            Text("结束体能训练？", color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("本次 ${kind.title} 已进行 ${formatTime(seconds)}。", color = colors.textSecondary, fontSize = 15.sp)
            Spacer(Modifier.height(18.dp))
            LiquidGlassButton("结束并保存", { showEnd = false; onFinish(seconds) },
                Modifier.fillMaxWidth(), tint = Color(0xFF34C759))
            Spacer(Modifier.height(10.dp))
            LiquidGlassButton("继续训练", { showEnd = false }, Modifier.fillMaxWidth(), filled = false)
        }
    }
}

@Composable
private fun ActiveStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = fitnessGray, fontSize = 13.sp)
        Text(value, color = fitnessInk, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WorkoutSummary(kind: WorkoutKind, seconds: Int, onDone: () -> Unit) {
    val colors = LocalWatchColors.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val distance = distanceFor(kind, seconds)
    val calories = caloriesFor(kind, seconds)
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFFD8F0AC), colors.groupedSurface), endY = 700f),
        ),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(statusBarHeight + 64.dp))
            Text("训练完成", color = fitnessInk, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(kind.title, color = fitnessGray, fontSize = 18.sp)
            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(190.dp)) {
                    drawCircle(fitnessGreen.copy(alpha = .16f), style = Stroke(width = 22.dp.toPx()))
                    drawArc(fitnessGreen, -90f, 280f, false,
                        style = Stroke(width = 22.dp.toPx(), cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatTime(seconds), color = fitnessInk, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("训练时长", color = fitnessGray, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryStat("距离", if (distance < 1000) "${distance.toInt()} 米" else String.format(Locale.US, "%.2f 公里", distance / 1000f), Modifier.weight(1f))
                SummaryStat("活动消耗", "$calories 千卡", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryStat("平均心率", "${110 + seconds / 20} 次/分", Modifier.weight(1f))
                SummaryStat("步数", "${(seconds * if (kind.running) 2.8 else 1.7).toInt()}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(28.dp))
            LiquidGlassButton("完成", onDone, Modifier.fillMaxWidth(), tint = Color(0xFF34C759), height = 54.dp)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Color.White, RoundedCornerShape(22.dp)).padding(18.dp)) {
        Text(label, color = fitnessGray, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = fitnessInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    }
}

private fun formatTime(total: Int): String {
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
private fun distanceFor(kind: WorkoutKind, seconds: Int): Float =
    seconds * if (kind.running) 2.6f else 1.25f

private fun caloriesFor(kind: WorkoutKind, seconds: Int): Int =
    (seconds * if (kind.running) .16f else .06f).toInt()

private fun sessionDate(session: WorkoutSession): String {
    val formatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    return runCatching {
        Instant.ofEpochMilli(session.startMillis).atZone(ZoneId.systemDefault()).format(formatter)
    }.getOrDefault("本机记录")
}

@Composable
internal fun RecentWorkoutRow(
    session: WorkoutSession,
    accentColor: Color = fitnessGreen,
    onClick: () -> Unit,
) {
    val colors = LocalWatchColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(colors.surface)
            .motionClickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(accentColor.copy(alpha = .14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (session.type.contains("跑")) Icons.Rounded.DirectionsRun else Icons.Rounded.DirectionsWalk,
                null, tint = accentColor, modifier = Modifier.size(25.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(session.type, color = fitnessInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${sessionDate(session)} · ${formatTime(session.durationSeconds)}",
                color = fitnessGray, fontSize = 13.sp,
            )
        }
        Text(
            if (session.distanceMeters < 1000) "${session.distanceMeters.toInt()} 米"
            else String.format(Locale.US, "%.2f 公里", session.distanceMeters / 1000f),
            color = accentColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun WorkoutSessionDetail(
    session: WorkoutSession,
    onBack: () -> Unit,
    historyPalette: Boolean = false,
) {
    val colors = LocalWatchColors.current
    val gradientStart = if (historyPalette) Color(0xFFA9C7FF) else Color(0xFFD8F0AC)
    val glassStart = if (historyPalette) Color(0xFFDDE9FF) else Color(0xFFEAF7CB)
    val backdrop = rememberLayerBackdrop()
    val scrollState = rememberScrollState()
    val backDrop = rememberCanvasBackdrop {
        drawRect(Brush.verticalGradient(listOf(glassStart, colors.groupedSurface)))
    }
    Box(Modifier.fillMaxSize().background(colors.groupedSurface)) {
        Column(Modifier.fillMaxSize().bounceWhenContentFits(scrollState)
            .layerBackdrop(backdrop).verticalScroll(scrollState)) {
            Box(
                Modifier.fillMaxWidth().height(250.dp).background(
                    Brush.verticalGradient(listOf(gradientStart, colors.groupedSurface)),
                ),
            ) {
                Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 126.dp)) {
                    Text("训练详情", color = fitnessInk, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("${session.type} · ${sessionDate(session)}", color = fitnessGray, fontSize = 15.sp)
                }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryStat("训练时长", formatTime(session.durationSeconds), Modifier.weight(1f))
                    SummaryStat(
                        "距离",
                        if (session.distanceMeters < 1000) "${session.distanceMeters.toInt()} 米"
                        else String.format(Locale.US, "%.2f 公里", session.distanceMeters / 1000f),
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryStat("活动消耗", "${session.calories} 千卡", Modifier.weight(1f))
                    SummaryStat("平均心率", "${session.avgHeartRate} 次/分", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                SummaryStat("步数", "${session.steps}", Modifier.fillMaxWidth())
                Spacer(Modifier.height(22.dp))
                Text("数据来源", color = fitnessInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(22.dp)).padding(18.dp),
                ) {
                    Text(
                        if (session.source == "local_demo") "本机演示记录，不代表真实手表测量。"
                        else "手表同步记录。",
                        color = fitnessGray, fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(40.dp + LocalBottomBarInset.current))
            }
        }
        FitnessBackButton(
            onBack,
            backDrop,
            Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 54.dp),
        )
    }
}
@Composable
fun WorkoutHistoryScreen(store: AppStore, onBack: () -> Unit) {
    val colors = LocalWatchColors.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val sessions = store.workoutSessions
    var selected by remember { mutableStateOf<WorkoutSession?>(null) }
    val detailTransition = remember { MutableTransitionState(false) }
    BackHandler(enabled = detailTransition.currentState || detailTransition.targetState) {
        detailTransition.targetState = false
    }
    LiquidGlassHeaderPage(
        title = "运动记录",
        onBack = onBack,
        topInset = statusBarHeight,
        bounce = true,
        backgroundBrush = Brush.verticalGradient(
            listOf(Color(0xFFA9C7FF), Color(0xFFDDE9FF), colors.groupedSurface, colors.groupedSurface),
            endY = 720f,
        ),
    ) {
        if (sessions.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("还没有训练记录", color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("完成一次体能训练后，记录会出现在这里。", color = colors.textSecondary, fontSize = 14.sp)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HistoryStat("总训练", "${sessions.size} 次", Modifier.weight(1f))
                HistoryStat(
                    "总距离",
                    String.format(Locale.US, "%.2f 公里", sessions.sumOf { it.distanceMeters.toDouble() } / 1000),
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("全部记录", color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            sessions.forEach { session ->
                RecentWorkoutRow(session, onClick = {
                    selected = session
                    detailTransition.targetState = true
                }, accentColor = workoutHistoryBlue)
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.height(36.dp + LocalBottomBarInset.current))
    }
    selected?.let { session ->
        AnimatedVisibility(
            visibleState = detailTransition,
            enter = slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it },
            exit = slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it },
        ) {
            WorkoutSessionDetail(session,
                onBack = { detailTransition.targetState = false },
                historyPalette = true)
        }
    }
}

@Composable
private fun HistoryStat(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalWatchColors.current
    Column(modifier.background(colors.surface, RoundedCornerShape(20.dp)).padding(16.dp)) {
        Text(label, color = fitnessGray, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = fitnessInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
