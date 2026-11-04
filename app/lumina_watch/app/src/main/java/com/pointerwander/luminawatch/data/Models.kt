package com.pointerwander.luminawatch.data

enum class Metric { HEART_RATE, OXYGEN }

/** 手机端手动录入的步数，独立于尚未接入的手表同步数据。 */
data class ManualStepEntry(val timestamp: Long, val steps: Int)

/** 手机端手动录入的心率；独立于尚未接入的手表同步数据。 */
data class ManualHeartEntry(val timestamp: Long, val bpm: Int)

/** 手机端手动录入的血氧；独立于尚未接入的手表同步数据。 */
data class ManualOxygenEntry(val timestamp: Long, val percent: Int)

/** 本机保存的一次体能训练；source=local_demo 表示演示数据。 */
data class WorkoutSession(
    val id: Long,
    val type: String,
    val startMillis: Long,
    val durationSeconds: Int,
    val distanceMeters: Float,
    val calories: Int,
    val avgHeartRate: Int,
    val steps: Int,
    val source: String = "local_demo",
)

/** 健康首页可置顶的项目；只控制首页展示，不删除底层记录。 */
enum class SummaryItem(val label: String) {
    STEPS("步数"),
    HEART("心率"),
    OXYGEN("血氧"),
    STANDING("站立活动"),
    CALORIES("活动消耗"),
    RUNNING("跑步"),
    MOOD("心理状态"),
    CARE("小云关怀"),
}

enum class SampleSource(val label: String) {
    DEMO("本机预览"),
    WATCH("手表数据"),
}

data class HealthSample(
    val metric: Metric,
    val value: Int,
    val timeLabel: String,
    val source: SampleSource = SampleSource.DEMO,
)

enum class MoodScope(val label: String) { MOMENT("此刻"), DAY("今日") }

data class MoodEntry(
    val id: Long,
    val epochDay: Long,
    val scope: MoodScope,
    /** 0f 很不愉快 … 1f 很愉快，保存连续值，不取整。 */
    val value: Float,
    val words: List<String>,
    val factors: List<String>,
    val note: String,
    val timeLabel: String,
)

/** 两份量表独立计分，未作答的量表使用空列表。 */
data class AssessmentRecord(
    val id: Long,
    val timestamp: Long,
    val gadAnswers: List<Int>,
    val phqAnswers: List<Int>,
) {
    val gadScore: Int? get() = gadAnswers.takeIf { it.size == 7 }?.sum()
    val phqScore: Int? get() = phqAnswers.takeIf { it.size == 9 }?.sum()
}

enum class CareKind(val label: String) {
    HEART("心率"),
    OXYGEN("血氧"),
    MOOD("心情"),
    IDLE("久坐"),
    REMINDER("提醒"),
    MANUAL("手动"),
}

data class CareEvent(
    val id: Long,
    val kind: CareKind,
    val title: String,
    val detail: String,
    val timeLabel: String,
    val acknowledged: Boolean = false,
    val followUpMinutes: Int? = null,
    val contextAuthorized: Boolean = false,
    val demo: Boolean = true,
)

data class Alarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String,
    val repeatDays: Set<Int>,
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val announce: Boolean = false,
    val synced: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val timeText: String get() = "%02d:%02d".format(hour, minute)
}

data class WatchDevice(
    /** 本机唯一记录 ID：手动 ID 或蓝牙地址；不代表已完成真实配对。 */
    val id: String = "",
    val bound: Boolean = false,
    val name: String = "小云手表",
    val tailId: String = "",
    val connected: Boolean = false,
    val battery: Int? = null,
    val lastSyncLabel: String? = null,
    /** manual / bluetooth，仅用于展示来源。 */
    val source: String = "manual",
)

data class CarePreferences(
    val idleCare: Boolean = true,
    val quietHours: Boolean = true,
    val quietStart: String = "22:00",
    val quietEnd: String = "07:00",
    val shareHealthContext: Boolean = false,
    val vibration: Boolean = true,
    val familySharing: Boolean = false,
)

enum class MessageStatus { SENT, SENDING, FAILED }

data class ChatMessage(
    val id: Long,
    val text: String,
    val fromCloud: Boolean,
    val source: String,
    val timeLabel: String,
    val status: MessageStatus = MessageStatus.SENT,
    /** 本地图片路径（file:// 或 content://）；仅本机演示，未上传云岸。 */
    val imageUri: String? = null,
)

enum class AddAlarmResult { OK, LIMIT, INVALID }

const val MAX_ALARMS = 15
