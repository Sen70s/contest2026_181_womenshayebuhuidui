package com.pointerwander.luminawatch.feature.health

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.SummaryItem
import com.pointerwander.luminawatch.ui.LiquidGlassBottomSheet
import com.pointerwander.luminawatch.ui.LiquidGlassIconButton
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.motionClickable

private val editorBackground = Color(0xFFF3F2F8)
private val editorBlue = Color(0xFF0A84FF)

@Composable
internal fun SummaryEditorSheet(store: AppStore, onDone: () -> Unit) {
    val colors = LocalWatchColors.current
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    LiquidGlassBottomSheet(
        onDismiss = onDone,
        fullHeight = true,
        translucent = false,
        sheetGesturesEnabled = false,
        surfaceColor = editorBackground,
        contentPadding = 0.dp,
    ) {
        Column(Modifier.fillMaxSize().background(editorBackground)) {
            Box(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 22.dp)) {
                Text("编辑列表", color = colors.textPrimary, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                LiquidGlassIconButton(Icons.Rounded.Check, "完成编辑", onDone,
                    modifier = Modifier.align(Alignment.CenterEnd), filled = true,
                    tint = editorBlue, size = 50.dp)
            }
            HealthSearchBar(query, { query = it }, searchExpanded,
                { searchExpanded = it }, searchFocus,
                modifier = Modifier.padding(horizontal = 18.dp), placeholder = "搜索")
            LazyColumn(Modifier.fillMaxSize().padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                val pinned = store.summaryItems.filter { it.label.contains(query.trim(), true) }
                item { EditorSectionTitle("置顶") }
                if (pinned.isEmpty()) item {
                    Text(if (query.isEmpty()) "暂无置顶项目" else "没有匹配的置顶项目",
                        color = colors.textSecondary, fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp))
                }
                itemsIndexed(pinned, key = { _, item -> "pinned_${item.name}" }) { index, item ->
                    EditorItemRow(item, pinned = true, store = store,
                        first = index == 0, last = index == pinned.lastIndex)
                }
                val available = SummaryItem.entries.filter {
                    it !in store.summaryItems && it.label.contains(query.trim(), true)
                }
                val groups = listOf(
                    "健康数据" to listOf(SummaryItem.HEART, SummaryItem.OXYGEN,
                        SummaryItem.STEPS, SummaryItem.STANDING, SummaryItem.CALORIES),
                    "健身记录" to listOf(SummaryItem.RUNNING),
                    "心理与关怀" to listOf(SummaryItem.MOOD, SummaryItem.CARE),
                )
                groups.forEach { (title, members) ->
                    val groupItems = available.filter { it in members }
                    if (groupItems.isNotEmpty()) {
                        item { EditorSectionTitle(title) }
                        itemsIndexed(groupItems, key = { _, item -> "available_${item.name}" }) { index, item ->
                            EditorItemRow(item, pinned = false, store = store,
                                first = index == 0, last = index == groupItems.lastIndex)
                        }
                    }
                }
                if (available.isEmpty() && pinned.isEmpty() && query.isNotEmpty()) item {
                    Text("没有找到相关项目", color = colors.textSecondary,
                        modifier = Modifier.padding(28.dp))
                }
                item { Spacer(Modifier.height(124.dp)) }
            }
        }
    }
}

@Composable
private fun EditorSectionTitle(title: String) {
    Text(title, color = Color(0xFF16161A), fontSize = 23.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 24.dp, top = 28.dp, bottom = 12.dp))
}

@Composable
private fun EditorItemRow(item: SummaryItem, pinned: Boolean, store: AppStore,
    first: Boolean, last: Boolean) {
    val colors = LocalWatchColors.current
    val rowHeight = with(LocalDensity.current) { 60.dp.toPx() }
    var dragOffset by remember(item) { mutableStateOf(0f) }
    var isDragging by remember(item) { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = if (first) 28.dp else 0.dp,
        topEnd = if (first) 28.dp else 0.dp,
        bottomStart = if (last) 28.dp else 0.dp,
        bottomEnd = if (last) 28.dp else 0.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)
        .zIndex(if (isDragging) 1f else 0f)
        .graphicsLayer { translationY = dragOffset }
        .clip(shape).background(Color.White).height(60.dp)
        .then(if (!pinned) Modifier.motionClickable(onClick = {
            store.setSummaryEnabled(item, true)
        }) else Modifier)
        .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape)
            .background(if (pinned) Color(0xFFC7C8CB) else Color(0xFFFFCE19))
            .then(if (pinned) Modifier.motionClickable(onClick = {
                store.setSummaryEnabled(item, false)
            }) else Modifier), contentAlignment = Alignment.Center) {
            Icon(if (pinned) Icons.Rounded.Remove else Icons.Rounded.PushPin,
                if (pinned) "移除${item.label}" else "置顶${item.label}",
                tint = Color.White, modifier = Modifier.size(17.dp))
        }
        Text(item.label, color = colors.textPrimary, fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 18.dp))
        if (pinned) {
            Icon(Icons.Rounded.DragHandle, "拖动${item.label}排序",
                tint = Color(0xFFB7B8BC), modifier = Modifier.size(30.dp)
                    .pointerInput(item) {
                        detectDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd = { isDragging = false; dragOffset = 0f },
                            onDragCancel = { isDragging = false; dragOffset = 0f },
                        ) { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                            if (dragOffset > rowHeight / 2 &&
                                store.summaryItems.indexOf(item) < store.summaryItems.lastIndex) {
                                store.moveSummary(item, 1)
                                dragOffset -= rowHeight
                            } else if (dragOffset < -rowHeight / 2 &&
                                store.summaryItems.indexOf(item) > 0) {
                                store.moveSummary(item, -1)
                                dragOffset += rowHeight
                            }
                        }
                    })
        }
    }
    if (!last) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .height(1.dp).background(Color.White)) {
            Box(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp)
                .height(1.dp).background(Color(0xFFE5E5EA)))
        }
    }
}
