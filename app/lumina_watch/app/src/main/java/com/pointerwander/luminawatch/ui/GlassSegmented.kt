package com.pointerwander.luminawatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 旧名称兼容；分段选项复用同一套液态玻璃按钮。 */
@Composable
fun GlassSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val colors = LocalWatchColors.current
    Row(
        modifier.fillMaxWidth().height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surfaceMuted).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            LiquidGlassChoiceChip(label, selected, { onSelected(index) },
                modifier = Modifier.weight(1f), height = 46.dp)
        }
    }
}
