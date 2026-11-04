package com.pointerwander.luminawatch.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 本地演示数据仓库：所有记录在 SharedPreferences 中以 JSON 持久化，重启不丢。
 * 真实手表与云岸接口未接入，写入这里的都是可复现的演示数据。
 */
class AppStore(context: Context) {
    private val appContext = context.applicationContext
    private val assessmentVault = AssessmentVault(context)
    private val previewHealthData =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val canPreviewHealth: Boolean get() = previewHealthData
    private val prefs: SharedPreferences =
        context.getSharedPreferences("luminawatch_demo", Context.MODE_PRIVATE)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    var started by mutableStateOf(prefs.getBoolean("started", false))
        private set
    var showHealthData by mutableStateOf(prefs.getBoolean("show_health", true))
        private set
    var bottomBarDark by mutableStateOf(prefs.getBoolean("bottom_bar_dark", true))
        private set
    var darkTheme by mutableStateOf(prefs.getBoolean("app_dark_theme", false))
        private set
    /** Debug 默认进入明确标记的预览；Release 默认只显示真实无数据状态。 */
    var demoMode by mutableStateOf(prefs.getBoolean("health_demo_mode", previewHealthData))
        private set
    val watches = mutableStateListOf<WatchDevice>()
    var selectedWatchId by mutableStateOf(prefs.getString("selected_watch_id", "").orEmpty())
        private set
    /** 当前查看的手表；没有保存设备时返回空设备。 */
    val device: WatchDevice
        get() = watches.firstOrNull { it.id == selectedWatchId } ?: watches.firstOrNull() ?: WatchDevice()
    var preferences by mutableStateOf(loadPreferences())
        private set
    var hydrationReminder by mutableStateOf(HydrationReminderSettings.load(appContext))
        private set
    var pendingReply by mutableStateOf(false)
        private set
    var lastDeletedAlarm by mutableStateOf<Alarm?>(null)
        private set
    var activeCareContextId by mutableStateOf<Long?>(null)
    var avatarId by mutableStateOf(
        prefs.getString("avatar_id", null)
            ?: if (prefs.getBoolean("avatar_calm", false)) "ping_jing" else "wei_xiao",
    )
        private set
    /** 仅保存手机端表盘预览主题，不表示已经下发到手表。 */
    var faceTheme by mutableStateOf(prefs.getInt("face_theme", 0).coerceIn(0, 8))
        private set
    /** null 表示表盘表情跟随 App 内小云形象。 */
    var faceAvatarId by mutableStateOf(prefs.getString("face_avatar_id", null))
        private set
    var cloudName by mutableStateOf(prefs.getString("cloud_name", "小云") ?: "小云")
        private set
    var cloudPersonality by mutableStateOf(prefs.getString("cloud_personality", "温柔") ?: "温柔")
        private set
    var cloudDescription by mutableStateOf(prefs.getString("cloud_description", "一直在这里陪你聊聊。") ?: "一直在这里陪你聊聊。")
        private set
    var memoryEnabled by mutableStateOf(prefs.getBoolean("memory_enabled", false))
        private set

    val messages = mutableStateListOf<ChatMessage>()
    val careEvents = mutableStateListOf<CareEvent>()
    val alarms = mutableStateListOf<Alarm>()
    val moods = mutableStateListOf<MoodEntry>()
    val assessments = mutableStateListOf<AssessmentRecord>()
    val summaryItems = mutableStateListOf<SummaryItem>()
    val manualStepEntries = mutableStateListOf<ManualStepEntry>()
    val manualHeartEntries = mutableStateListOf<ManualHeartEntry>()
    val manualOxygenEntries = mutableStateListOf<ManualOxygenEntry>()
    val workoutSessions = mutableStateListOf<WorkoutSession>()
    var assessmentStorageError by mutableStateOf(false)
        private set

    init {
        HydrationReminderScheduler.refresh(appContext, hydrationReminder)
        loadWatches()
        summaryItems.addAll(loadSummaryItems())
        loadManualSteps()
        loadManualHeart()
        loadManualOxygen()
        loadWorkoutSessions()
        loadMessages()
        loadCareEvents()
        if (!prefs.getBoolean("legacy_care_cleanup", false)) {
            careEvents.removeAll { it.id in 2L..4L || it.demo }
            saveCareEvents()
            prefs.edit().putBoolean("legacy_care_cleanup", true).apply()
        }
        loadAlarms()
        loadMoods()
        runCatching { assessments.addAll(assessmentVault.read()) }
            .onFailure { assessmentStorageError = true }
        if (!prefs.getBoolean("seeded", false)) {
            seedDemoData()
            prefs.edit().putBoolean("seeded", true).apply()
            saveMessages()
            saveCareEvents()
            saveAlarms()
            saveMoods()
            saveDevice()
            savePreferences()
        }
    }

    fun updateHydrationReminder(settings: HydrationReminderSettings) {
        val safe = settings.normalized()
        hydrationReminder = safe
        HydrationReminderSettings.save(appContext, safe)
        HydrationReminderScheduler.refresh(appContext, safe)
    }

    // ---------- 演示控制 ----------

    fun start() {
        started = true
        prefs.edit().putBoolean("started", true).apply()
    }

    fun updateHealthDataVisibility(visible: Boolean) {
        showHealthData = visible
        prefs.edit().putBoolean("show_health", visible).apply()
    }

    fun updateBottomBarDark(dark: Boolean) {
        bottomBarDark = dark
        prefs.edit().putBoolean("bottom_bar_dark", dark).apply()
    }

    fun updateDarkTheme(dark: Boolean) {
        darkTheme = dark
        prefs.edit().putBoolean("app_dark_theme", dark).apply()
    }

    fun updateDemoMode(enabled: Boolean) {
        demoMode = enabled && previewHealthData
        prefs.edit().putBoolean("health_demo_mode", demoMode).apply()
    }

    fun setSummaryEnabled(item: SummaryItem, enabled: Boolean) {
        if (enabled && item !in summaryItems) summaryItems.add(item)
        if (!enabled) summaryItems.remove(item)
        saveSummaryItems()
    }

    fun addWorkoutSession(
        type: String,
        durationSeconds: Int,
        distanceMeters: Float,
        calories: Int,
        avgHeartRate: Int,
        steps: Int,
    ): WorkoutSession {
        val session = WorkoutSession(
            id = System.currentTimeMillis(),
            type = type,
            startMillis = System.currentTimeMillis() - durationSeconds * 1000L,
            durationSeconds = durationSeconds,
            distanceMeters = distanceMeters,
            calories = calories,
            avgHeartRate = avgHeartRate,
            steps = steps,
        )
        workoutSessions.add(0, session)
        val array = JSONArray()
        workoutSessions.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("type", item.type)
                    .put("start", item.startMillis)
                    .put("duration", item.durationSeconds)
                    .put("distance", item.distanceMeters.toDouble())
                    .put("calories", item.calories)
                    .put("heart", item.avgHeartRate)
                    .put("steps", item.steps)
                    .put("source", item.source),
            )
        }
        prefs.edit().putString("workout_sessions", array.toString()).apply()
        return session
    }

    private fun loadWorkoutSessions() {
        val raw = prefs.getString("workout_sessions", "[]") ?: "[]"
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                workoutSessions.add(
                    WorkoutSession(
                        id = item.optLong("id", 0L),
                        type = item.optString("type", "训练"),
                        startMillis = item.optLong("start", 0L),
                        durationSeconds = item.optInt("duration", 0),
                        distanceMeters = item.optDouble("distance", 0.0).toFloat(),
                        calories = item.optInt("calories", 0),
                        avgHeartRate = item.optInt("heart", 0),
                        steps = item.optInt("steps", 0),
                        source = item.optString("source", "local_demo"),
                    ),
                )
            }
        }
    }

    fun addManualSteps(timestamp: Long, steps: Int): Boolean {
        if (steps !in 1..100_000 || timestamp > System.currentTimeMillis()) return false
        manualStepEntries.add(ManualStepEntry(timestamp, steps))
        val array = JSONArray()
        manualStepEntries.forEach { entry ->
            array.put(JSONObject().put("timestamp", entry.timestamp).put("steps", entry.steps))
        }
        prefs.edit().putString("manual_steps", array.toString()).apply()
        return true
    }

    fun addManualHeart(timestamp: Long, bpm: Int): Boolean {
        if (bpm !in 30..220 || timestamp > System.currentTimeMillis()) return false
        manualHeartEntries.add(ManualHeartEntry(timestamp, bpm))
        val array = JSONArray()
        manualHeartEntries.forEach { entry ->
            array.put(JSONObject().put("timestamp", entry.timestamp).put("bpm", entry.bpm))
        }
        prefs.edit().putString("manual_heart", array.toString()).apply()
        return true
    }

    fun addManualOxygen(timestamp: Long, percent: Int): Boolean {
        if (percent !in 70..100 || timestamp > System.currentTimeMillis()) return false
        manualOxygenEntries.add(ManualOxygenEntry(timestamp, percent))
        val array = JSONArray()
        manualOxygenEntries.forEach { entry ->
            array.put(JSONObject().put("timestamp", entry.timestamp).put("percent", entry.percent))
        }
        prefs.edit().putString("manual_oxygen", array.toString()).apply()
        return true
    }

    private fun loadManualOxygen() {
        val raw = prefs.getString("manual_oxygen", "[]") ?: "[]"
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val timestamp = item.optLong("timestamp", -1L)
                val percent = item.optInt("percent", 0)
                if (timestamp > 0 && percent in 70..100) {
                    manualOxygenEntries.add(ManualOxygenEntry(timestamp, percent))
                }
            }
        }
    }

    private fun loadManualHeart() {
        val raw = prefs.getString("manual_heart", "[]") ?: "[]"
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val timestamp = item.optLong("timestamp", -1L)
                val bpm = item.optInt("bpm", 0)
                if (timestamp > 0 && bpm in 30..220) {
                    manualHeartEntries.add(ManualHeartEntry(timestamp, bpm))
                }
            }
        }
    }

    private fun loadManualSteps() {
        val raw = prefs.getString("manual_steps", "[]") ?: "[]"
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val timestamp = item.optLong("timestamp", -1L)
                val steps = item.optInt("steps", 0)
                if (timestamp > 0 && steps in 1..100_000) {
                    manualStepEntries.add(ManualStepEntry(timestamp, steps))
                }
            }
        }
    }

    fun moveSummary(item: SummaryItem, by: Int) {
        val from = summaryItems.indexOf(item)
        val to = from + by
        if (from !in summaryItems.indices || to !in summaryItems.indices) return
        summaryItems.removeAt(from)
        summaryItems.add(to, item)
        saveSummaryItems()
    }

    private fun loadSummaryItems(): List<SummaryItem> {
        val stored = prefs.getString("health_summary_items", null)
            ?: return listOf(SummaryItem.STEPS, SummaryItem.HEART, SummaryItem.MOOD)
        return stored.split(',').mapNotNull { name ->
            SummaryItem.entries.firstOrNull { it.name == name }
        }.distinct()
    }

    private fun saveSummaryItems() {
        prefs.edit().putString("health_summary_items", summaryItems.joinToString(",") { it.name }).apply()
    }


    fun resetDemo() {
        prefs.edit().clear().apply()
        updateHydrationReminder(HydrationReminderSettings())
        messages.clear()
        careEvents.clear()
        alarms.clear()
        moods.clear()
        watches.clear()
        selectedWatchId = ""
        preferences = CarePreferences()
        avatarId = "wei_xiao"
        faceTheme = 0
        faceAvatarId = null
        cloudName = "小云"
        cloudPersonality = "温柔"
        cloudDescription = "一直在这里陪你聊聊。"
        memoryEnabled = false
        showHealthData = true
        bottomBarDark = true
        darkTheme = false
        demoMode = previewHealthData
        summaryItems.clear()
        summaryItems.addAll(listOf(SummaryItem.STEPS, SummaryItem.HEART, SummaryItem.MOOD))
        started = false
        pendingReply = false
        lastDeletedAlarm = null
        activeCareContextId = null
        seedDemoData()
        prefs.edit().putBoolean("seeded", true).apply()
        saveMessages()
        saveCareEvents()
        saveAlarms()
        saveMoods()
        saveDevice()
        savePreferences()
        saveSummaryItems()
    }

    // ---------- 健康 ----------

    fun latestSample(metric: Metric): HealthSample? {
        if (!showHealthData || !previewHealthData) return null
        return when (metric) {
            Metric.HEART_RATE -> HealthSample(
                Metric.HEART_RATE, 78, "今天", SampleSource.DEMO,
            )
            Metric.OXYGEN -> HealthSample(
                Metric.OXYGEN, 98, "今天", SampleSource.DEMO,
            )
        }
    }

    fun valueText(metric: Metric): String = latestSample(metric)?.value?.toString() ?: "--"

    // ---------- 小云对话 ----------

    fun sendMessage(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        messages += ChatMessage(
            id = System.nanoTime(),
            text = content,
            fromCloud = false,
            source = "来自 App",
            timeLabel = nowLabel(),
            status = MessageStatus.SENT,
        )
        pendingReply = true
        saveMessages()
    }

    fun sendImage(imageUri: String) {
        messages += ChatMessage(
            id = System.nanoTime(),
            text = "发送了一张照片",
            fromCloud = false,
            source = "来自 App · 仅本机保存",
            timeLabel = nowLabel(),
            imageUri = imageUri,
        )
        pendingReply = true
        saveMessages()
    }

    fun completeReply() {
        if (!pendingReply) return
        val lastUserText = messages.lastOrNull { !it.fromCloud }?.text.orEmpty()
        messages += ChatMessage(
            id = System.nanoTime(),
            text = replyFor(lastUserText),
            fromCloud = true,
            source = "小云 · 本地回复",
            timeLabel = nowLabel(),
        )
        pendingReply = false
        saveMessages()
    }

    fun retryLastMessage() {
        val index = messages.indexOfLast { !it.fromCloud }
        if (index >= 0 && messages[index].status == MessageStatus.FAILED) {
            messages[index] = messages[index].copy(status = MessageStatus.SENT)
            pendingReply = true
            saveMessages()
        }
    }

    private fun replyFor(input: String): String = when {
        input.contains("累") -> "辛苦啦。要不要先放松一下，喝点水，再慢慢和我说？"
        input.contains("开心") -> "好呀！我想听听是什么让你开心。"
        input.contains("心率") || input.contains("血氧") ->
            "健康数据需要连接手表后才能用于判断。如果身体不舒服，请及时联系专业人士。"
        input.contains("今天") || input.contains("状态") ->
            "我在这里陪你聊聊。也可以告诉我你现在的感受。"
        else -> "我在听。你可以继续告诉我，今天有什么想分享的。"
    }

    // ---------- 关怀 ----------

    fun acknowledgeCare(id: Long) = updateCare(id) { it.copy(acknowledged = true) }

    fun setFollowUp(id: Long, minutes: Int?) = updateCare(id) { it.copy(followUpMinutes = minutes) }

    fun authorizeContext(id: Long, authorized: Boolean) =
        updateCare(id) { it.copy(contextAuthorized = authorized) }

    fun deleteCare(id: Long) {
        careEvents.removeAll { it.id == id }
        if (activeCareContextId == id) activeCareContextId = null
        saveCareEvents()
    }

    fun careEvent(id: Long?): CareEvent? = careEvents.firstOrNull { it.id == id }

    private fun updateCare(id: Long, block: (CareEvent) -> CareEvent) {
        val index = careEvents.indexOfFirst { it.id == id }
        if (index >= 0) {
            careEvents[index] = block(careEvents[index])
            saveCareEvents()
        }
    }

    fun updatePreferences(block: (CarePreferences) -> CarePreferences) {
        preferences = block(preferences)
        savePreferences()
    }

    // ---------- 闹钟 ----------

    fun addAlarm(
        hour: Int,
        minute: Int,
        label: String,
        repeatDays: Set<Int>,
        vibrate: Boolean,
        announce: Boolean,
    ): AddAlarmResult {
        if (hour !in 0..23 || minute !in 0..59) return AddAlarmResult.INVALID
        // 关闭的闹钟同样占用名额：存在即计数。
        if (alarms.size >= MAX_ALARMS) return AddAlarmResult.LIMIT
        alarms += Alarm(
            id = System.nanoTime(),
            hour = hour,
            minute = minute,
            label = label.trim().ifEmpty { "闹钟" },
            repeatDays = repeatDays,
            vibrate = vibrate,
            announce = announce,
        )
        saveAlarms()
        return AddAlarmResult.OK
    }

    fun updateAlarm(alarm: Alarm) {
        val index = alarms.indexOfFirst { it.id == alarm.id }
        if (index >= 0) {
            alarms[index] = alarm
            saveAlarms()
        }
    }

    fun deleteAlarm(id: Long) {
        val target = alarms.firstOrNull { it.id == id } ?: return
        lastDeletedAlarm = target
        alarms.removeAll { it.id == id }
        saveAlarms()
    }

    fun undoDeleteAlarm() {
        lastDeletedAlarm?.let { alarm ->
            if (alarms.size < MAX_ALARMS) alarms += alarm
        }
        lastDeletedAlarm = null
        saveAlarms()
    }

    fun clearUndo() {
        lastDeletedAlarm = null
    }

    fun nextAlarm(): Alarm? = alarms
        .filter { it.enabled }
        .sortedWith(compareBy({ it.hour }, { it.minute }))
        .firstOrNull()

    // ---------- 心情 ----------

    fun addMood(
        scope: MoodScope,
        value: Float,
        words: List<String>,
        factors: List<String>,
        note: String,
        epochDay: Long = LocalDate.now().toEpochDay(),
    ): MoodEntry {
        val entry = MoodEntry(
            id = System.nanoTime(),
            epochDay = epochDay,
            scope = scope,
            value = value.coerceIn(0f, 1f),
            words = words,
            factors = factors,
            note = note.trim(),
            timeLabel = if (epochDay == LocalDate.now().toEpochDay()) nowLabel()
                else "${LocalDate.ofEpochDay(epochDay).monthValue}月${LocalDate.ofEpochDay(epochDay).dayOfMonth}日 " +
                    LocalTime.now().format(timeFormat),
        )
        moods.add(0, entry)
        saveMoods()
        return entry
    }

    fun updateMood(entry: MoodEntry) {
        val index = moods.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            moods[index] = entry
            saveMoods()
        }
    }

    fun deleteMood(id: Long) {
        moods.removeAll { it.id == id }
        saveMoods()
    }

    fun moodsOn(epochDay: Long): List<MoodEntry> = moods.filter { it.epochDay == epochDay }

    fun todayMoods(): List<MoodEntry> = moodsOn(LocalDate.now().toEpochDay())

    fun mood(id: Long?): MoodEntry? = moods.firstOrNull { it.id == id }

    fun addAssessment(gadAnswers: List<Int>, phqAnswers: List<Int>): AssessmentRecord? {
        if (assessmentStorageError) return null
        require(gadAnswers.isEmpty() || gadAnswers.size == 7)
        require(phqAnswers.isEmpty() || phqAnswers.size == 9)
        val record = AssessmentRecord(System.nanoTime(), System.currentTimeMillis(), gadAnswers, phqAnswers)
        val next = listOf(record) + assessments
        return runCatching {
            assessmentVault.write(next)
            assessments.add(0, record)
            record
        }.onFailure { assessmentStorageError = true }.getOrNull()
    }

    fun deleteAssessment(id: Long): Boolean {
        if (assessmentStorageError) return false
        val next = assessments.filterNot { it.id == id }
        return runCatching {
            assessmentVault.write(next)
            assessments.removeAll { it.id == id }
            true
        }.onFailure { assessmentStorageError = true }.getOrDefault(false)
    }

    // ---------- 设备 ----------

    /** 手动保存设备信息：只做本地格式校验，不校验真实设备唯一 ID。 */
    fun bindDevice(input: String, name: String = "小云手表", source: String = "manual"): Boolean {
        val clean = input.trim().uppercase().replace(" ", "")
        if (!Regex("^[A-Z0-9]{8,16}$").matches(clean)) return false
        val existing = watches.indexOfFirst { it.id == clean || (it.source == "manual" && it.tailId == clean.takeLast(4)) }
        val watch = WatchDevice(
            id = clean,
            bound = true,
            name = if (existing >= 0) watches[existing].name else name,
            tailId = clean.takeLast(4),
            connected = false,
            battery = null,
            lastSyncLabel = "已保存设备信息 · 等待连接",
            source = source,
        )
        if (existing >= 0) watches[existing] = watch else watches += watch
        selectedWatchId = clean
        saveWatches()
        return true
    }

    /** 蓝牙选择只保存本机设备记录；真实 GATT/协议连接尚未接入。 */
    fun addBluetoothWatch(name: String, address: String): Boolean {
        val clean = address.trim().uppercase()
        if (clean.isEmpty()) return false
        val safeName = name.trim().ifEmpty { "蓝牙手表" }
        val existing = watches.indexOfFirst { it.id == clean }
        val watch = WatchDevice(
            id = clean,
            bound = true,
            name = if (existing >= 0) watches[existing].name else safeName,
            tailId = clean.takeLast(4),
            connected = false,
            battery = null,
            lastSyncLabel = "已选择蓝牙设备 · 协议尚未接入",
            source = "bluetooth",
        )
        if (existing >= 0) watches[existing] = watch else watches += watch
        selectedWatchId = clean
        saveWatches()
        return true
    }

    fun selectWatch(id: String) {
        if (watches.none { it.id == id }) return
        selectedWatchId = id
        prefs.edit().putString("selected_watch_id", id).apply()
    }

    fun renameWatch(id: String, name: String) {
        val index = watches.indexOfFirst { it.id == id }
        if (index < 0) return
        val clean = name.trim().take(24).ifEmpty { "小云手表" }
        watches[index] = watches[index].copy(name = clean)
        saveWatches()
    }

    fun removeWatch(id: String) {
        watches.removeAll { it.id == id }
        if (selectedWatchId == id) selectedWatchId = watches.firstOrNull()?.id.orEmpty()
        saveWatches()
    }

    fun unbindDevice() {
        removeWatch(device.id)
    }

    fun syncDevice() {
        if (!device.bound) return
        val index = watches.indexOfFirst { it.id == device.id }
        if (index < 0) return
        watches[index] = watches[index].copy(
            connected = false,
            lastSyncLabel = "等待手表连接",
        )
        saveWatches()
    }

    fun updateAvatar(id: String) {
        avatarId = id
        prefs.edit().putString("avatar_id", id).apply()
    }

    fun updateFaceTheme(theme: Int) {
        faceTheme = theme.coerceIn(0, 8)
        prefs.edit().putInt("face_theme", faceTheme).apply()
    }

    fun updateFaceAvatar(id: String?) {
        faceAvatarId = id
        prefs.edit().apply {
            if (id == null) remove("face_avatar_id") else putString("face_avatar_id", id)
        }.apply()
    }

    fun updateCloudProfile(name: String, personality: String, description: String) {
        cloudName = name.trim().ifEmpty { "小云" }
        cloudPersonality = personality
        cloudDescription = description.trim()
        prefs.edit().putString("cloud_name", cloudName)
            .putString("cloud_personality", cloudPersonality)
            .putString("cloud_description", cloudDescription).apply()
    }

    fun updateMemoryEnabled(enabled: Boolean) {
        memoryEnabled = enabled
        prefs.edit().putBoolean("memory_enabled", enabled).apply()
    }

    fun setDemoConnection(connected: Boolean) {
        val index = watches.indexOfFirst { it.id == device.id }
        if (index < 0) return
        watches[index] = watches[index].copy(connected = connected)
        saveWatches()
    }

    // ---------- 持久化 ----------

    private fun seedDemoData() {
        messages += ChatMessage(
            id = 1L,
            text = "下午好，我是小云。现在想聊些什么？",
            fromCloud = true,
            source = "小云",
            timeLabel = "欢迎",
        )
    }

    private fun nowLabel(): String = "今天 " + LocalTime.now().format(timeFormat)

    private fun loadMessages() {
        val raw = prefs.getString("messages", null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                messages += ChatMessage(
                    id = item.optLong("id"),
                    text = item.optString("text"),
                    fromCloud = item.optBoolean("fromCloud"),
                    source = item.optString("source"),
                    timeLabel = item.optString("time"),
                    status = MessageStatus.valueOf(item.optString("status", "SENT")),
                    imageUri = item.optString("imageUri").takeIf { it.isNotBlank() },
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun saveMessages() {
        val array = JSONArray()
        messages.takeLast(200).forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("text", message.text)
                    .put("fromCloud", message.fromCloud)
                    .put("source", message.source)
                    .put("time", message.timeLabel)
                    .put("status", message.status.name)
                    .put("imageUri", message.imageUri ?: ""),
            )
        }
        prefs.edit().putString("messages", array.toString()).apply()
    }

    private fun loadCareEvents() {
        val raw = prefs.getString("care_events", null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                careEvents += CareEvent(
                    id = item.optLong("id"),
                    kind = CareKind.valueOf(item.optString("kind", "MANUAL")),
                    title = item.optString("title"),
                    detail = item.optString("detail"),
                    timeLabel = item.optString("time"),
                    acknowledged = item.optBoolean("ack"),
                    followUpMinutes = if (item.has("followUp")) item.optInt("followUp") else null,
                    contextAuthorized = item.optBoolean("context"),
                    demo = item.optBoolean("demo", true),
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun saveCareEvents() {
        val array = JSONArray()
        careEvents.forEach { event ->
            val item = JSONObject()
                .put("id", event.id)
                .put("kind", event.kind.name)
                .put("title", event.title)
                .put("detail", event.detail)
                .put("time", event.timeLabel)
                .put("ack", event.acknowledged)
                .put("context", event.contextAuthorized)
                .put("demo", event.demo)
            event.followUpMinutes?.let { item.put("followUp", it) }
            array.put(item)
        }
        prefs.edit().putString("care_events", array.toString()).apply()
    }

    private fun loadAlarms() {
        val raw = prefs.getString("alarms", null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val days = mutableSetOf<Int>()
                val dayArray = item.optJSONArray("days") ?: JSONArray()
                for (d in 0 until dayArray.length()) days += dayArray.getInt(d)
                alarms += Alarm(
                    id = item.optLong("id"),
                    hour = item.optInt("hour"),
                    minute = item.optInt("minute"),
                    label = item.optString("label"),
                    repeatDays = days,
                    enabled = item.optBoolean("enabled", true),
                    vibrate = item.optBoolean("vibrate", true),
                    announce = item.optBoolean("announce"),
                    synced = item.optBoolean("synced"),
                    createdAt = item.optLong("created"),
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun saveAlarms() {
        val array = JSONArray()
        alarms.forEach { alarm ->
            val days = JSONArray()
            alarm.repeatDays.sorted().forEach { days.put(it) }
            array.put(
                JSONObject()
                    .put("id", alarm.id)
                    .put("hour", alarm.hour)
                    .put("minute", alarm.minute)
                    .put("label", alarm.label)
                    .put("days", days)
                    .put("enabled", alarm.enabled)
                    .put("vibrate", alarm.vibrate)
                    .put("announce", alarm.announce)
                    .put("synced", alarm.synced)
                    .put("created", alarm.createdAt),
            )
        }
        prefs.edit().putString("alarms", array.toString()).apply()
    }

    private fun loadMoods() {
        val raw = prefs.getString("moods", null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val words = mutableListOf<String>()
                val wordArray = item.optJSONArray("words") ?: JSONArray()
                for (w in 0 until wordArray.length()) words += wordArray.getString(w)
                val factors = mutableListOf<String>()
                val factorArray = item.optJSONArray("factors") ?: JSONArray()
                for (f in 0 until factorArray.length()) factors += factorArray.getString(f)
                moods += MoodEntry(
                    id = item.optLong("id"),
                    epochDay = item.optLong("day"),
                    scope = MoodScope.valueOf(item.optString("scope", "MOMENT")),
                    value = item.optDouble("value", 0.5).toFloat(),
                    words = words,
                    factors = factors,
                    note = item.optString("note"),
                    timeLabel = item.optString("time"),
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun saveMoods() {
        val array = JSONArray()
        moods.forEach { entry ->
            val words = JSONArray().apply { entry.words.forEach { put(it) } }
            val factors = JSONArray().apply { entry.factors.forEach { put(it) } }
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("day", entry.epochDay)
                    .put("scope", entry.scope.name)
                    .put("value", entry.value.toDouble())
                    .put("words", words)
                    .put("factors", factors)
                    .put("note", entry.note)
                    .put("time", entry.timeLabel),
            )
        }
        prefs.edit().putString("moods", array.toString()).apply()
    }

    private fun loadWatches() {
        val raw = prefs.getString("watches", null)
        if (raw != null) {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val id = item.optString("id").ifBlank { item.optString("tail") }
                    watches += WatchDevice(
                        id = id,
                        bound = item.optBoolean("bound", true),
                        name = item.optString("name", "小云手表"),
                        tailId = item.optString("tail"),
                        connected = false,
                        battery = null,
                        lastSyncLabel = item.optString("lastSync", "等待手表连接"),
                        source = item.optString("source", "manual"),
                    )
                }
            } catch (_: Throwable) {
                watches.clear()
            }
        }
        if (raw == null && prefs.getString("device", null) != null) {
            val legacy = loadDevice()
            if (legacy.name.isNotBlank()) watches += legacy
        }
        selectedWatchId = prefs.getString("selected_watch_id", "").orEmpty()
            .ifBlank { watches.firstOrNull()?.id.orEmpty() }
    }

    private fun saveWatches() {
        val array = JSONArray()
        watches.forEach { watch ->
            array.put(
                JSONObject()
                    .put("id", watch.id)
                    .put("bound", watch.bound)
                    .put("name", watch.name)
                    .put("tail", watch.tailId)
                    .put("connected", watch.connected)
                    .put("lastSync", watch.lastSyncLabel ?: "")
                    .put("source", watch.source),
            )
        }
        val editor = prefs.edit()
            .putString("watches", array.toString())
            .putString("selected_watch_id", selectedWatchId)
        val legacy = watches.firstOrNull { it.id == selectedWatchId } ?: watches.firstOrNull()
        if (legacy == null) {
            editor.remove("device")
        } else {
            val item = JSONObject()
                .put("bound", legacy.bound)
                .put("name", legacy.name)
                .put("tail", legacy.tailId)
                .put("connected", legacy.connected)
                .put("lastSync", legacy.lastSyncLabel ?: "")
            editor.putString("device", item.toString())
        }
        editor.apply()
    }

    private fun loadDevice(): WatchDevice {
        val raw = prefs.getString("device", null) ?: return WatchDevice()
        return try {
            val item = JSONObject(raw)
            WatchDevice(
                id = item.optString("tail"),
                bound = item.optBoolean("bound"),
                name = item.optString("name", "小云手表"),
                tailId = item.optString("tail"),
                connected = false,
                battery = null,
                lastSyncLabel = "等待手表连接",
            )
        } catch (_: Throwable) {
            WatchDevice()
        }
    }

    /** 兼容旧调用：保存全部手表记录。 */
    private fun saveDevice() = saveWatches()

    private fun loadPreferences(): CarePreferences {
        val raw = prefs.getString("preferences", null) ?: return CarePreferences()
        return try {
            val item = JSONObject(raw)
            CarePreferences(
                idleCare = item.optBoolean("idle", true),
                quietHours = item.optBoolean("quiet", true),
                quietStart = item.optString("quietStart", "22:00"),
                quietEnd = item.optString("quietEnd", "07:00"),
                shareHealthContext = item.optBoolean("share", false),
                vibration = item.optBoolean("vibration", true),
                familySharing = item.optBoolean("family", false),
            )
        } catch (_: Throwable) {
            CarePreferences()
        }
    }

    private fun savePreferences() {
        val item = JSONObject()
            .put("idle", preferences.idleCare)
            .put("quiet", preferences.quietHours)
            .put("quietStart", preferences.quietStart)
            .put("quietEnd", preferences.quietEnd)
            .put("share", preferences.shareHealthContext)
            .put("vibration", preferences.vibration)
            .put("family", preferences.familySharing)
        prefs.edit().putString("preferences", item.toString()).apply()
    }
}
