package com.pointerwander.luminawatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import com.pointerwander.luminawatch.app.LuminaWatchApp
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.ui.LuminaWatchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val store = remember { AppStore(applicationContext) }
            SideEffect {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
            // 第一阶段只交付浅色；原深色偏好仍保留，待整套深色设计完成再启用。
            LuminaWatchTheme(dark = false) {
                LuminaWatchApp(store)
            }
        }
    }
}
