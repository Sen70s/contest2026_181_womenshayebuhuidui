package com.pointerwander.luminawatch.ui

import androidx.compose.runtime.compositionLocalOf
import com.pointerwander.luminawatch.app.Tab

data class AppNavigationState(val selected: Tab, val onSelect: (Tab) -> Unit)

val LocalAppNavigationState = compositionLocalOf<AppNavigationState?> { null }
