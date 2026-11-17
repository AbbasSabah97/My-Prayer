package com.abbassabah.myprayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

class PrayerCalculatorTest {
    private val zone = ZoneId.of("Asia/Baghdad")
    private val settings = PrayerSettings(method = "haqiba")

    @Test
    fun haqibaCompatibilitySampleMatchesRecoveredAstronomicalTimes() {
        val day = LocalDate.of(2026, 11, 3)
        val schedule = PrayerCalculator.schedule(day, 32.4721, 44.4217, zone, settings)
        assertNotNull(schedule)
        val events = schedule!!.events.associateBy { it.id }
        val expected = mapOf("FAJR" to 298, "sunrise" to 381, "DHUHR" to 706,
            "ASR" to 888, "sunset" to 1030, "MAGHRIB" to 1046, "ISHA" to 1094)
        expected.forEach { (key, minute) ->
            val actual = events[key]!!.time.hour * 60 + events[key]!!.time.minute
            assertTrue("$key expected near $minute, actual $actual", abs(actual - minute) <= 3)
        }
    }

    @Test
    fun fullThirtyDayCalendarHasEightEventsEveryDay() {
        val start = LocalDate.of(2026, 11, 3)
        repeat(30) { offset ->
            val schedule = PrayerCalculator.schedule(start.plusDays(offset.toLong()),
                32.4721, 44.4217, zone, settings)
            assertNotNull("day ${offset + 1}", schedule)
            assertEquals("day ${offset + 1}", 8, schedule!!.events.size)
            assertTrue(schedule.prayers.zipWithNext().all { it.first.time < it.second.time })
            assertTrue(schedule.sunset < schedule.midnight)
        }
    }

    @Test
    fun allMonthsCalculateInBabylon() {
        for (month in 1..12) {
            val schedule = PrayerCalculator.schedule(LocalDate.of(2026, month, 15),
                32.4721, 44.4217, zone, settings)
            assertNotNull("month $month", schedule)
            assertEquals(8, schedule!!.events.size)
        }
    }

    @Test
    fun customFajrAngleChangesFajrTime() {
        val day = LocalDate.of(2026, 11, 3)
        val default = PrayerCalculator.schedule(day, 32.4721, 44.4217, zone, settings)!!
        val custom = PrayerCalculator.schedule(day, 32.4721, 44.4217, zone,
            settings.copy(customFajrAngle = 16.0))!!
        assertTrue(custom.prayers.first { it.prayer == PrayerKey.FAJR }.time >
            default.prayers.first { it.prayer == PrayerKey.FAJR }.time)
    }

    @Test
    fun invalidCoordinatesAreRejected() {
        assertEquals(null, PrayerCalculator.schedule(LocalDate.of(2026, 11, 3),
            120.0, 44.0, zone, settings))
    }
}
