package com.abbassabah.myprayer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate

object PrayerNotificationScheduler {
    private const val PREFS = "my_prayer_android_alarms"
    private const val IDS = "scheduled_alarm_ids"
    private const val REFRESH_ID = 1_923_041
    private const val TEST_ID = 1_923_042
    private const val ACTION_PREFIX = "com.abbassabah.myprayer.PRAYER_ALARM."
    private const val ACTION_REFRESH = "com.abbassabah.myprayer.REFRESH_SCHEDULE"

    fun channelId(sound: String) = when (sound) {
        "soft" -> "prayer_soft_v1"
        "clear" -> "prayer_clear_v1"
        else -> "prayer_default_v1"
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val choices = listOf(
            Triple("prayer_default_v1", "تنبيهات الصلاة — الافتراضي", null),
            Triple("prayer_soft_v1", "تنبيهات الصلاة — نغمة هادئة", soundUri(context, R.raw.soft)),
            Triple("prayer_clear_v1", "تنبيهات الصلاة — نغمة واضحة", soundUri(context, R.raw.clear))
        )
        choices.forEach { (id, name, sound) ->
            if (manager.getNotificationChannel(id) == null) {
                manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "تذكيرات أوقات الصلاة من تطبيق صلاتي"
                    setSound(sound, audio)
                    enableVibration(true)
                })
            }
        }
    }

    private fun soundUri(context: Context, resource: Int): Uri =
        Uri.parse("android.resource://${context.packageName}/$resource")

    fun schedule(context: Context) {
        ensureChannels(context)
        val settings = PrayerSettingsStore.load(context)
        val zone = PrayerCalculator.zoneFor(settings.cityId)
        val city = PrayerCities.byId(settings.cityId)
        val latitude = city?.latitude ?: PrayerLocationStore.latitude(context)
        val longitude = city?.longitude ?: PrayerLocationStore.longitude(context)
        val now = System.currentTimeMillis()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        prefs.getStringSet(IDS, emptySet())?.toList()?.forEach { raw ->
            raw.toIntOrNull()?.let { id -> alarmPendingIntent(context, id)?.let(alarmManager::cancel) }
        }
        refreshPendingIntent(context)?.let(alarmManager::cancel)

        val ids = mutableSetOf<String>()
        val firstDay = LocalDate.now(zone)
        for (offset in 0 until 14) {
            val date = firstDay.plusDays(offset.toLong())
            val daily = PrayerCalculator.schedule(date, latitude, longitude, zone, settings) ?: continue
            daily.prayers.forEach prayerLoop@ { entry ->
                if (!settings.active(entry.prayer)) return@prayerLoop
                val eventTime = entry.time.toInstant().toEpochMilli()
                if (eventTime > now + 2_000L) {
                    val title = "حان وقت صلاة ${entry.prayer.title}"
                    val id = alarmId("${entry.prayer.name}-$eventTime")
                    scheduleOne(context, alarmManager, id, eventTime, title, "حي على الصلاة", settings.sound)
                    ids += id.toString()
                }
                if (settings.advance > 0) {
                    val early = eventTime - settings.advance * 60_000L
                    if (early > now + 2_000L) {
                        val title = "اقترب وقت صلاة ${entry.prayer.title}"
                        val id = alarmId("${entry.prayer.name}-early-$eventTime")
                        scheduleOne(context, alarmManager, id, early, title,
                            "بقي ${settings.advance} دقيقة على الصلاة", settings.sound)
                        ids += id.toString()
                    }
                }
            }
        }
        prefs.edit().putStringSet(IDS, ids).apply()

        val nextMidnight = firstDay.plusDays(1).atStartOfDay(zone).plusSeconds(10)
            .toInstant().toEpochMilli()
        val refreshIntent = Intent(context, PrayerAlarmReceiver::class.java)
            .setAction(ACTION_REFRESH).putExtra("refresh_schedule", true)
        val refresh = PendingIntent.getBroadcast(context, REFRESH_ID, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setAlarm(alarmManager, nextMidnight, refresh)
    }

    private fun scheduleOne(context: Context, manager: AlarmManager, id: Int, at: Long,
                            title: String, body: String, sound: String) {
        val intent = Intent(context, PrayerAlarmReceiver::class.java)
            .setAction(ACTION_PREFIX + id).putExtra("notification_id", id)
            .putExtra("title", title).putExtra("body", body).putExtra("sound", sound)
        val pending = PendingIntent.getBroadcast(context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setAlarm(manager, at, pending)
    }

    private fun setAlarm(manager: AlarmManager, at: Long, pending: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun alarmPendingIntent(context: Context, id: Int): PendingIntent? {
        val intent = Intent(context, PrayerAlarmReceiver::class.java).setAction(ACTION_PREFIX + id)
        return PendingIntent.getBroadcast(context, id, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun refreshPendingIntent(context: Context): PendingIntent? {
        val intent = Intent(context, PrayerAlarmReceiver::class.java).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(context, REFRESH_ID, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun alarmId(value: String) = value.hashCode()

    fun scheduleTest(context: Context, sound: String) {
        ensureChannels(context)
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, PrayerAlarmReceiver::class.java)
            .setAction("com.abbassabah.myprayer.TEST_NOTIFICATION")
            .putExtra("notification_id", TEST_ID).putExtra("title", "تجربة تنبيه الصلاة")
            .putExtra("body", "هذا تنبيه اختباري من تطبيق صلاتي").putExtra("sound", sound)
        val pending = PendingIntent.getBroadcast(context, TEST_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setAlarm(manager, System.currentTimeMillis() + 8_000L, pending)
    }

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun exactAlarmsAllowed(context: Context): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }
}

class PrayerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("refresh_schedule", false)) {
            PrayerNotificationScheduler.schedule(context)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        PrayerNotificationScheduler.ensureChannels(context)
        val title = intent.getStringExtra("title") ?: "تنبيه الصلاة"
        val body = intent.getStringExtra("body") ?: "حي على الصلاة"
        val sound = intent.getStringExtra("sound") ?: "default"
        val id = intent.getIntExtra("notification_id", 1)
        val openApp = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, PrayerNotificationScheduler.channelId(sound))
            .setSmallIcon(R.drawable.ic_stat_prayer).setContentTitle(title).setContentText(body)
            .setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).setContentIntent(openApp).build()
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
        } catch (_: SecurityException) {
            // Notification permission may have been revoked after scheduling.
        }
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PrayerNotificationScheduler.schedule(context)
    }
}
