package com.abbassabah.myprayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private val AppBackground = Color(0xFFF9F8F2)
private val AppDark = Color(0xFF1A3033)
private val AppTeal = Color(0xFF145E5E)
private val AppAqua = Color(0xFF2E827D)
private val AppGold = Color(0xFFD4A65C)
private val AppPale = Color(0xFFE8EFEB)
private val AppMuted = Color(0xFF687878)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(249, 248, 242)
        window.navigationBarColor = android.graphics.Color.rgb(249, 248, 242)
        if (Build.VERSION.SDK_INT >= 26) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        setContent {
            val scheme = lightColorScheme(
                primary = AppTeal, onPrimary = Color.White,
                secondary = AppAqua, background = AppBackground,
                surface = Color.White, onSurface = AppDark
            )
            MaterialTheme(colorScheme = scheme) { PrayerApp() }
        }
    }
}

@Composable
private fun PrayerApp() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(PrayerSettingsStore.load(context)) }
    var latitude by remember { mutableDoubleStateOf(PrayerLocationStore.latitude(context)) }
    var longitude by remember { mutableDoubleStateOf(PrayerLocationStore.longitude(context)) }
    var locationLabel by remember { mutableStateOf(PrayerLocationStore.label(context)) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf("today") }
    var showMonth by rememberSaveable { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notificationStatus by remember { mutableStateOf(PrayerNotificationScheduler.notificationsAllowed(context)) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            requestCurrentLocation(context) { lat, lon ->
                latitude = lat
                longitude = lon
                locationLabel = "موقعي الحالي"
                locationMessage = null
                PrayerLocationStore.save(context, lat, lon)
            }
        } else {
            locationMessage = "لم تمنح إذن الموقع. يمكنك اختيار مدينة عراقية يدويًا."
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationStatus = PrayerNotificationScheduler.notificationsAllowed(context)
        if (granted) PrayerNotificationScheduler.schedule(context)
        else Toast.makeText(context, "لم يتم السماح بالإشعارات", Toast.LENGTH_SHORT).show()
    }

    val updateSettings: (PrayerSettings) -> Unit = { updated ->
        settings = updated
        PrayerSettingsStore.save(context, updated)
    }

    fun updateLocation() {
        if (settings.cityId != "auto") {
            locationMessage = "اختر «موقعي تلقائيًا» أولًا لاستخدام GPS."
            return
        }
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            requestCurrentLocation(context) { lat, lon ->
                latitude = lat
                longitude = lon
                locationLabel = "موقعي الحالي"
                locationMessage = null
                PrayerLocationStore.save(context, lat, lon)
            }
        } else {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationStatus = PrayerNotificationScheduler.notificationsAllowed(context)
            if (!notificationStatus) Toast.makeText(context,
                "فعّل إشعارات تطبيق صلاتي من إعدادات MIUI", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (settings.cityId == "auto") {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (fine || coarse) {
                requestCurrentLocation(context) { lat, lon ->
                    latitude = lat
                    longitude = lon
                    locationLabel = "موقعي الحالي"
                    PrayerLocationStore.save(context, lat, lon)
                }
            } else {
                locationPermissionLauncher.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            notificationStatus = PrayerNotificationScheduler.notificationsAllowed(context)
            delay(1_000)
        }
    }
    LaunchedEffect(settings, latitude, longitude) {
        withContext(Dispatchers.Default) { PrayerNotificationScheduler.schedule(context) }
    }

    val zone = remember(settings.cityId) { PrayerCalculator.zoneFor(settings.cityId) }
    val dateTime = remember(nowMillis, zone) { Instant.ofEpochMilli(nowMillis).atZone(zone) }
    val today = dateTime.toLocalDate()
    val city = PrayerCities.byId(settings.cityId)
    val actualLatitude = city?.latitude ?: latitude
    val actualLongitude = city?.longitude ?: longitude
    val daily = remember(today, actualLatitude, actualLongitude, settings, zone) {
        PrayerCalculator.schedule(today, actualLatitude, actualLongitude, zone, settings)
    }
    val tomorrow = remember(today, actualLatitude, actualLongitude, settings, zone) {
        PrayerCalculator.schedule(today.plusDays(1), actualLatitude, actualLongitude, zone, settings)
    }
    val nextPrayer = remember(daily, tomorrow, nowMillis) {
        (daily?.prayers.orEmpty() + tomorrow?.prayers.orEmpty())
            .filter { it.time.toInstant().toEpochMilli() > nowMillis }
            .minByOrNull { it.time.toInstant() }
    }
    val cityName = city?.name ?: locationLabel

    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        if (showMonth) {
            MonthScreen(today, actualLatitude, actualLongitude, zone, settings) { showMonth = false }
        } else {
            Scaffold(
                containerColor = AppBackground,
                bottomBar = {
                    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
                        NavigationBarItem(selectedTab == "today", { selectedTab = "today" },
                            icon = { Text("☾", fontSize = 21.sp) }, label = { Text("الصلاة") })
                        NavigationBarItem(selectedTab == "alerts", { selectedTab = "alerts" },
                            icon = { Text("♧", fontSize = 20.sp) }, label = { Text("التنبيهات") })
                        NavigationBarItem(selectedTab == "settings", { selectedTab = "settings" },
                            icon = { Text("⚙", fontSize = 20.sp) }, label = { Text("الإعدادات") })
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (selectedTab) {
                        "alerts" -> AlertsScreen(
                            settings, updateSettings, notificationStatus, ::requestNotifications,
                            { PrayerNotificationScheduler.scheduleTest(context, settings.sound) },
                            {
                                if (Build.VERSION.SDK_INT >= 31) {
                                    try {
                                        context.startActivity(Intent(
                                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                            Uri.parse("package:${context.packageName}")
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "افتح إعدادات التنبيهات الدقيقة يدويًا", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                        "settings" -> OptionsScreen(settings, updateSettings, cityName, locationMessage, ::updateLocation)
                        else -> TodayScreen(dateTime, cityName, daily, nextPrayer, locationMessage,
                            { showMonth = true }, { selectedTab = "settings" }, ::updateLocation)
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun requestCurrentLocation(context: Context, onResult: (Double, Double) -> Unit) {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val delivered = AtomicBoolean(false)
    fun deliver(location: Location?) {
        if (location != null && delivered.compareAndSet(false, true)) onResult(location.latitude, location.longitude)
    }
    listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).forEach { provider ->
        try {
            if (manager.isProviderEnabled(provider)) {
                deliver(manager.getLastKnownLocation(provider))
                manager.getCurrentLocation(provider, null, context.mainExecutor) { deliver(it) }
            }
        } catch (_: SecurityException) {
            // The other provider may still be available with coarse permission.
        } catch (_: Exception) {
            // Keep the saved city/location if this provider is unavailable.
        }
    }
}

@Composable
private fun TodayScreen(
    now: ZonedDateTime, cityName: String, daily: DailySchedule?,
    nextPrayer: PrayerEntry?, locationMessage: String?,
    onMonth: () -> Unit, onSettings: () -> Unit, onLocation: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("صلاتي", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = AppDark)
                    Text(formatDay(now.toLocalDate()), fontSize = 13.sp, color = AppMuted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onSettings) {
                        Text("⌖  $cityName", maxLines = 1, overflow = TextOverflow.Ellipsis, color = AppTeal)
                    }
                    TextButton(onClick = onLocation) { Text("تحديث الموقع", fontSize = 11.sp, color = AppAqua) }
                }
            }
        }
        item { NextPrayerCard(now, nextPrayer) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("مواقيت الصلاة والشمس", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = AppDark, modifier = Modifier.weight(1f))
                TextButton(onClick = onMonth) { Text("جدول 30 يوماً", color = AppTeal, fontSize = 12.sp) }
            }
        }
        if (daily == null) item { Panel { Text("لم يمكن حساب المواقيت لهذا الموقع. تحقق من إحداثيات المدينة.") } }
        else {
            items(daily.events, key = { it.id }) { event -> TimeEventRow(event) }
            item { AsrVirtuePanel(daily) }
        }
        item {
            Text("الأوقات حسابات فلكية تقديرية. زاوية الفجر والمغرب والعشاء لا تغني عن التحقق من العلامات الشرعية والتقويم المحلي.",
                fontSize = 11.sp, color = AppMuted, lineHeight = 17.sp)
            if (locationMessage != null) {
                Spacer(Modifier.height(6.dp))
                Text(locationMessage, color = Color(0xFFB56A26), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun NextPrayerCard(now: ZonedDateTime, nextPrayer: PrayerEntry?) {
    val remaining = nextPrayer?.let {
        Duration.between(now.toInstant(), it.time.toInstant()).seconds.coerceAtLeast(0)
    } ?: 0L
    val hours = remaining / 3600
    val minutes = (remaining % 3600) / 60
    val seconds = remaining % 60
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(25.dp),
        colors = CardDefaults.cardColors(containerColor = AppTeal)) {
        Column(Modifier.background(Brush.linearGradient(listOf(AppTeal, AppAqua))).padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("✦  الصلاة القادمة", color = Color.White.copy(alpha = .84f), fontSize = 13.sp)
                    Text(nextPrayer?.prayer?.title ?: "—", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                    Text(nextPrayer?.let { formatTime(it.time) } ?: "—", color = Color.White.copy(alpha = .9f), fontSize = 17.sp)
                }
                Text("☾", color = AppGold, fontSize = 56.sp)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = .25f))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الوقت المتبقي", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds),
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TimeEventRow(event: TimeEvent) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(43.dp).background(AppPale, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(event.symbol, color = AppTeal, fontSize = 23.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(event.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppDark)
                Text(event.detail, fontSize = 10.sp, color = AppMuted, lineHeight = 14.sp)
            }
            Text(formatTime(event.time), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppDark)
        }
    }
}

@Composable
private fun AsrVirtuePanel(daily: DailySchedule) {
    val start = daily.asrVirtueStart ?: return
    val preferred = daily.asrVirtuePreferred ?: return
    val end = daily.asrVirtueEnd ?: return
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("فضيلة العصر وفق زيادة الظل", color = AppTeal, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                VirtueTime("البداية ٢/٧", start)
                VirtueTime("الأفضل ٤/٧", preferred)
                VirtueTime("النهاية ٦/٧", end)
            }
            Text("طريقة ظل المثل: ${formatTime(daily.conventionalAsr)}. وقت الفضيلة ليس بداية جواز العصر؛ تجب مراعاة ترتيب الظهر والعصر.",
                fontSize = 11.sp, color = AppMuted, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun VirtueTime(label: String, time: ZonedDateTime) {
    Column {
        Text(label, fontSize = 10.sp, color = AppMuted)
        Text(formatTime(time), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppDark)
    }
}

@Composable
private fun MonthScreen(
    today: LocalDate, latitude: Double, longitude: Double, zone: ZoneId,
    settings: PrayerSettings, onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(AppBackground)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("مواقيت 30 يوماً", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppDark)
                Text("جميع الأيام — اسحب عموديًا لعرض الجدول كاملًا", fontSize = 11.sp, color = AppMuted)
            }
            TextButton(onClick = onClose) { Text("إغلاق", color = AppTeal) }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items((0 until 30).toList()) { offset ->
                val date = today.plusDays(offset.toLong())
                MonthDayCard(offset + 1, date,
                    PrayerCalculator.schedule(date, latitude, longitude, zone, settings))
            }
            item {
                Text("نهاية جدول الثلاثين يومًا", modifier = Modifier.fillMaxWidth().padding(16.dp),
                    textAlign = TextAlign.Center, color = AppMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MonthDayCard(dayNumber: Int, date: LocalDate, schedule: DailySchedule?) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatDay(date), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppDark)
                    Text("حساب محلي — ${schedule?.events?.size ?: 0} أحداث", fontSize = 10.sp, color = AppMuted)
                }
                Surface(color = AppPale, shape = RoundedCornerShape(50)) {
                    Text("$dayNumber / 30", modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        color = AppTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (schedule == null || schedule.events.size != 8) {
                Text("تعذر حساب مواقيت هذا اليوم.", color = Color(0xFFB56A26), fontSize = 12.sp)
            } else {
                schedule.events.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { event -> TimeCell(event, Modifier.weight(1f)) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeCell(event: TimeEvent, modifier: Modifier = Modifier) {
    Row(modifier.background(AppBackground, RoundedCornerShape(12.dp)).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(event.symbol, color = AppAqua, fontSize = 17.sp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(event.title, color = AppMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatTime(event.time), color = AppDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AlertsScreen(
    settings: PrayerSettings, onSettings: (PrayerSettings) -> Unit,
    notificationsAllowed: Boolean, onRequestNotifications: () -> Unit,
    onTest: () -> Unit, onRequestExactAlarm: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            Text("تنبيهات الصلاة", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = AppDark)
            Text("اختر الصلوات التي تريد تذكيرك عند دخول وقتها.", fontSize = 13.sp, color = AppMuted)
        }
        item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (notificationsAllowed) "✓ الإشعارات مفعّلة" else "الإشعارات غير مفعّلة",
                        color = AppTeal, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (!notificationsAllowed) {
                        Button(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
                            Text("السماح بالإشعارات")
                        }
                    }
                    Button(onClick = onTest, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppAqua)) {
                        Text("اختبار الإشعار بعد 8 ثوانٍ")
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        OutlinedButton(onClick = onRequestExactAlarm, modifier = Modifier.fillMaxWidth()) {
                            Text("إعداد السماح بالتنبيهات الدقيقة")
                        }
                    }
                    Text("تُجدول التنبيهات للأيام الـ14 القادمة وتُجدّد يوميًا. في MIUI فعّل التشغيل التلقائي وأزل قيود البطارية لضمان أفضل موثوقية.",
                        fontSize = 11.sp, color = AppMuted, lineHeight = 16.sp)
                }
            }
        }
        item { Text("الصلوات", fontWeight = FontWeight.Bold, color = AppDark, fontSize = 17.sp) }
        item {
            Panel {
                Column {
                    PrayerKey.values().forEachIndexed { index, prayer ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text("${prayer.symbol}  ${prayer.title}", modifier = Modifier.weight(1f),
                                color = AppDark, fontWeight = FontWeight.Medium)
                            Switch(checked = settings.active(prayer),
                                onCheckedChange = { onSettings(settings.copy(enabled = settings.enabled + (prayer to it))) })
                        }
                        if (index != PrayerKey.values().lastIndex) HorizontalDivider(color = AppPale)
                    }
                }
            }
        }
        item { Text("التذكير المسبق", fontWeight = FontWeight.Bold, color = AppDark, fontSize = 17.sp) }
        item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("وقت التنبيه", color = AppDark, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(0, 5, 10, 15).forEach { minutes ->
                            FilterChip(selected = settings.advance == minutes,
                                onClick = { onSettings(settings.copy(advance = minutes)) },
                                label = { Text(if (minutes == 0) "عند الوقت" else "قبل $minutes د") })
                        }
                    }
                }
            }
        }
        item { Text("صوت التنبيه", fontWeight = FontWeight.Bold, color = AppDark, fontSize = 17.sp) }
        item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("default" to "الافتراضي", "soft" to "نغمة هادئة", "clear" to "نغمة واضحة").forEach { (id, title) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(title, modifier = Modifier.weight(1f), color = AppDark)
                            RadioButton(selected = settings.sound == id, onClick = { onSettings(settings.copy(sound = id)) })
                        }
                    }
                    Text("قنوات الصوت منفصلة في Android؛ إذا عدّلت صوت القناة من إعدادات النظام فسيُحترم اختيار النظام.",
                        color = AppMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun OptionsScreen(
    settings: PrayerSettings, onSettings: (PrayerSettings) -> Unit,
    cityName: String, locationMessage: String?, onLocation: () -> Unit
) {
    val cities = listOf("auto" to "موقعي تلقائيًا") + PrayerCities.all.map { it.id to it.name }
    val methods = listOf(
        "haqiba" to "مطابقة حقيبة المؤمن — APK",
        "jafari" to "جعفري تقريبي — فضيلة العصر",
        "tehran" to "طهران",
        "mwl" to "رابطة العالم الإسلامي",
        "umm" to "أم القرى",
        "karachi" to "كراتشي"
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("الإعدادات", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = AppDark) }
        item { Text("المدينة والموقع", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppDark) }
        item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    DropdownField("المدينة", settings.cityId, cities) { value -> onSettings(settings.copy(cityId = value)) }
                    Text("الموقع الحالي: $cityName", fontSize = 12.sp, color = AppMuted)
                    if (settings.cityId == "auto") {
                        OutlinedButton(onClick = onLocation, modifier = Modifier.fillMaxWidth()) { Text("تحديث موقعي GPS") }
                    }
                    if (locationMessage != null) Text(locationMessage, color = Color(0xFFB56A26), fontSize = 11.sp)
                    Text("عند رفض إذن الموقع يُستخدم آخر موقع محفوظ أو بغداد افتراضيًا.",
                        fontSize = 11.sp, color = AppMuted)
                }
            }
        }
        item { Text("طريقة حساب المواقيت", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppDark) }
        item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownField("طريقة الحساب", settings.method, methods) { value ->
                        onSettings(settings.copy(method = value).resetAngles().copy(asrModeOverride = null))
                    }
                    Text(if (settings.method == "haqiba")
                        "معاملات APK المستخرجة: الفجر 18°، المغرب 4°، العشاء 14°، العصر ظل المثل، ومنتصف الليل بين الغروب والفجر التالي."
                    else "طريقة حساب فلكية بديلة. تختلف النتائج باختلاف الزوايا وطريقة العصر.",
                        fontSize = 11.sp, color = AppMuted, lineHeight = 16.sp)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الزوايا الفلكية", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = AppDark, fontSize = 17.sp)
                TextButton(onClick = { onSettings(settings.resetAngles()) }) { Text("استعادة الافتراضي") }
            }
        }
        item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    AngleStepper("زاوية الفجر", settings.fajrAngle(), 12.0, 24.0) {
                        onSettings(settings.copy(customFajrAngle = it))
                    }
                    HorizontalDivider(color = AppPale)
                    AngleStepper("زاوية المغرب", settings.maghribAngle(), 0.0, 8.0) {
                        onSettings(settings.copy(customMaghribAngle = it))
                    }
                    HorizontalDivider(color = AppPale)
                    AngleStepper("زاوية العشاء", settings.ishaAngle(), 10.0, 24.0) {
                        onSettings(settings.copy(customIshaAngle = it))
                    }
                    Text("تغيير الزوايا يؤثر في الصلاة القادمة والتنبيهات ومنتصف الليل. الزوايا ليست اعتمادًا رسميًا من مكتب السيد السيستاني.",
                        color = AppMuted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
        item { Text("اختيار وقت العصر", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppDark) }
        item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("virtue" to "بداية فضيلة العصر — ظل ٢/٧", "shadow" to "حساب ظل المثل — ١").forEach { (id, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(label, modifier = Modifier.weight(1f), color = AppDark, fontSize = 13.sp)
                            RadioButton(settings.asrMode() == id, onClick = { onSettings(settings.copy(asrModeOverride = id)) })
                        }
                    }
                    Text("بداية الفضيلة ليست بداية جواز العصر؛ تُراعى أولوية صلاة الظهر.", color = AppMuted, fontSize = 11.sp)
                }
            }
        }
        item { Text("تصحيح الدقائق لكل صلاة", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppDark) }
        item {
            Panel {
                Column {
                    PrayerKey.values().forEachIndexed { index, prayer ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text(prayer.title, modifier = Modifier.weight(1f), color = AppDark)
                            TextButton(onClick = { onSettings(settings.copy(offsets = settings.offsets + (prayer to (settings.correction(prayer) - 1).coerceAtLeast(-30)))) }) { Text("−") }
                            Text("${settings.correction(prayer)} د", color = AppTeal, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { onSettings(settings.copy(offsets = settings.offsets + (prayer to (settings.correction(prayer) + 1).coerceAtMost(30)))) }) { Text("+") }
                        }
                        if (index != PrayerKey.values().lastIndex) HorizontalDivider(color = AppPale)
                    }
                }
            }
        }
        item {
            Text("الحساب محلي على الهاتف. نتائج المواقيت تقديرية ويجب مقارنتها بالتقويم الشرعي المعتمد في منطقتك.",
                fontSize = 11.sp, color = AppMuted, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun DropdownField(
    label: String, selected: String, options: List<Pair<String, String>>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: selected
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = AppMuted, fontSize = 11.sp)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current, modifier = Modifier.weight(1f), textAlign = TextAlign.Start, color = AppDark)
                Text("⌄", color = AppTeal)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = {
                        expanded = false
                        onSelected(id)
                    })
                }
            }
        }
    }
}

@Composable
private fun AngleStepper(title: String, value: Double, minimum: Double, maximum: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), color = AppDark)
        TextButton(onClick = { onChange((value - 0.5).coerceAtLeast(minimum)) }) { Text("−") }
        Text(String.format(Locale.US, "%.1f°", value), color = AppTeal, fontWeight = FontWeight.Bold)
        TextButton(onClick = { onChange((value + 0.5).coerceAtMost(maximum)) }) { Text("+") }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, AppPale)
    ) {
        Column(Modifier.padding(15.dp), content = content)
    }
}

private fun formatTime(value: ZonedDateTime): String =
    value.format(DateTimeFormatter.ofPattern("h:mm a", Locale("ar", "IQ")))

private fun formatDay(value: LocalDate): String =
    value.format(DateTimeFormatter.ofPattern("EEEE، d MMMM yyyy", Locale("ar", "IQ")))
