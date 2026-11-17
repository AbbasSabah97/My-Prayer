package com.abbassabah.myprayer

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

data class PrayerEntry(val prayer: PrayerKey, val time: ZonedDateTime)
data class TimeEvent(
    val id: String, val title: String, val symbol: String,
    val time: ZonedDateTime, val detail: String, val prayer: PrayerKey? = null
)
data class DailySchedule(
    val date: LocalDate, val prayers: List<PrayerEntry>,
    val sunrise: ZonedDateTime, val sunset: ZonedDateTime, val midnight: ZonedDateTime,
    val asrVirtueStart: ZonedDateTime?, val asrVirtuePreferred: ZonedDateTime?,
    val asrVirtueEnd: ZonedDateTime?, val conventionalAsr: ZonedDateTime,
    val asrUsesVirtue: Boolean, val events: List<TimeEvent>
)
private data class RawPrayerDay(
    val fajr: Double, val sunrise: Double, val dhuhr: Double, val asr: Double,
    val sunset: Double, val maghrib: Double, val isha: Double
)

object PrayerCalculator {
    private const val SUNRISE_ANGLE = 0.833
    private const val ASR_FACTOR = 1.0

    fun zoneFor(cityId: String): ZoneId =
        if (cityId == "auto") ZoneId.systemDefault() else ZoneId.of("Asia/Baghdad")

    fun schedule(date: LocalDate, latitude: Double, longitude: Double, zone: ZoneId,
                 settings: PrayerSettings): DailySchedule? {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val today = rawDay(date, latitude, longitude, zone, settings) ?: return null
        val tomorrow = rawDay(date.plusDays(1), latitude, longitude, zone, settings) ?: return null
        val virtueStart = shadowIncreaseTime(latitude, today.dhuhr, 2.0 / 7.0, date, zone)
        val virtuePreferred = shadowIncreaseTime(latitude, today.dhuhr, 4.0 / 7.0, date, zone)
        val virtueEnd = shadowIncreaseTime(latitude, today.dhuhr, 6.0 / 7.0, date, zone)
        val useVirtue = settings.asrMode() == "virtue" && virtueStart != null

        val prayerTimes = listOf(
            PrayerKey.FAJR to today.fajr,
            PrayerKey.DHUHR to today.dhuhr,
            PrayerKey.ASR to if (useVirtue) virtueStart!!.toLocalHour(date, zone) else today.asr,
            PrayerKey.MAGHRIB to today.maghrib,
            PrayerKey.ISHA to today.isha
        ).map { (prayer, hour) ->
            PrayerEntry(prayer, roundUpToMinute(localDateTime(date, hour + settings.correction(prayer) / 60.0, zone)))
        }

        val nextFajr = roundUpToMinute(localDateTime(
            date.plusDays(1), tomorrow.fajr + settings.correction(PrayerKey.FAJR) / 60.0, zone
        ))
        val sunsetDate = localDateTime(date, today.sunset, zone)
        val midpointMillis = sunsetDate.toInstant().toEpochMilli() +
            (nextFajr.toInstant().toEpochMilli() - sunsetDate.toInstant().toEpochMilli()) / 2L
        val midnight = roundToNearestMinute(Instant.ofEpochMilli(midpointMillis).atZone(zone))

        val sunriseDate = roundUpToMinute(localDateTime(date, today.sunrise, zone))
        val sunsetRounded = roundUpToMinute(sunsetDate)
        val startDate = virtueStart?.let { roundUpToMinute(it) }
        val preferredDate = virtuePreferred?.let { roundUpToMinute(it) }
        val endDate = virtueEnd?.let { roundUpToMinute(it) }
        val conventional = roundUpToMinute(localDateTime(date, today.asr, zone))

        val events = mutableListOf<TimeEvent>()
        prayerTimes.forEach { entry ->
            val detail = when (entry.prayer) {
                PrayerKey.ASR -> if (useVirtue) "بداية فضيلة العصر — بعد أداء الظهر"
                    else "حساب ظل المثل — ليس بداية جواز العصر"
                PrayerKey.MAGHRIB -> "تقدير زوال الحمرة المشرقية — تحقق محليًا"
                PrayerKey.ISHA -> "تقدير انحسار الشفق — لا يمثل بداية الجواز"
                else -> "وقت الصلاة المحسوب"
            }
            events += TimeEvent(entry.prayer.name, entry.prayer.title, entry.prayer.symbol, entry.time, detail, entry.prayer)
        }
        events += TimeEvent("sunrise", "شروق الشمس", "☀", sunriseDate, "حدث فلكي — ليس صلاة")
        events += TimeEvent("sunset", "غروب الشمس", "◒", sunsetRounded, "الغروب الفلكي — قد يسبق المغرب")
        events += TimeEvent("midnight", "منتصف الليل الشرعي", "☾", midnight, "منتصف الغروب والفجر التالي")

        return DailySchedule(date, prayerTimes, sunriseDate, sunsetRounded, midnight,
            startDate, preferredDate, endDate, conventional, useVirtue,
            events.sortedBy { it.time.toInstant() })
    }

    private fun rawDay(date: LocalDate, latitude: Double, longitude: Double,
                       zone: ZoneId, settings: PrayerSettings): RawPrayerDay? {
        val method = settings.baseAngles()
        val fajrAngle = settings.fajrAngle()
        val maghribAngle = settings.maghribAngle()
        val ishaAngle = settings.ishaAngle()
        if (fajrAngle !in 0.0..30.0 || maghribAngle !in 0.0..30.0 || ishaAngle !in 0.0..30.0) return null
        val noonInstant = date.atTime(12, 0).atZone(zone).toInstant()
        val zoneHours = zone.rules.getOffset(noonInstant).totalSeconds / 3600.0
        val jd = julianDate(date) - longitude / 360.0

        fun noon(t: Double): Double = mod(12.0 - sunPosition(jd + t).second, 24.0)
        fun angleTime(angle: Double, t: Double): Double {
            val declination = sunPosition(jd + t).first
            val denominator = cosD(declination) * cosD(latitude)
            if (abs(denominator) < 1e-12) return Double.NaN
            val cosine = (-sinD(angle) - sinD(declination) * sinD(latitude)) / denominator
            if (!cosine.isFinite() || cosine !in -1.0..1.0) return Double.NaN
            val hourAngle = degrees(acos(cosine)) / 15.0
            return noon(t) + if (angle > 90.0) -hourAngle else hourAngle
        }
        fun asr(t: Double): Double {
            val declination = sunPosition(jd + t).first
            val altitude = -degrees(atan2(1.0, ASR_FACTOR + tanD(abs(latitude - declination))))
            return angleTime(altitude, t)
        }

        val guesses = doubleArrayOf(5, 6, 12, 13, 18, 18, 18).map { it / 24.0 }
        var fajr = angleTime(180.0 - fajrAngle, guesses[0])
        val sunrise = angleTime(180.0 - SUNRISE_ANGLE, guesses[1])
        val dhuhr = noon(guesses[2])
        val asrHour = asr(guesses[3])
        val sunset = angleTime(SUNRISE_ANGLE, guesses[4])
        var maghrib = if (maghribAngle == 0.0) sunset else angleTime(maghribAngle, guesses[5])
        val ishaInterval = if (settings.customIshaAngle != null) null else method.ishaIntervalMinutes
        var isha = if (ishaInterval != null) maghrib + ishaInterval / 60.0
            else angleTime(ishaAngle, guesses[6])

        val correction = zoneHours - longitude / 15.0
        fajr += correction
        val sunriseCorrected = sunrise + correction
        val dhuhrCorrected = dhuhr + correction
        val asrCorrected = asr + correction
        val sunsetCorrected = sunset + correction
        maghrib += correction
        isha += correction

        if (sunriseCorrected.isFinite() && sunsetCorrected.isFinite()) {
            val night = mod(sunriseCorrected - sunsetCorrected, 24.0)
            val fajrMaximum = fajrAngle / 60.0 * night
            if (!fajr.isFinite() || mod(sunriseCorrected - fajr, 24.0) > fajrMaximum) {
                fajr = sunriseCorrected - fajrMaximum
            }
            if (ishaInterval == null) {
                val ishaMaximum = ishaAngle / 60.0 * night
                if (!isha.isFinite() || mod(isha - sunsetCorrected, 24.0) > ishaMaximum) {
                    isha = sunsetCorrected + ishaMaximum
                }
            }
            if (maghribAngle > 0.0) {
                val maghribMaximum = maghribAngle / 60.0 * night
                if (!maghrib.isFinite() || mod(maghrib - sunsetCorrected, 24.0) > maghribMaximum) {
                    maghrib = sunsetCorrected + maghribMaximum
                }
            }
        }

        val values = listOf(fajr, sunriseCorrected, dhuhrCorrected, asrCorrected, sunsetCorrected, maghrib, isha)
        if (values.any { !it.isFinite() }) return null
        return RawPrayerDay(fajr, sunriseCorrected, dhuhrCorrected, asrCorrected, sunsetCorrected, maghrib, isha)
    }

    private fun shadowIncreaseTime(latitude: Double, solarNoonHour: Double, increase: Double,
                                   date: LocalDate, zone: ZoneId): ZonedDateTime? {
        val solarNoon = localDateTime(date, solarNoonHour, zone)
        val dec = solarDeclination(solarNoon.toInstant())
        val lat = radians(latitude)
        val declination = radians(dec)
        val difference = abs(lat - declination)
        if (difference >= Math.PI / 2) return null
        val altitude = atan(1.0 / (tan(difference) + increase))
        val denominator = cos(lat) * cos(declination)
        if (abs(denominator) < 1e-12) return null
        val cosine = (sin(altitude) - sin(lat) * sin(declination)) / denominator
        if (!cosine.isFinite() || cosine !in -1.0..1.0) return null
        return solarNoon.plusNanos((acos(cosine) * 43_200_000_000_000.0 / Math.PI).toLong())
    }

    private fun solarDeclination(instant: Instant): Double {
        val jd = instant.toEpochMilli() / 86_400_000.0 + 2_440_587.5
        val t = (jd - 2_451_545.0) / 36_525.0
        val l = mod(280.46646 + t * (36_000.76983 + 0.0003032 * t), 360.0)
        val m = radians(357.52911 + t * (35_999.05029 - 0.0001537 * t))
        val c = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val omega = radians(125.04 - 1934.136 * t)
        val longitude = radians(l + c - 0.00569 - 0.00478 * sin(omega))
        val obliquity = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - 0.001813 * t))) / 60.0) / 60.0 +
            0.00256 * cos(omega)
        return degrees(asin(sin(radians(obliquity)) * sin(longitude)))
    }

    private fun julianDate(date: LocalDate): Double {
        var year = date.year
        var month = date.monthValue
        if (month <= 2) { year -= 1; month += 12 }
        val a = floor(year / 100.0)
        val b = 2.0 - a + floor(a / 4.0)
        return floor(365.25 * (year + 4716.0)) + floor(30.6001 * (month + 1.0)) +
            date.dayOfMonth + b - 1524.5
    }

    private fun sunPosition(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = mod(357.529 + 0.98560028 * d, 360.0)
        val q = mod(280.459 + 0.98564736 * d, 360.0)
        val longitude = mod(q + 1.915 * sinD(g) + 0.020 * sinD(2.0 * g), 360.0)
        val obliquity = 23.439 - 0.00000036 * d
        val declination = degrees(asin(sinD(obliquity) * sinD(longitude)))
        val rightAscension = mod(degrees(atan2(cosD(obliquity) * sinD(longitude), cosD(longitude))) / 15.0, 24.0)
        return declination to (q / 15.0 - rightAscension)
    }

    private fun localDateTime(date: LocalDate, hour: Double, zone: ZoneId): ZonedDateTime {
        val seconds = (mod(hour, 24.0) * 3600.0).toLong()
        return date.atStartOfDay(zone).plusSeconds(seconds)
    }
    private fun roundUpToMinute(value: ZonedDateTime): ZonedDateTime {
        val millis = value.toInstant().toEpochMilli()
        return Instant.ofEpochMilli(ceil(millis / 60_000.0).toLong() * 60_000L).atZone(value.zone)
    }
    private fun roundToNearestMinute(value: ZonedDateTime): ZonedDateTime {
        val millis = value.toInstant().toEpochMilli()
        return Instant.ofEpochMilli(kotlin.math.round(millis / 60_000.0).toLong() * 60_000L).atZone(value.zone)
    }
    private fun mod(value: Double, divisor: Double): Double {
        val result = value % divisor
        return if (result < 0) result + divisor else result
    }
    private fun radians(value: Double) = value * Math.PI / 180.0
    private fun degrees(value: Double) = value * 180.0 / Math.PI
    private fun sinD(value: Double) = sin(radians(value))
    private fun cosD(value: Double) = cos(radians(value))
    private fun tanD(value: Double) = tan(radians(value))
    private fun ZonedDateTime.toLocalHour(date: LocalDate, zone: ZoneId): Double =
        ChronoUnit.SECONDS.between(date.atStartOfDay(zone), this) / 3600.0
}
