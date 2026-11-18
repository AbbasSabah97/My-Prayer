package com.abbassabah.myprayer

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PrayerTimesWidgetTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = AppWidgetManager.getInstance(context)
    private val shadowManager get() = shadowOf(manager)

    @Before
    fun clearSettings() {
        context.getSharedPreferences("my_prayer_android", 0).edit().clear().commit()
    }

    @Test
    fun providerIsRegisteredAsAnExportedHomeScreenWidget() {
        val component = ComponentName(context, PrayerTimesWidgetProvider::class.java)
        val receiver = context.packageManager.getReceiverInfo(component, PackageManager.GET_META_DATA)
        assertTrue("Widget receiver must be exported for the launcher", receiver.exported)
        assertNotNull("Widget provider metadata is missing", receiver.metaData)
        assertTrue("Widget provider XML resource is missing",
            receiver.metaData!!.getInt("android.appwidget.provider") != 0)
        val updateReceivers = context.packageManager.queryBroadcastReceivers(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(context.packageName), 0
        )
        val bootReceivers = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName), 0
        )
        assertTrue(updateReceivers.any { it.activityInfo.name == component.className })
        assertTrue(bootReceivers.any { it.activityInfo.name == component.className })
    }

    @Test
    fun widgetRendersAllFivePrayerNamesTimesAndIcons() {
        PrayerSettingsStore.save(context, PrayerSettings(cityId = "hilla", method = "haqiba"))
        val widgetId = shadowManager.createWidget(
            PrayerTimesWidgetProvider::class.java, R.layout.widget_prayer_times)
        PrayerTimesWidgetProvider().onUpdate(context, manager, intArrayOf(widgetId))
        val view = shadowManager.getViewFor(widgetId)

        assertTrue(view.findViewById<TextView>(R.id.widget_title).text.isNotBlank())
        assertTrue(view.findViewById<TextView>(R.id.widget_date).text.isNotBlank())
        val nameIds = listOf(R.id.name_fajr, R.id.name_dhuhr, R.id.name_asr,
            R.id.name_maghrib, R.id.name_isha)
        val expectedNames = listOf("الفجر", "الظهر", "العصر", "المغرب", "العشاء")
        nameIds.forEachIndexed { index, id ->
            assertTrue(view.findViewById<TextView>(id).text.toString() == expectedNames[index])
        }
        val timeIds = listOf(R.id.time_fajr, R.id.time_dhuhr, R.id.time_asr,
            R.id.time_maghrib, R.id.time_isha)
        timeIds.forEach { id ->
            val time = view.findViewById<TextView>(id).text.toString()
            assertTrue("Prayer time was not rendered for view $id: $time",
                time.isNotBlank() && time != "--:--")
        }
        val iconIds = listOf(R.id.icon_fajr, R.id.icon_dhuhr, R.id.icon_asr,
            R.id.icon_maghrib, R.id.icon_isha)
        iconIds.forEach { id ->
            assertNotNull("Prayer icon drawable missing for view $id",
                view.findViewById<ImageView>(id).drawable)
        }
        assertNotNull(view.findViewById<TextView>(R.id.widget_refresh))
    }

    @Test
    fun savingNewPrayerCalculationSettingsRefreshesExistingWidget() {
        PrayerSettingsStore.save(context, PrayerSettings(cityId = "hilla", method = "haqiba"))
        val widgetId = shadowManager.createWidget(
            PrayerTimesWidgetProvider::class.java, R.layout.widget_prayer_times)
        PrayerTimesWidgetProvider().onUpdate(context, manager, intArrayOf(widgetId))
        val view = shadowManager.getViewFor(widgetId)
        val before = view.findViewById<TextView>(R.id.time_fajr).text.toString()

        PrayerSettingsStore.save(context, PrayerSettings(
            cityId = "hilla", method = "haqiba", customFajrAngle = 16.0))
        val after = shadowManager.getViewFor(widgetId)
            .findViewById<TextView>(R.id.time_fajr).text.toString()
        assertNotEquals("Widget did not refresh after settings changed", before, after)
    }

    @Test
    fun manualRefreshBroadcastUpdatesExistingWidget() {
        PrayerSettingsStore.save(context, PrayerSettings(cityId = "hilla", method = "haqiba"))
        val widgetId = shadowManager.createWidget(
            PrayerTimesWidgetProvider::class.java, R.layout.widget_prayer_times)
        PrayerTimesWidgetProvider().onReceive(
            context,
            android.content.Intent(context, PrayerTimesWidgetProvider::class.java)
                .setAction(PrayerTimesWidgetProvider.ACTION_REFRESH_WIDGET)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        )
        val view = shadowManager.getViewFor(widgetId)
        assertTrue(view.findViewById<TextView>(R.id.time_fajr).text.isNotBlank())
    }
}
