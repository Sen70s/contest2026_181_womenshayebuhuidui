package com.pointerwander.luminawatch.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import android.app.Activity
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.Metric
import com.pointerwander.luminawatch.feature.care.CareDetailScreen
import com.pointerwander.luminawatch.feature.care.CareRulesScreen
import com.pointerwander.luminawatch.feature.care.CareScreen
import com.pointerwander.luminawatch.feature.chat.ChatHistoryScreen
import com.pointerwander.luminawatch.feature.chat.CloudMemoryScreen
import com.pointerwander.luminawatch.feature.chat.CloudStyleScreen
import com.pointerwander.luminawatch.feature.chat.ChatScreen
import com.pointerwander.luminawatch.feature.device.AlarmEditScreen
import com.pointerwander.luminawatch.feature.device.AlarmListScreen
import com.pointerwander.luminawatch.feature.device.AppearanceScreen
import com.pointerwander.luminawatch.feature.device.AllWatchesScreen
import com.pointerwander.luminawatch.feature.device.BindingScreen
import com.pointerwander.luminawatch.feature.device.DeviceScreen
import com.pointerwander.luminawatch.feature.device.SettingsScreen
import com.pointerwander.luminawatch.feature.device.WatchHomeScreen
import com.pointerwander.luminawatch.feature.device.FaceGalleryScreen
import com.pointerwander.luminawatch.feature.device.WatchDashboardScreen
import com.pointerwander.luminawatch.feature.device.HydrationInfoScreen
import com.pointerwander.luminawatch.feature.discover.DiscoverScreen
import com.pointerwander.luminawatch.feature.health.HealthScreen
import com.pointerwander.luminawatch.feature.health.HealthDashboardScreen
import com.pointerwander.luminawatch.feature.health.HealthSectionScreen
import com.pointerwander.luminawatch.feature.health.RunningHomeScreen
import com.pointerwander.luminawatch.feature.health.WorkoutHistoryScreen
import com.pointerwander.luminawatch.feature.health.SummaryEditorScreen
import com.pointerwander.luminawatch.feature.health.ActivityDetailScreen
import com.pointerwander.luminawatch.feature.health.HeartScreen
import com.pointerwander.luminawatch.feature.health.OxygenScreen
import com.pointerwander.luminawatch.feature.health.MetricDetailScreen
import com.pointerwander.luminawatch.feature.health.MentalAssessmentSheet
import com.pointerwander.luminawatch.feature.health.MentalRiskDetailScreen
import com.pointerwander.luminawatch.feature.health.MoodStatusScreen
import com.pointerwander.luminawatch.feature.mood.MoodCalendarScreen
import com.pointerwander.luminawatch.feature.mood.MoodDetailScreen
import com.pointerwander.luminawatch.feature.mood.MoodRecordFlow
import com.pointerwander.luminawatch.feature.today.TodayScreen
import com.pointerwander.luminawatch.ui.LocalBottomBarInset
import com.pointerwander.luminawatch.ui.AppNavigationState
import com.pointerwander.luminawatch.ui.LocalAppNavigationState
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.WatchBottomBar

@Composable
fun LuminaWatchApp(store: AppStore) {
    var started by remember { mutableStateOf(store.started) }
    var connectAfterStart by remember { mutableStateOf(false) }

    if (!started) {
        WelcomeScreen(
            onStart = {
                store.start()
                started = true
            },
            onConnect = {
                store.start()
                started = true
                connectAfterStart = true
            },
        )
        return
    }

    AppShell(
        store = store,
        openBindingOnStart = connectAfterStart,
        consumed = { connectAfterStart = false },
    )
}

@Composable
private fun AppShell(store: AppStore, openBindingOnStart: Boolean, consumed: () -> Unit) {
    val colors = LocalWatchColors.current
    var tab by rememberSaveable { mutableStateOf(Tab.HEALTH) }
    var resetTick by rememberSaveable { mutableIntStateOf(0) }
    var moodCalendarDate by rememberSaveable { mutableStateOf<Long?>(null) }
    val stack = remember { mutableStateListOf<Screen>() }
    val routeStack = stack.filterNot { it is Screen.MoodRecord }
    val route = routeStack.lastOrNull()
    val moodRecord = stack.lastOrNull() as? Screen.MoodRecord
    var displayedRoute by remember { mutableStateOf<Screen?>(null) }
    var displayedDepth by remember { mutableIntStateOf(0) }
    var departingRoute by remember { mutableStateOf<Screen?>(null) }
    var routeTransitioning by remember { mutableStateOf(false) }
    var returning by remember { mutableStateOf(false) }
    val routeSlide = remember { Animatable(0f) }
    var lastMoodRecord by remember { mutableStateOf<Screen.MoodRecord?>(null) }
    LaunchedEffect(route, routeStack.size) {
        if (route == displayedRoute && routeStack.size == displayedDepth) return@LaunchedEffect
        val previous = displayedRoute
        returning = routeStack.size < displayedDepth
        departingRoute = previous
        displayedRoute = route
        displayedDepth = routeStack.size
        routeTransitioning = true
        routeSlide.snapTo(if (returning) 0f else 1f)
        // Compose the destination once at its off-screen position before moving both pages.
        withFrameNanos { }
        routeSlide.animateTo(if (returning) 1f else 0f,
            spring(dampingRatio = .9f, stiffness = 310f))
        routeTransitioning = false
        departingRoute = null
    }
    val stateHolder = rememberSaveableStateHolder()
    val activity = LocalContext.current as? Activity
    // 页面与底栏分层：玻璃只采样页面，不能将底栏录进自己的 backdrop。
    val bottomBarBackdrop = rememberLayerBackdrop {
        drawRect(colors.background)
        drawContent()
    }

    SideEffect {
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }

    LaunchedEffect(openBindingOnStart) {
        if (openBindingOnStart) {
            tab = Tab.WATCH
            stack += Screen.Binding
            consumed()
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) {
        store.clearUndo()
        stack.removeAt(stack.lastIndex)
    }

    val selectTab: (Tab) -> Unit = { next ->
        if (stack.isNotEmpty()) stack.clear()
        if (next == tab) {
            resetTick++
        } else {
            tab = next
            store.clearUndo()
            store.activeCareContextId = null
        }
    }

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showBottomBar = moodRecord == null && route != Screen.MentalAssessment && !imeVisible
    val shellBackground = when {
        route == null && tab == Tab.HEALTH -> Color(0xFFF3F3F8)
        route == null && tab == Tab.WATCH -> Color(0xFFF4F4F8)
        route == null && tab == Tab.CLOUD -> colors.background
        route == Screen.ChatHome || route == Screen.ChatHistory || route is Screen.ChatMessage -> colors.background
        else -> colors.groupedSurface
    }
    // 渐变大标题页从系统栏下方连续绘制；普通页面让内容避开可触控安全区。
    val isFullBleedPage: (Screen?) -> Boolean = { screen ->
        (screen == null && tab == Tab.HEALTH) ||
            (screen == null && tab == Tab.WATCH) ||
            (screen == null && tab == Tab.CLOUD) ||
            screen == Screen.ChatHome ||
            screen == Screen.HealthHome ||
            screen == Screen.CareHome ||
            screen == Screen.RunningHome || screen == Screen.WorkoutHistory ||
            screen == Screen.HeartDetail || screen == Screen.OxygenDetail ||
            screen is Screen.MentalRiskDetail ||
            screen == Screen.DeviceHome || screen == Screen.AllWatches || screen == Screen.FaceGallery ||
            screen == Screen.HydrationInfo ||
            screen == Screen.AlarmList || screen is Screen.AlarmEdit ||
            screen == Screen.Binding || screen == Screen.Appearance ||
            screen == Screen.Settings || screen == Screen.CloudMemory ||
            screen == Screen.CloudStyle ||
            screen is Screen.ActivityDetail ||
            screen is Screen.HealthSectionPage &&
                (screen.section == HealthSection.MENTAL || screen.section == HealthSection.ACTIVITY)
    }
    val fullBleedPage = isFullBleedPage(route)
    Box(Modifier.fillMaxSize().background(shellBackground)) {
        if (!fullBleedPage) {
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                .background(shellBackground))
        }
        CompositionLocalProvider(
            LocalBottomBarInset provides if (showBottomBar) 88.dp else 0.dp,
            LocalAppNavigationState provides AppNavigationState(tab, selectTab),
        ) {
            Box(Modifier.fillMaxSize()) {
                // 内容与底栏为同级；页面是底栏的唯一背景采样源。
                Box(
                    Modifier.fillMaxSize()
                        .layerBackdrop(bottomBarBackdrop),
                ) {
                    val renderRoute: @Composable (Screen?, Boolean) -> Unit = { screen, interactive ->
                        Box(Modifier.fillMaxSize().then(
                            if (isFullBleedPage(screen)) Modifier
                            else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                        )) {
                        if (screen != null) {
                            SecondLevelScreen(
                                store = store,
                                screen = screen,
                                moodCalendarDate = moodCalendarDate,
                                onMoodCalendarDate = { moodCalendarDate = it },
                                onBack = {
                                    if (interactive && stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                                },
                                onOpen = { if (interactive) stack += it },
                                onGallery = { if (interactive) stack += Screen.FaceGallery },
                                onChat = { contextId ->
                                    if (interactive) {
                                        store.activeCareContextId = contextId
                                        stack += Screen.ChatHome
                                    }
                                },
                            )
                        } else {
                            Crossfade(targetState = tab, animationSpec = tween(180), label = "一级页切换") { current ->
                                val key = "${current.name}:$resetTick"
                                stateHolder.SaveableStateProvider(key) {
                                    when (current) {
                                        Tab.HEALTH -> HealthDashboardScreen(store,
                                            onOpen = { if (interactive) stack += it })
                                        Tab.CLOUD -> ChatScreen(store,
                                            onOpenHistory = { if (interactive) stack += Screen.ChatHistory },
                                            onOpenMemory = { if (interactive) stack += Screen.CloudMemory },
                                            onOpenStyle = { if (interactive) stack += Screen.CloudStyle },
                                        )
                                        Tab.WATCH -> WatchDashboardScreen(store,
                                            onOpen = { if (interactive) stack += it })
                                    }
                                }
                            }
                        }
                        }
                    }
                    if (routeTransitioning) {
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            val progress = routeSlide.value
                            translationX = -size.width * .22f * (1f - progress)
                        }) {
                            renderRoute(if (returning) displayedRoute else departingRoute, false)
                        }
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            val progress = routeSlide.value
                            translationX = size.width * progress
                        }) {
                            renderRoute(if (returning) departingRoute else displayedRoute,
                                !returning)
                        }
                    } else {
                        renderRoute(displayedRoute, true)
                    }
                }

                SideEffect { if (moodRecord != null) lastMoodRecord = moodRecord }
                AnimatedVisibility(
                    visible = moodRecord != null,
                    enter = slideInVertically(tween(320)) { it },
                    exit = slideOutVertically(tween(260)) { it },
                ) {
                    val shownRecord = moodRecord ?: lastMoodRecord
                    if (shownRecord != null) {
                    Box(Modifier.fillMaxSize().background(Color(0xFFBFC0C5))) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                            MoodRecordFlow(store, shownRecord.epochDay,
                                onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
                                onDone = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) })
                        }
                    }
                    }
                }
                // 全屏渐变页的卡片可以从底栏后方滑过，边缘用渐隐收口，
                // 避免卡片在手势区露出一截突兀的白色边缘。
                if (showBottomBar) {
                    val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding()
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .height(120.dp + navigationBarHeight)
                            .background(
                                Brush.verticalGradient(
                                    0f to shellBackground.copy(alpha = 0f),
                                    0.55f to shellBackground.copy(alpha = 0.82f),
                                    1f to shellBackground,
                                )
                            )
                    )
                }
                if (showBottomBar) Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .imePadding()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    WatchBottomBar(selected = tab, dark = false, backdrop = bottomBarBackdrop,
                        onSelect = selectTab)
                }
            }
        }
    }
}

@Composable
private fun SecondLevelScreen(
    store: AppStore,
    screen: Screen,
    moodCalendarDate: Long?,
    onMoodCalendarDate: (Long?) -> Unit,
    onBack: () -> Unit,
    onOpen: (Screen) -> Unit,
    onGallery: () -> Unit,
    onChat: (Long?) -> Unit,
) {
    when (screen) {
        Screen.AllWatches -> AllWatchesScreen(store, onBack, onOpen)
        Screen.SummaryEditor -> SummaryEditorScreen(store, onBack)
        is Screen.HealthSectionPage -> HealthSectionScreen(screen.section, store, onBack, onOpen)
        Screen.RunningHome -> RunningHomeScreen(store, onBack)
        Screen.WorkoutHistory -> WorkoutHistoryScreen(store, onBack)
        Screen.FaceGallery -> FaceGalleryScreen(store, onBack)
        Screen.HydrationInfo -> HydrationInfoScreen(store, onBack)
        Screen.TodayOverview -> TodayScreen(store, onOpen = onOpen,
            onOpenChat = { store.activeCareContextId = null; onOpen(Screen.ChatHome) },
            onOpenCare = { onOpen(Screen.CareHome) },
            onOpenDevice = { onOpen(Screen.DeviceHome) }, onBack = onBack)
        Screen.HealthHome -> HealthScreen(store, onOpen = onOpen, onBack = onBack)
        Screen.ChatHome -> ChatScreen(store, onOpenHistory = { onOpen(Screen.ChatHistory) },
            onOpenMemory = { onOpen(Screen.CloudMemory) },
            onOpenStyle = { onOpen(Screen.CloudStyle) }, onBack = onBack)
        Screen.CareHome -> CareScreen(store, onOpen = onOpen, onBack = onBack)
        Screen.DeviceHome -> DeviceScreen(store, onOpen = onOpen, onBack = onBack, onGallery = onGallery)
        Screen.HeartDetail -> HeartScreen(store, onBack)
        Screen.OxygenDetail -> OxygenScreen(store, onBack)
        is Screen.ActivityDetail -> ActivityDetailScreen(screen.metric, store, onBack)
        is Screen.MoodRecord -> MoodRecordFlow(store, screen.epochDay, onBack) { onBack() }
        Screen.MoodCalendar -> MoodCalendarScreen(
            store = store,
            onBack = onBack,
            onOpen = onOpen,
            selectedDate = moodCalendarDate,
            onSelectedDate = onMoodCalendarDate,
        )
        is Screen.MoodDetail -> MoodDetailScreen(store, screen.id, onBack, onOpen) { onChat(null) }
        Screen.MentalAssessment -> Box(Modifier.fillMaxSize()) {
            MentalAssessmentSheet(store, onBack)
        }
        is Screen.MentalRiskDetail -> MentalRiskDetailScreen(store, screen.depression, onBack)
        is Screen.MoodStatus -> MoodStatusScreen(store, screen.epochDay, onBack, onOpen)
        is Screen.CareDetail -> CareDetailScreen(
            store = store,
            eventId = screen.id,
            onBack = onBack,
            onOpen = onOpen,
            onChat = { onChat(screen.id) },
        )
        Screen.CareRules -> CareRulesScreen(store, onBack, onOpen)
        Screen.AlarmList -> AlarmListScreen(store, onBack, onOpen)
        is Screen.AlarmEdit -> AlarmEditScreen(store, screen.id, onBack)
        Screen.Binding -> BindingScreen(store, onBack)
        Screen.Appearance -> AppearanceScreen(store, onBack, onGallery)
        Screen.Settings -> SettingsScreen(store, onBack, onOpen)
        Screen.ChatHistory -> ChatHistoryScreen(store, onBack,
            onOpenMessage = { onOpen(Screen.ChatMessage(it)) })
        is Screen.ChatMessage -> ChatScreen(store,
            onOpenHistory = { onOpen(Screen.ChatHistory) },
            onOpenMemory = { onOpen(Screen.CloudMemory) },
            onOpenStyle = { onOpen(Screen.CloudStyle) },
            onBack = onBack,
            initialMessageId = screen.id)
        Screen.CloudMemory -> CloudMemoryScreen(store, onBack)
        Screen.CloudStyle -> CloudStyleScreen(store, onBack)
    }
}
