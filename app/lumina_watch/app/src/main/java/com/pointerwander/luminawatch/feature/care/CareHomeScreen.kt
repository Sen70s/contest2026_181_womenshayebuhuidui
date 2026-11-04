package com.pointerwander.luminawatch.feature.care

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.pointerwander.luminawatch.app.Screen
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.CareEvent
import com.pointerwander.luminawatch.data.CareKind
import com.pointerwander.luminawatch.feature.health.MentalMoodCalendarSheet
import com.pointerwander.luminawatch.ui.LocalBottomBarInset
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.LiquidGlassStickyHeader
import com.pointerwander.luminawatch.ui.LiquidGlassSurface
import com.pointerwander.luminawatch.ui.motionClickable
import java.time.LocalDate

private val carePurple = Color(0xFF7569DA)

@Composable
internal fun CareHomeScreen(store: AppStore, onOpen: (Screen) -> Unit, onBack: (() -> Unit)?) {
    val colors = LocalWatchColors.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scrollState = rememberScrollState()
    val backdrop = rememberLayerBackdrop()
    val showHeader by remember { derivedStateOf { scrollState.value > 40 } }
    val backDrop = rememberCanvasBackdrop {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFD7D0F7), Color(0xFFEDE9FB), colors.groupedSurface)))
    }
    var filter by remember { mutableStateOf<CareKind?>(null) }
    var showCalendar by remember { mutableStateOf(false) }
    var calendarDate by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val pending = store.careEvents.filter { !it.acknowledged }
    val visible = store.careEvents.filter { filter == null || it.kind == filter }

    Box(Modifier.fillMaxSize().background(colors.groupedSurface)) {
        Column(Modifier.fillMaxSize().layerBackdrop(backdrop).verticalScroll(scrollState)) {
            Box(Modifier.fillMaxWidth().height(226.dp + statusBarHeight).background(Brush.verticalGradient(
                listOf(Color(0xFFD8D0F8), Color(0xFFECE8FB), colors.groupedSurface),
            ))) {
                Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 120.dp + statusBarHeight)) {
                    Text("小云关怀", color = colors.textPrimary, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(4.dp))
            Text("关怀与提醒", color = colors.textPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                CareHomeSectionTitle("待处理")
                if (pending.isNotEmpty()) Text("${pending.size} 条待回应", color = carePurple,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            if (pending.isEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(colors.surface).padding(horizontal = 20.dp, vertical = 19.dp)) {
                    Text("目前没有待处理事项", color = colors.textPrimary,
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("新的关怀提醒会出现在这里。", color = colors.textSecondary, fontSize = 14.sp)
                }
            } else {
                pending.forEachIndexed { index, event ->
                    CareHomeEventRow(event) { onOpen(Screen.CareDetail(event.id)) }
                    if (index != pending.lastIndex) Spacer(Modifier.height(10.dp))
                }
            }

            Spacer(Modifier.height(28.dp))
            Text("常用入口", color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(colors.surface)) {
                CareHomeEntry("关怀规则") { onOpen(Screen.CareRules) }
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp)
                    .background(Color(0xFFE7E7EC)))
                CareHomeEntry("心情月历") { showCalendar = true }
            }

            Spacer(Modifier.height(28.dp))
            CareHomeSectionTitle("关怀时间线")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CareHomeFilter("全部", filter == null) { filter = null }
                CareKind.entries.filter { it != CareKind.MANUAL }.forEach { kind ->
                    CareHomeFilter(kind.label, filter == kind) {
                        filter = if (filter == kind) null else kind
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (visible.isEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(colors.surface).padding(20.dp)) {
                    Text(if (filter == null) "暂无关怀记录" else "暂无${filter?.label}记录",
                        color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("有新的关怀记录时，会按时间显示在这里。",
                        color = colors.textSecondary, fontSize = 14.sp)
                }
            } else {
                visible.forEachIndexed { index, event ->
                    CareHomeEventRow(event) { onOpen(Screen.CareDetail(event.id)) }
                    if (index != visible.lastIndex) Spacer(Modifier.height(10.dp))
                }
            }
            Spacer(Modifier.height(150.dp + LocalBottomBarInset.current + navigationBarHeight))
            }
        }
        AnimatedVisibility(showHeader, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            LiquidGlassStickyHeader("小云关怀", onBack = onBack, backdrop = backdrop,
                topInset = statusBarHeight)
        }
        AnimatedVisibility(!showHeader, Modifier.align(Alignment.TopStart)
            .padding(start = 18.dp, top = statusBarHeight + 12.dp),
            enter = fadeIn(), exit = fadeOut()) {
            CareHomeBackButton(onBack, backDrop)
        }
    }
    if (showCalendar) {
        MentalMoodCalendarSheet(
            store = store,
            selectedEpochDay = calendarDate,
            onSelect = { calendarDate = it },
            onRecord = { day ->
                showCalendar = false
                onOpen(Screen.MoodRecord(day))
            },
            onDismiss = { showCalendar = false },
        )
    }
}

@Composable
private fun CareHomeBackButton(onBack: (() -> Unit)?, backdrop: Backdrop) {
    if (onBack == null) return
    LiquidGlassSurface(
        onClick = onBack,
        modifier = Modifier.size(48.dp),
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
private fun CareHomeSectionTitle(title: String) {
    Text(title, color = LocalWatchColors.current.textPrimary,
        fontSize = 22.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun CareHomeEntry(title: String, onClick: () -> Unit) {
    val colors = LocalWatchColors.current
    Row(Modifier.fillMaxWidth().height(48.dp).motionClickable(onClick)
        .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = colors.textPrimary, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null,
            tint = Color(0xFFB8B8BD), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CareHomeFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalWatchColors.current
    Box(Modifier.height(44.dp).clip(CircleShape)
        .background(if (selected) carePurple else colors.surface)
        .motionClickable(onClick)
        .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) colors.surface else colors.textPrimary,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CareHomeEventRow(event: CareEvent, onClick: () -> Unit) {
    val colors = LocalWatchColors.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(colors.surface).motionClickable(onClick)
        .padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.surfaceMuted),
            contentAlignment = Alignment.Center) {
            Icon(kindIcon(event.kind), contentDescription = null,
                tint = carePurple, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(event.title, color = colors.textPrimary, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text("${event.timeLabel} · ${event.kind.label}", color = colors.textSecondary, fontSize = 13.sp)
            if (!event.acknowledged) {
                Spacer(Modifier.height(3.dp))
                Text("待回应", color = carePurple, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null,
            tint = colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}
