package com.abbassabah.myprayer

import android.content.Context
import org.json.JSONObject

enum class PrayerKey(val title: String, val symbol: String) {
    FAJR("الفجر", "sunrise.fill"), DHUHR("الظهر", "sun.max.fill"), ASR("العصر", "sun.haze.fill"),
    MAGHRIB("المغرب", "sunset.fill"), ISHA("العشاء", "moon.stars.fill")
}
data class CityInfo(val id: String, val name: String, val latitude: Double, val longitude: Double)
object PrayerCities {
    val all = listOf(
        CityInfo("baghdad", "بغداد", 33.3152, 44.3661),
        CityInfo("hilla", "الحلة", 32.4721, 44.4217),
        CityInfo("karbala", "كربلاء", 32.6160, 44.0249),
        CityInfo("najaf", "النجف", 31.9996, 44.3267),
        CityInfo("basra", "البصرة", 30.5085, 47.7804)
    )
    fun byId(id: String) = all.firstOrNull { it.id == id }
}
data class MethodAngles(val fajr: Double, val maghrib: Double, val isha: Double, val ishaIntervalMinutes: Int? = null)
data class PrayerSettings(
    val cityId: String = "auto",
    val method: String = "haqiba",
    val enabled: Map<PrayerKey, Boolean> = emptyMap(),
    val offsets: Map<PrayerKey, Int> = emptyMap(),
    val advance: Int = 0,
    val sound: String = "default",
    val customFajrAngle: Double? = null,
    val customMaghribAngle: Double? = null,
    val customIshaAngle: Double? = null,
    val asrModeOverride: String? = null
) {
    fun active(prayer: PrayerKey) = enabled[prayer] ?: true
    fun correction(prayer: PrayerKey) = offsets[prayer] ?: 0
    fun baseAngles(): MethodAngles = when (method) {
        "haqiba" -> MethodAngles(18.0, 4.0, 14.0)
        "tehran" -> MethodAngles(17.7, 4.5, 14.0)
        "mwl" -> MethodAngles(18.0, 0.0, 17.0)
        "umm" -> MethodAngles(18.5, 0.0, 0.0, 90)
        "karachi" -> MethodAngles(18.0, 0.0, 18.0)
        else -> MethodAngles(16.0, 4.0, 14.0)
    }
    fun fajrAngle() = customFajrAngle ?: baseAngles().fajr
    fun maghribAngle() = customMaghribAngle ?: baseAngles().maghrib
    fun ishaAngle() = customIshaAngle ?: baseAngles().isha
    fun asrMode() = asrModeOverride ?: if (method == "jafari" || method == "tehran") "virtue" else "shadow"
    fun resetAngles() = copy(customFajrAngle = null, customMaghribAngle = null, customIshaAngle = null)
}
object PrayerSettingsStore {
    private const val PREFS = "my_prayer_android"
    private const val SETTINGS = "settings_json"
    fun load(context: Context): PrayerSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = try { JSONObject(prefs.getString(SETTINGS, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val enabledJson = root.optJSONObject("enabled") ?: JSONObject()
        val offsetsJson = root.optJSONObject("offsets") ?: JSONObject()
        return PrayerSettings(
            cityId = root.optString("cityId", "auto"),
            method = root.optString("method", "haqiba"),
            enabled = PrayerKey.values().associateWith { enabledJson.optBoolean(it.name, true) },
            offsets = PrayerKey.values().associateWith { offsetsJson.optInt(it.name, 0).coerceIn(-30, 30) },
            advance = root.optInt("advance", 0).coerceIn(0, 15),
            sound = root.optString("sound", "default"),
            customFajrAngle = root.optionalDouble("customFajrAngle"),
            customMaghribAngle = root.optionalDouble("customMaghribAngle"),
            customIshaAngle = root.optionalDouble("customIshaAngle"),
            asrModeOverride = root.optString("asrModeOverride").takeIf { it == "virtue" || it == "shadow" }
        )
    }
    fun save(context: Context, settings: PrayerSettings) {
        val enabled = JSONObject()
        val offsets = JSONObject()
        PrayerKey.values().forEach { enabled.put(it.name, settings.active(it)); offsets.put(it.name, settings.correction(it)) }
        val root = JSONObject().apply {
            put("cityId", settings.cityId); put("method", settings.method)
            put("enabled", enabled); put("offsets", offsets)
            put("advance", settings.advance); put("sound", settings.sound)
            put("customFajrAngle", settings.customFajrAngle ?: JSONObject.NULL)
            put("customMaghribAngle", settings.customMaghribAngle ?: JSONObject.NULL)
            put("customIshaAngle", settings.customIshaAngle ?: JSONObject.NULL)
            put("asrModeOverride", settings.asrModeOverride ?: JSONObject.NULL)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SETTINGS, root.toString()).apply()
    }
    private fun JSONObject.optionalDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key, Double.NaN).takeIf { it.isFinite() }
    }
}
object PrayerLocationStore {
    private const val PREFS = "my_prayer_android"
    fun latitude(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("last_latitude", 33.3152f).toDouble()
    fun longitude(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("last_longitude", 44.3661f).toDouble()
    fun label(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("location_label", "بغداد (افتراضي)") ?: "بغداد (افتراضي)"
    fun save(context: Context, latitude: Double, longitude: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("last_latitude", latitude.toFloat()).putFloat("last_longitude", longitude.toFloat())
            .putString("location_label", "موقعي الحالي").apply()
    }
}
