package com.pointerwander.luminawatch.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.ui.graphics.vector.ImageVector

/** 健康、陪伴和设备各占一个一级入口。 */
enum class Tab(val label: String, val icon: ImageVector) {
    HEALTH("健康", Icons.Rounded.Favorite),
    CLOUD("小云", Icons.Rounded.ChatBubble),
    WATCH("手表", Icons.Rounded.Watch),
}

enum class HealthSection { HEART, OXYGEN, ACTIVITY, EXERCISE, MENTAL, CARE }

/** 二级页面：隐藏底栏，使用统一顶部返回。 */
sealed interface Screen {
    data object AllWatches : Screen
    data object SummaryEditor : Screen
    data class HealthSectionPage(val section: HealthSection) : Screen
    data object RunningHome : Screen
    data object WorkoutHistory : Screen
    data object FaceGallery : Screen
    data object HydrationInfo : Screen
    data object TodayOverview : Screen
    data object HealthHome : Screen
    data object ChatHome : Screen
    data object CareHome : Screen
    data object DeviceHome : Screen
    data object HeartDetail : Screen
    data object OxygenDetail : Screen
    data class ActivityDetail(val metric: String) : Screen
    data class MoodRecord(val epochDay: Long? = null) : Screen
    data object MoodCalendar : Screen
    data class MoodStatus(val epochDay: Long? = null) : Screen
    data class MoodDetail(val id: Long) : Screen
    data object MentalAssessment : Screen
    data class MentalRiskDetail(val depression: Boolean) : Screen
    data class CareDetail(val id: Long) : Screen
    data object CareRules : Screen
    data object AlarmList : Screen
    data class AlarmEdit(val id: Long?) : Screen
    data object Binding : Screen
    data object Appearance : Screen
    data object Settings : Screen
    data object ChatHistory : Screen
    data class ChatMessage(val id: Long) : Screen
    data object CloudMemory : Screen
    data object CloudStyle : Screen
}
