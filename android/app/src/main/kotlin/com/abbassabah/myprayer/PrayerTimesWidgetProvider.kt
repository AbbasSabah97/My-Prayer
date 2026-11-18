package com.abbassabah.myprayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object PrayerWidgetUpdater {
    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, PrayerTimesWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isNotEmpty()) PrayerTimesWidgetProvider().onUpdate(context, manager, ids)
    }
}

class PrayerTimesWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        PrayerWidgetUpdater.update(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val settings = PrayerSettingsStore.load(context)
        val city = PrayerCities.byId(settings.cityId)
        val latitude = city?.latitude ?: PrayerLocationStore.latitude(context)
        val longitude = city?.longitude ?: PrayerLocationStore.longitude(context)
        val zone = PrayerCalculator.zoneFor(settings.cityId)
        val today = Instant.now().atZone(zone).toLocalDate()
        val schedule = PrayerCalculator.schedule(today, latitude, longitude, zone, settings)
        val prayers = schedule?.prayers?.associateBy { it.prayer }.orEmpty()
        val names = listOf("الفجر", "الظهر", "العصر", "المغرب", "العشاء")
        val keys = PrayerKey.values()
        val icons = intArrayOf(
            R.drawable.ic_prayer_fajr, R.drawable.ic_prayer_dhuhr,
            R.drawable.ic_prayer_asr, R.drawable.ic_prayer_maghrib, R.drawable.ic_prayer_isha
        )
        val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale("ar", "IQ"))
        val dateFormat = DateTimeFormatter.ofPattern("EEEE، d MMMM", Locale("ar", "IQ"))
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (android.os.Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_prayer_times)
            views.setTextViewText(R.id.widget_title, "مواقيت الصلاة")
            views.setTextViewText(R.id.widget_date, today.format(dateFormat))
            views.setOnClickPendingIntent(R.id.widget_root, openApp)
            keys.forEachIndexed { index, key ->
                val entry = prayers[key]
                views.setTextViewText(
                    intArrayOf(R.id.time_fajr, R.id.time_dhuhr, R.id.time_asr, R.id.time_maghrib, R.id.time_isha)[index],
                    entry?.time?.format(timeFormat) ?: "--:--"
                )
                views.setTextViewText(
                    intArrayOf(R.id.name_fajr, R.id.name_dhuhr, R.id.name_asr, R.id.name_maghrib, R.id.name_isha)[index],
                    names[index]
                )
                views.setImageViewResource(
                    intArrayOf(R.id.icon_fajr, R.id.icon_dhuhr, R.id.icon_asr, R.id.icon_maghrib, R.id.icon_isha)[index],
                    icons[index]
                )
            }
            manager.updateAppWidget(id, views)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        onUpdate(context, manager, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        }
    }
}
