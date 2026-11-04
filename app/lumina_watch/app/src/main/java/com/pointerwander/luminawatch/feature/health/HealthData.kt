package com.pointerwander.luminawatch.feature.health

import com.pointerwander.luminawatch.data.Metric

enum class Period(val label: String) { DAY("日"), WEEK("周"), MONTH("月"), YEAR("年") }

data class HealthPoint(val label: String, val value: Float)

enum class GuardLevel { STABLE, NOTICE, IMPORTANT }

data class GuardResult(val level: GuardLevel, val title: String, val message: String)

fun guardResult(metric: Metric, value: Float): GuardResult = if (metric == Metric.HEART_RATE) {
    when {
        value < 50f || value > 120f -> GuardResult(
            GuardLevel.IMPORTANT,
            "小云发现一次明显变化",
            "请先停止活动、安静休息后复测。若同时感到胸闷、晕厥或明显不适，请及时寻求专业帮助。",
        )
        value < 60f || value > 100f -> GuardResult(
            GuardLevel.NOTICE,
            "小云想请你再测一次",
            "这次读数超出常见静息参考区间。运动、情绪、睡眠与药物都可能影响心率，请结合当时状态查看。",
        )
        else -> GuardResult(
            GuardLevel.STABLE,
            "小云守护中",
            "当前选中的心率记录未触发提醒。连接手表后，小云会继续留意变化。",
        )
    }
} else {
    when {
        value <= 92f -> GuardResult(
            GuardLevel.IMPORTANT,
            "小云建议立即复测",
            "请保持手腕稳定并确认佩戴正确。若读数持续偏低，或伴有呼吸困难、胸闷等不适，请及时寻求专业帮助。",
        )
        value < 95f -> GuardResult(
            GuardLevel.NOTICE,
            "小云发现血氧读数偏低",
            "先安静坐好、调整佩戴后再次测量；不要只依据一次可穿戴设备读数判断身体状况。",
        )
        else -> GuardResult(
            GuardLevel.STABLE,
            "小云守护中",
            "当前选中的血氧记录未触发提醒。连接手表后，小云会关注读数趋势和你的感受。",
        )
    }
}

private val heartDay = listOf(
    "08:00" to 68f, "10:00" to 72f, "12:00" to 76f, "14:00" to 104f,
    "16:00" to 88f, "18:00" to 80f, "20:00" to 78f,
)
private val heartWeek = listOf(
    "周一" to 74f, "周二" to 76f, "周三" to 72f, "周四" to 81f,
    "周五" to 79f, "周六" to 75f, "周日" to 78f,
)
private val heartMonth = listOf(
    "第1周" to 73f, "第2周" to 76f, "第3周" to 80f, "第4周" to 77f, "本周" to 78f,
)
private val heartYear = listOf(
    "1月" to 72f, "2月" to 74f, "3月" to 73f, "4月" to 76f, "5月" to 75f, "6月" to 78f,
    "7月" to 79f, "8月" to 77f, "9月" to 76f, "10月" to 74f, "11月" to 73f, "12月" to 75f,
)

private val oxygenDay = listOf(
    "08:00" to 98f, "10:00" to 97f, "12:00" to 96f, "14:00" to 94f,
    "16:00" to 97f, "18:00" to 98f, "20:00" to 98f,
)
private val oxygenWeek = listOf(
    "周一" to 98f, "周二" to 97f, "周三" to 98f, "周四" to 96f,
    "周五" to 97f, "周六" to 98f, "周日" to 98f,
)
private val oxygenMonth = listOf(
    "第1周" to 98f, "第2周" to 97f, "第3周" to 97f, "第4周" to 98f, "本周" to 98f,
)
private val oxygenYear = listOf(
    "1月" to 98f, "2月" to 97f, "3月" to 98f, "4月" to 97f, "5月" to 98f, "6月" to 97f,
    "7月" to 98f, "8月" to 98f, "9月" to 97f, "10月" to 98f, "11月" to 98f, "12月" to 98f,
)

fun healthSeries(metric: Metric, period: Period): List<HealthPoint> {
    val raw = if (metric == Metric.HEART_RATE) {
        when (period) {
            Period.DAY -> heartDay
            Period.WEEK -> heartWeek
            Period.MONTH -> heartMonth
            Period.YEAR -> heartYear
        }
    } else {
        when (period) {
            Period.DAY -> oxygenDay
            Period.WEEK -> oxygenWeek
            Period.MONTH -> oxygenMonth
            Period.YEAR -> oxygenYear
        }
    }
    return raw.map { HealthPoint(it.first, it.second) }
}

/** 血氧按离散测量点展示，不画连续实时曲线；心率可以连线。 */
fun isDiscrete(metric: Metric): Boolean = metric == Metric.OXYGEN
