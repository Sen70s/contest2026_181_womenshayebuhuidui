package com.pointerwander.luminawatch.data

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.pointerwander.luminawatch.MainActivity
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class HydrationReminderSettings(
    val enabled: Boolean = false,
    val startMinutes: Int = 9 * 60,
    val endMinutes: Int = 21 * 60,
    val intervalMinutes: Int = 90,
    val days: Set<Int> = (1..7).toSet(),
) {
    fun normalized(): HydrationReminderSettings = copy(
        startMinutes = startMinutes.coerceIn(0, 23 * 60),
        endMinutes = endMinutes.coerceIn((startMinutes + 30).coerceAtMost(23 * 60 + 59), 23 * 60 + 59),
        intervalMinutes = intervalMinutes.coerceIn(30, 360),
        days = days.filter { it in 1..7 }.toSet().ifEmpty { (1..7).toSet() },
    )

    fun nextTime(after: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        if (!enabled) return null
        val safe = normalized()
        val zone = after.zone
        for (offset in 0..7) {
            val date: LocalDate = after.toLocalDate().plusDays(offset.toLong())
            if (date.dayOfWeek.value !in safe.days) continue
            var minute = safe.startMinutes
            while (minute <= safe.endMinutes) {
                val time = LocalTime.of(minute / 60, minute % 60)
                val candidate = ZonedDateTime.of(date, time, zone)
                if (candidate.isAfter(after)) return candidate
                minute += safe.intervalMinutes
            }
        }
        return null
    }

    companion object {
        fun load(context: Context): HydrationReminderSettings {
            val prefs = context.getSharedPreferences("luminawatch_hydration", Context.MODE_PRIVATE)
            return HydrationReminderSettings(
                enabled = prefs.getBoolean("enabled", false),
                startMinutes = prefs.getInt("start_minutes", 9 * 60),
                endMinutes = prefs.getInt("end_minutes", 21 * 60),
                intervalMinutes = prefs.getInt("interval_minutes", 90),
                days = prefs.getString("days", "1,2,3,4,5,6,7").orEmpty()
                    .split(",").mapNotNull(String::toIntOrNull).toSet(),
            ).normalized()
        }

        fun save(context: Context, settings: HydrationReminderSettings) {
            val safe = settings.normalized()
            context.getSharedPreferences("luminawatch_hydration", Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", safe.enabled)
                .putInt("start_minutes", safe.startMinutes)
                .putInt("end_minutes", safe.endMinutes)
                .putInt("interval_minutes", safe.intervalMinutes)
                .putString("days", safe.days.sorted().joinToString(","))
                .apply()
        }
    }
}

object HydrationReminderScheduler {
    private const val ACTION_REMIND = "com.pointerwander.luminawatch.HYDRATION_REMIND"
    private const val CHANNEL_ID = "hydration_reminders"
    private const val REQUEST_CODE = 712

    fun refresh(context: Context, settings: HydrationReminderSettings) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(appContext, HydrationReminderReceiver::class.java).setAction(ACTION_REMIND)
        val pending = PendingIntent.getBroadcast(appContext, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.cancel(pending)
        val next = settings.nextTime() ?: return
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending)
    }

    internal fun onReceive(context: Context, action: String?) {
        if (action == ACTION_REMIND) {
            showNotification(context)
        }
        refresh(context, HydrationReminderSettings.load(context))
    }

    private fun showNotification(context: Context) {
        val settings = HydrationReminderSettings.load(context)
        if (!settings.enabled) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "喝水提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val openApp = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("喝水提醒")
            .setContentText("记得喝点水，按自己的节奏补充水分。")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(REQUEST_CODE, notification)
    }
}

class HydrationReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HydrationReminderScheduler.onReceive(context, intent.action)
    }
}
