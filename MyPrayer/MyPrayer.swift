import SwiftUI
import CoreLocation
import UserNotifications
import Adhan

enum Prayer: String, CaseIterable, Identifiable {
    case fajr, dhuhr, asr, maghrib, isha
    var id: String { rawValue }
    var title: String {
        switch self {
        case .fajr: return "الفجر"
        case .dhuhr: return "الظهر"
        case .asr: return "العصر"
        case .maghrib: return "المغرب"
        case .isha: return "العشاء"
        }
    }
    var icon: String {
        switch self {
        case .fajr: return "sunrise.fill"
        case .dhuhr: return "sun.max.fill"
        case .asr: return "sun.haze.fill"
        case .maghrib: return "sunset.fill"
        case .isha: return "moon.stars.fill"
        }
    }
}
struct PrayerEntry: Identifiable {
    let prayer: Prayer
    let date: Date
    var id: String { prayer.rawValue + "-" + String(date.timeIntervalSince1970) }
}
struct City: Identifiable {
    let id: String
    let name: String
    let latitude: Double
    let longitude: Double
    static let all: [City] = [
        City(id:"baghdad",name:"بغداد",latitude:33.3152,longitude:44.3661),
        City(id:"hilla",name:"الحلة",latitude:32.4721,longitude:44.4217),
        City(id:"karbala",name:"كربلاء",latitude:32.6160,longitude:44.0249),
        City(id:"najaf",name:"النجف",latitude:31.9996,longitude:44.3267),
        City(id:"basra",name:"البصرة",latitude:30.5085,longitude:47.7804)
    ]
}
struct AppSettings: Codable {
    var city = "auto"
    var method = "jafari"
    var enabled: [String: Bool] = [:]
    var offsets: [String: Int] = [:]
    var advance = 0
    var sound = "default"

    // Optional keys migrate safely from 1.0 without deleting existing preferences.
    var customFajrAngle: Double?
    var customMaghribAngle: Double?
    var customIshaAngle: Double?

    func active(_ p: Prayer) -> Bool { enabled[p.rawValue] ?? true }
    func correction(_ p: Prayer) -> Int { offsets[p.rawValue] ?? 0 }
    func angle(_ prayer: Prayer) -> Double {
        let baseline = PrayerClock.baseParameters(for: method)
        switch prayer {
        case .fajr: return customFajrAngle ?? baseline.fajrAngle
        case .maghrib: return customMaghribAngle ?? baseline.maghribAngle ?? 0
        case .isha: return customIshaAngle ?? baseline.ishaAngle
        default: return 0
        }
    }
    mutating func setAngle(_ prayer: Prayer, value: Double) {
        switch prayer {
        case .fajr: customFajrAngle = value
        case .maghrib: customMaghribAngle = value
        case .isha: customIshaAngle = value
        default: break
        }
    }
    mutating func resetAngles() {
        customFajrAngle = nil
        customMaghribAngle = nil
        customIshaAngle = nil
    }
    static func restore() -> AppSettings {
        guard let d = UserDefaults.standard.data(forKey:"appSettings"),
              let value = try? JSONDecoder().decode(AppSettings.self,from:d) else { return AppSettings() }
        return value
    }
    func persist() {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data,forKey:"appSettings")
        }
    }
}
struct TimeRow: Identifiable {
    let id: String
    let title: String
    let icon: String
    let date: Date
    let detail: String
    let prayer: Prayer?
}
struct DailySchedule {
    let prayers: [PrayerEntry]
    let sunrise: Date
    let sunset: Date
    let midnight: Date

    var rows: [TimeRow] {
        var result = prayers.map { e in
            TimeRow(id:e.prayer.rawValue,title:e.prayer.title,icon:e.prayer.icon,
                    date:e.date,detail:"صلاة",prayer:e.prayer)
        }
        result.append(TimeRow(id:"sunrise",title:"شروق الشمس",icon:"sunrise",
                              date:sunrise,detail:"حدث فلكي — ليس صلاة",prayer:nil))
        result.append(TimeRow(id:"sunset",title:"غروب الشمس",icon:"sunset",
                              date:sunset,detail:"الغروب الفلكي — قد يسبق المغرب",prayer:nil))
        result.append(TimeRow(id:"midnight",title:"منتصف الليل الشرعي",icon:"moon.zzz.fill",
                              date:midnight,detail:"منتصف الغروب والفجر التالي",prayer:nil))
        return result.sorted { $0.date < $1.date }
    }
}
enum PrayerClock {
    static func timeZone(for settings: AppSettings) -> TimeZone {
        // Manual Iraqi locations must use Iraq's civil time, even if the phone is abroad.
        if settings.city != "auto" { return TimeZone(identifier:"Asia/Baghdad") ?? .current }
        return .current
    }
    static func calendar(for settings: AppSettings) -> Calendar {
        var cal = Calendar(identifier:.gregorian)
        cal.timeZone = timeZone(for:settings)
        return cal
    }
    static func baseParameters(for method: String) -> CalculationParameters {
        switch method {
        case "tehran": return CalculationMethod.tehran.params
        case "mwl": return CalculationMethod.muslimWorldLeague.params
        case "umm": return CalculationMethod.ummAlQura.params
        case "karachi": return CalculationMethod.karachi.params
        default:
            var p = CalculationMethod.tehran.params
            p.fajrAngle = 16
            p.maghribAngle = 4
            p.ishaAngle = 14
            return p
        }
    }
    static func parameters(for settings: AppSettings) -> CalculationParameters {
        var p = baseParameters(for: settings.method)
        p.madhab = .shafi  // shared shadow-length convention for the selected methods
        if let value = settings.customFajrAngle { p.fajrAngle = value }
        if let value = settings.customMaghribAngle { p.maghribAngle = value == 0 ? nil : value }
        if let value = settings.customIshaAngle {
            p.ishaAngle = value
            p.ishaInterval = 0 // override fixed 90-minute Isha methods, e.g. Umm Al-Qura
        }
        return p
    }
    static func schedule(for day: Date, latitude: Double,
                         longitude: Double, settings: AppSettings) -> DailySchedule? {
        let cal = calendar(for:settings)
        let today = cal.dateComponents([.year,.month,.day],from:day)
        guard let tomorrowDay = cal.date(byAdding:.day,value:1,to:day) else { return nil }
        let tomorrow = cal.dateComponents([.year,.month,.day],from:tomorrowDay)
        let coords = Coordinates(latitude:latitude,longitude:longitude)
        let parameters = parameters(for:settings)
        guard let current = PrayerTimes(coordinates:coords,date:today,calculationParameters:parameters),
              let following = PrayerTimes(coordinates:coords,date:tomorrow,calculationParameters:parameters)
        else { return nil }

        // The Adhan "maghrib" is affected by the Jafari twilight angle, so
        // compute actual astronomical sunset separately with no Maghrib angle.
        var solarParameters = CalculationMethod.other.params
        solarParameters.fajrAngle = 16
        solarParameters.ishaAngle = 14
        solarParameters.maghribAngle = nil
        solarParameters.madhab = .shafi
        guard let solar = PrayerTimes(coordinates:coords,date:today,
                                     calculationParameters:solarParameters) else { return nil }
        let sunset = solar.maghrib
        // Sistani's Islamic midnight: midpoint between solar sunset and next true dawn.
        // It does NOT depend on the civil midnight (00:00), or on Maghrib's twilight delay.
        let midpoint = sunset.addingTimeInterval(following.fajr.timeIntervalSince(sunset)/2)
        let roundedMidnight = Date(timeIntervalSince1970:(midpoint.timeIntervalSince1970/60).rounded()*60)
        let pairs: [(Prayer,Date)] = [
            (.fajr,current.fajr),(.dhuhr,current.dhuhr),(.asr,current.asr),
            (.maghrib,current.maghrib),(.isha,current.isha)
        ]
        let prayers = pairs.map { p,date in
            PrayerEntry(prayer:p,date:date.addingTimeInterval(Double(settings.correction(p)*60)))
        }
        return DailySchedule(prayers:prayers,sunrise:current.sunrise,
                             sunset:sunset,midnight:roundedMidnight)
    }
    static func entries(for day: Date,latitude:Double,longitude:Double,
                        settings:AppSettings) -> [PrayerEntry] {
        schedule(for:day,latitude:latitude,longitude:longitude,settings:settings)?.prayers ?? []
    }
    static func formattedTime(_ date: Date, settings: AppSettings) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier:"ar_IQ")
        f.timeZone = timeZone(for:settings)
        f.dateFormat = "h:mm a"
        return f.string(from:date)
    }
    static func formattedDay(_ date: Date, settings: AppSettings, short: Bool = false) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier:"ar_IQ")
        f.timeZone = timeZone(for:settings)
        f.dateFormat = short ? "E d/MM" : "EEEE، d MMMM yyyy"
        return f.string(from:date)
    }
}
@MainActor
final class PrayerStore: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var settings = AppSettings.restore()
    @Published var latitude = 33.3152
    @Published var longitude = 44.3661
    @Published var locationLabel = "بغداد (افتراضي)"
    @Published var locationMessage: String?
    @Published var notificationsAllowed = false

    private let locator = CLLocationManager()
    private var dayCache: [String: DailySchedule] = [:]
    private func resetDayCache() { dayCache.removeAll(keepingCapacity:true) }
    private var scheduledTask: Task<Void,Never>?
    private var launched = false
    override init() {
        super.init()
        if UserDefaults.standard.object(forKey:"lastLat") != nil {
            latitude = UserDefaults.standard.double(forKey:"lastLat")
            longitude = UserDefaults.standard.double(forKey:"lastLon")
            locationLabel = "الموقع المحفوظ"
        }
        locator.delegate = self
        locator.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }
    var coordinates: (Double,Double) {
        if let city=City.all.first(where:{$0.id==settings.city}) {
            return (city.latitude,city.longitude)
        }
        return (latitude,longitude)
    }
    var cityName: String {
        City.all.first(where:{$0.id==settings.city})?.name ?? locationLabel
    }
    var timeZone: TimeZone { PrayerClock.timeZone(for:settings) }
    func timeText(_ date: Date) -> String { PrayerClock.formattedTime(date,settings:settings) }
    func dayText(_ date: Date, short: Bool = false) -> String {
        PrayerClock.formattedDay(date,settings:settings,short:short)
    }
    func date(offset days: Int) -> Date? {
        PrayerClock.calendar(for:settings).date(byAdding:.day,value:days,to:.now)
    }
    func daily(on date:Date = .now) -> DailySchedule? {
        let c=PrayerClock.calendar(for:settings).dateComponents([.year,.month,.day],from:date)
        let key="\(c.year ?? 0)-\(c.month ?? 0)-\(c.day ?? 0)"
        if let saved=dayCache[key] { return saved }
        let (lat,lon)=coordinates
        guard let schedule=PrayerClock.schedule(for:date,latitude:lat,longitude:lon,settings:settings) else {
            return nil
        }
        dayCache[key]=schedule
        return schedule
    }
    func entries(on date:Date = .now) -> [PrayerEntry] { daily(on:date)?.prayers ?? [] }
    func rows(on date:Date = .now) -> [TimeRow] { daily(on:date)?.rows ?? [] }
    func next(after date:Date) -> PrayerEntry? {
        let tomorrow=Calendar.current.date(byAdding:.day,value:1,to:date) ?? date.addingTimeInterval(86400)
        return (entries(on:date)+entries(on:tomorrow))
            .filter{$0.date>date}.sorted{$0.date<$1.date}.first
    }
    func edit(_ change:(inout AppSettings)->Void) {
        change(&settings)
        resetDayCache()
        settings.persist()
        if settings.city=="auto" { locate() }
        reschedule()
    }
    func launch() {
        guard !launched else { return }
        launched=true
        refresh()
    }
    func refresh() {
        resetDayCache()
        Task {
            let status=await UNUserNotificationCenter.current().notificationSettings()
            notificationsAllowed = status.authorizationStatus == .authorized || status.authorizationStatus == .provisional
        }
        if settings.city=="auto" { locate() }
        reschedule()
    }
    func locate() {
        guard settings.city=="auto" else { return }
        switch locator.authorizationStatus {
        case .notDetermined: locator.requestWhenInUseAuthorization()
        case .authorizedAlways,.authorizedWhenInUse: locator.requestLocation()
        case .denied,.restricted:
            locationMessage="تم رفض إذن الموقع. اختر مدينتك يدوياً من الإعدادات."
        @unknown default: break
        }
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if settings.city == "auto" &&
            (manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways) {
            manager.requestLocation()
        }
    }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations:[CLLocation]) {
        guard let fix=locations.last,fix.horizontalAccuracy>=0 else { return }
        latitude=fix.coordinate.latitude
        longitude=fix.coordinate.longitude
        resetDayCache()
        locationLabel="موقعي الحالي"
        locationMessage=nil
        UserDefaults.standard.set(latitude,forKey:"lastLat")
        UserDefaults.standard.set(longitude,forKey:"lastLon")
        reschedule()
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error:Error) {
        locationMessage="تعذر تحديث الموقع: \(error.localizedDescription)"
    }
    func enableNotifications() async {
        do {
            notificationsAllowed = try await UNUserNotificationCenter.current()
                .requestAuthorization(options:[.alert,.sound,.badge])
            if notificationsAllowed { reschedule() }
        } catch { locationMessage="تعذر تفعيل التنبيهات: \(error.localizedDescription)" }
    }
    func testNotification() async {
        if !notificationsAllowed { await enableNotifications() }
        guard notificationsAllowed else { return }
        let content=UNMutableNotificationContent()
        content.title="تجربة تنبيه الصلاة"
        content.body="هذا تنبيه اختباري من تطبيق صلاتي"
        content.sound=AlertScheduler.sound(settings.sound)
        let request=UNNotificationRequest(identifier:"salati-test-" + UUID().uuidString,
                  content:content,trigger:UNTimeIntervalNotificationTrigger(timeInterval:8,repeats:false))
        do { try await UNUserNotificationCenter.current().add(request) }
        catch { locationMessage="فشل الاختبار: \(error.localizedDescription)" }
    }
    func reschedule() {
        scheduledTask?.cancel()
        let (lat,lon)=coordinates
        let snapshot=settings
        scheduledTask=Task {
            try? await Task.sleep(nanoseconds:300_000_000)
            guard !Task.isCancelled else { return }
            await AlertScheduler.schedule(latitude:lat,longitude:lon,settings:snapshot)
        }
    }
}
enum AlertScheduler {
    static func sound(_ name:String) -> UNNotificationSound {
        switch name {
        case "soft": return UNNotificationSound(named:UNNotificationSoundName(rawValue:"soft.wav"))
        case "clear": return UNNotificationSound(named:UNNotificationSoundName(rawValue:"clear.wav"))
        default: return .default
        }
    }
    static func schedule(latitude:Double,longitude:Double,settings:AppSettings) async {
        let center=UNUserNotificationCenter.current()
        let now=Date()
        let cal=PrayerClock.calendar(for:settings)
        var rows:[(String,Date,String,String)]=[]
        for i in 0..<16 {
            guard let day=cal.date(byAdding:.day,value:i,to:now) else { continue }
            for entry in PrayerClock.entries(for:day,latitude:latitude,longitude:longitude,settings:settings) {
                guard settings.active(entry.prayer),entry.date>now.addingTimeInterval(2) else { continue }
                let base="salati-"+entry.prayer.rawValue+"-"+String(Int(entry.date.timeIntervalSince1970))
                rows.append((base,entry.date,"حان وقت صلاة \(entry.prayer.title)","حي على الصلاة"))
                if settings.advance>0 {
                    let earlier=entry.date.addingTimeInterval(Double(-settings.advance*60))
                    if earlier>now.addingTimeInterval(2) {
                        rows.append((base+"-early",earlier,"اقترب وقت صلاة \(entry.prayer.title)",
                                     "بقي \(settings.advance) دقيقة على الصلاة"))
                    }
                }
            }
        }
        // iOS generally retains at most 64 pending local notifications. Leave room for testing.
        let upcoming=Array(rows.sorted{$0.1<$1.1}.prefix(60))
        let existing=await center.pendingNotificationRequests()
        center.removePendingNotificationRequests(withIdentifiers:
            existing.filter{$0.identifier.hasPrefix("salati-") && !$0.identifier.hasPrefix("salati-test-")}
                    .map(\.identifier))
        for (id,date,title,body) in upcoming {
            if Task.isCancelled { return }
            let content=UNMutableNotificationContent()
            content.title=title
            content.body=body
            content.sound=sound(settings.sound)
            let interval=date.timeIntervalSinceNow
            guard interval>1 else { continue }
            let trigger=UNTimeIntervalNotificationTrigger(timeInterval:interval,repeats:false)
            try? await center.add(UNNotificationRequest(identifier:id,content:content,trigger:trigger))
        }
    }
}
enum Palette {
    static let background=Color(red:0.975,green:0.971,blue:0.947)
    static let dark=Color(red:0.10,green:0.19,blue:0.20)
    static let teal=Color(red:0.08,green:0.37,blue:0.37)
    static let aqua=Color(red:0.18,green:0.51,blue:0.49)
    static let gold=Color(red:0.83,green:0.65,blue:0.36)
    static let pale=Color(red:0.91,green:0.94,blue:0.92)
}
struct Panel<Content:View>:View {
    @ViewBuilder let content:Content
    var body:some View {
        content.padding(18)
            .background(.white,in:RoundedRectangle(cornerRadius:21))
            .overlay(RoundedRectangle(cornerRadius:21).stroke(Palette.pale,lineWidth:1))
    }
}
@main
struct MyPrayerApp:App {
    @StateObject private var store=PrayerStore()
    @Environment(\.scenePhase) private var scenePhase
    init() { UNUserNotificationCenter.current().delegate=PrayerNotificationDelegate.shared }
    var body:some Scene {
        WindowGroup {
            PrayerTabs()
                .environmentObject(store)
                .environment(\.layoutDirection,.rightToLeft)
                .preferredColorScheme(.light)
                .task { store.launch() }
                .onChange(of:scenePhase) { state in
                    if state == .active { store.refresh() }
                }
        }
    }
}
final class PrayerNotificationDelegate:NSObject,UNUserNotificationCenterDelegate {
    static let shared=PrayerNotificationDelegate()
    func userNotificationCenter(_ center:UNUserNotificationCenter,
        willPresent notification:UNNotification,
        withCompletionHandler completionHandler:@escaping (UNNotificationPresentationOptions)->Void) {
        completionHandler([.banner,.list,.sound])
    }
}
struct PrayerTabs:View {
    var body:some View {
        TabView {
            NavigationStack { TodayScreen() }
                .tabItem { Label("الصلاة",systemImage:"moon.stars.fill") }
            NavigationStack { AlertsScreen() }
                .tabItem { Label("التنبيهات",systemImage:"bell.badge.fill") }
            NavigationStack { OptionsScreen() }
                .tabItem { Label("الإعدادات",systemImage:"gearshape.fill") }
        }.tint(Palette.teal)
    }
}
struct TodayScreen:View {
    @EnvironmentObject private var store:PrayerStore
    @State private var monthly=false
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:20) {
                HStack {
                    VStack(alignment:.leading,spacing:5) {
                        Text("صلاتي").font(.system(size:34,weight:.heavy,design:.rounded))
                            .foregroundStyle(Palette.dark)
                        Text(store.dayText(.now))
                            .font(.system(size:13)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    NavigationLink(destination:OptionsScreen()) {
                        Label(store.cityName,systemImage:"location.fill")
                            .lineLimit(1).font(.system(size:12,weight:.medium))
                            .padding(10).background(Palette.pale,in:Capsule())
                    }
                }
                NextPrayerCard()
                HStack {
                    Text("مواقيت الصلاة والشمس").font(.system(size:19,weight:.bold))
                    Spacer()
                    Button("جدول 30 يوماً") { monthly=true }
                        .font(.system(size:12,weight:.semibold))
                }.foregroundStyle(Palette.teal)
                Panel {
                    VStack(spacing:0) {
                        let events=store.rows()
                        ForEach(events) { event in
                            HStack(spacing:13) {
                                Image(systemName:event.icon)
                                    .frame(width:44,height:44)
                                    .foregroundStyle(Palette.teal)
                                    .background(Palette.pale,in:RoundedRectangle(cornerRadius:12))
                                VStack(alignment:.leading,spacing:4) {
                                    Text(event.title).font(.system(size:17,weight:.bold))
                                    Text(event.prayer.map { store.settings.active($0) ? "التذكير مفعّل" : "التذكير متوقف" } ?? event.detail)
                                        .font(.system(size:11)).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(store.timeText(event.date))
                                    .font(.system(size:15,weight:.medium))
                            }.foregroundStyle(Palette.dark)
                            if event.id != events.last?.id { Divider().padding(.vertical,8) }
                        }
                        if events.isEmpty { Text("لم يمكن حساب المواقيت لهذا الموقع.") }
                    }
                }
                Label("قد تختلف المواقيت عن تقويم المسجد المحلي؛ تحقق منها واضبط الدقائق عند الحاجة.",
                      systemImage:"info.circle")
                    .font(.system(size:12)).foregroundStyle(.secondary)
                if let message=store.locationMessage {
                    Text(message).font(.system(size:12)).foregroundStyle(.orange)
                }
            }.padding(20)
        }
        .background(Palette.background.ignoresSafeArea())
        .toolbar(.hidden,for:.navigationBar)
        .sheet(isPresented:$monthly) { MonthScreen() }
    }
}
struct NextPrayerCard:View {
    @EnvironmentObject private var store:PrayerStore
    var body:some View {
        TimelineView(.periodic(from:.now,by:1)) { context in
            let event=store.next(after:context.date)
            VStack(alignment:.leading,spacing:16) {
                HStack {
                    VStack(alignment:.leading,spacing:7) {
                        Label("الصلاة القادمة",systemImage:"sparkles")
                            .font(.system(size:13)).foregroundStyle(.white.opacity(0.84))
                        Text(event?.prayer.title ?? "—")
                            .font(.system(size:40,weight:.heavy)).foregroundStyle(.white)
                        Text(event.map{store.timeText($0.date)} ?? "—")
                            .font(.system(size:17)).foregroundStyle(.white.opacity(0.88))
                    }
                    Spacer()
                    Image(systemName:"moon.stars.fill")
                        .font(.system(size:49,weight:.ultraLight))
                        .foregroundStyle(Palette.gold)
                }
                Rectangle().fill(.white.opacity(0.2)).frame(height:1)
                HStack {
                    Text("الوقت المتبقي").font(.system(size:13))
                    Spacer()
                    Text(event.map{countdown($0.date.timeIntervalSince(context.date))} ?? "—")
                        .font(.system(size:23,weight:.bold,design:.monospaced))
                        .environment(\.layoutDirection,.leftToRight)
                }.foregroundStyle(.white)
            }
            .padding(24)
            .background(LinearGradient(colors:[Palette.teal,Palette.aqua],
                 startPoint:.topLeading,endPoint:.bottomTrailing),
                 in:RoundedRectangle(cornerRadius:26))
        }
    }
    func countdown(_ seconds:TimeInterval)->String {
        let n=max(0,Int(seconds))
        return String(format:"%02d:%02d:%02d",n/3600,(n%3600)/60,n%60)
    }
}
struct MonthScreen:View {
    @EnvironmentObject private var store:PrayerStore
    @Environment(\.dismiss) private var dismiss
    var body:some View {
        NavigationStack {
            ScrollView(.horizontal) {
                VStack(spacing:0) {
                    HStack {
                        Text("اليوم").frame(width:125)
                        ForEach(["الفجر","الشروق","الظهر","العصر","الغروب","المغرب","العشاء","منتصف الليل"],id:\.self) {
                            Text($0).frame(width:92)
                        }
                    }.font(.system(size:12,weight:.bold))
                        .foregroundStyle(Palette.teal).padding(.vertical,15)
                    ForEach(0..<30,id:\.self) { offset in
                        if let d=store.date(offset:offset) {
                            HStack {
                                Text(store.dayText(d,short:true)).frame(width:125)
                                ForEach(store.rows(on:d)) { row in
                                    Text(store.timeText(row.date)).frame(width:92)
                                }
                            }
                            .font(.system(size:11)).padding(.vertical,12)
                            .background(offset.isMultiple(of:2) ? Palette.pale : .white)
                        }
                    }
                }.padding(.horizontal,14)
            }
            .navigationTitle("مواقيت 30 يوماً")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement:.navigationBarTrailing) {
                Button("إغلاق") { dismiss() }
            }}
        }
    }
}
struct AlertsScreen:View {
    @EnvironmentObject private var store:PrayerStore
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:17) {
                Text("تنبيهات الصلاة").font(.system(size:30,weight:.bold))
                    .foregroundStyle(Palette.dark)
                Text("اختر الصلوات التي تريد تذكيراً عند دخول وقتها.")
                    .font(.system(size:13)).foregroundStyle(.secondary)
                Panel {
                    VStack(alignment:.leading,spacing:12) {
                        Label(store.notificationsAllowed ? "الإشعارات مفعلة" : "الإشعارات غير مفعلة",
                              systemImage:store.notificationsAllowed ? "checkmark.seal.fill":"bell.slash.fill")
                            .font(.system(size:16,weight:.semibold))
                        if !store.notificationsAllowed {
                            Button("السماح بالإشعارات") {
                                Task { await store.enableNotifications() }
                            }.buttonStyle(.borderedProminent).tint(Palette.teal)
                        }
                        Button("اختبار الإشعار بعد 8 ثوانٍ") {
                            Task { await store.testNotification() }
                        }.font(.system(size:13))
                        Text("يتم جدولة حتى 60 إشعاراً قادماً وتجديدها عند فتح التطبيق. يلزم فتح التطبيق دورياً لاستمرار التنبيهات.")
                            .font(.system(size:12)).foregroundStyle(.secondary)
                    }.foregroundStyle(Palette.teal)
                }
                Text("الصلوات").font(.headline).foregroundStyle(Palette.dark)
                Panel {
                    VStack(spacing:15) {
                        ForEach(Prayer.allCases) { prayer in
                            Toggle(isOn:Binding(
                                get:{store.settings.active(prayer)},
                                set:{v in store.edit{$0.enabled[prayer.rawValue]=v}}
                            )) {
                                Label(prayer.title,systemImage:prayer.icon).foregroundStyle(Palette.dark)
                            }.tint(Palette.teal)
                            if prayer != .isha { Divider() }
                        }
                    }
                }
                Text("التذكير المسبق").font(.headline).foregroundStyle(Palette.dark)
                Panel {
                    Picker("وقت التذكير",selection:Binding(
                        get:{store.settings.advance},
                        set:{v in store.edit{$0.advance=v}}
                    )) {
                        Text("عند الصلاة فقط").tag(0)
                        Text("قبل 5 دقائق").tag(5)
                        Text("قبل 10 دقائق").tag(10)
                        Text("قبل 15 دقيقة").tag(15)
                    }.tint(Palette.teal)
                }
                Text("الصوت").font(.headline).foregroundStyle(Palette.dark)
                Panel {
                    Picker("صوت التنبيه",selection:Binding(
                        get:{store.settings.sound},
                        set:{v in store.edit{$0.sound=v}}
                    )) {
                        Text("الصوت الافتراضي").tag("default")
                        Text("نغمة هادئة").tag("soft")
                        Text("نغمة واضحة").tag("clear")
                    }.tint(Palette.teal)
                    Text("أصوات إشعارات قصيرة؛ تشغيل الأذان الكامل في الخلفية غير مضمون على iOS.")
                        .font(.system(size:12)).foregroundStyle(.secondary)
                }
            }.padding(20)
        }.background(Palette.background.ignoresSafeArea())
    }
}
struct OptionsScreen:View {
    @EnvironmentObject private var store:PrayerStore
    private let methods:[(String,String)]=[
        ("jafari","الجعفري — قم"),("tehran","طهران"),
        ("mwl","رابطة العالم الإسلامي"),("umm","أم القرى"),("karachi","كراتشي")
    ]
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:17) {
                Text("الإعدادات").font(.system(size:30,weight:.bold)).foregroundStyle(Palette.dark)
                Text("المدينة والموقع").font(.headline).foregroundStyle(Palette.dark)
                Panel {
                    VStack(alignment:.leading,spacing:11) {
                        Picker("المدينة",selection:Binding(
                            get:{store.settings.city},
                            set:{v in store.edit{$0.city=v}}
                        )) {
                            Text("موقعي تلقائياً").tag("auto")
                            ForEach(City.all) { c in Text(c.name).tag(c.id) }
                        }.tint(Palette.teal)
                        if store.settings.city == "auto" {
                            Button("تحديث الموقع") { store.locate() }.foregroundStyle(Palette.teal)
                        }
                        Text("عند عدم منح إذن الموقع، يتم استخدام بغداد أو آخر موقع محفوظ.")
                            .font(.system(size:12)).foregroundStyle(.secondary)
                    }
                }
                Text("طريقة حساب المواقيت").font(.headline).foregroundStyle(Palette.dark)
                Panel {
                    Picker("طريقة الحساب",selection:Binding(
                        get:{store.settings.method},
                        set:{v in store.edit{$0.method=v; $0.resetAngles()}}
                    )) {
                        ForEach(0..<methods.count,id:\.self) { i in Text(methods[i].1).tag(methods[i].0) }
                    }.tint(Palette.teal)
                    Text("اختيار طريقة الحساب يحدد الزوايا الابتدائية. يمكنك تعديلها أدناه؛ ليست هذه قيماً معتمدة من حقيبة المؤمن.")
                        .font(.system(size:12)).foregroundStyle(.secondary)
                }
                HStack {
                    Text("الزوايا الفلكية (بالدرجات)").font(.headline)
                    Spacer()
                    Button("استعادة الافتراضي") { store.edit{$0.resetAngles()} }
                        .font(.system(size:12,weight:.semibold))
                }.foregroundStyle(Palette.dark)
                Panel {
                    VStack(alignment:.leading,spacing:14) {
                        angleControl(.fajr,title:"زاوية الفجر",range:12...24)
                        Divider()
                        angleControl(.maghrib,title:"زاوية المغرب",range:0...8)
                        Divider()
                        angleControl(.isha,title:"زاوية العشاء",range:10...24)
                        Text("زاوية 0° للمغرب تعني بدء المغرب عند غروب الشمس. عند تخصيص زاوية العشاء لطريقة أم القرى، تلغي الزاوية فترة 90 دقيقة الثابتة.")
                            .font(.system(size:12)).foregroundStyle(.secondary)
                        Text("تغيير الزوايا يؤثر على أوقات الصلاة والعد التنازلي والتنبيهات ومنتصف الليل الشرعي.")
                            .font(.system(size:12)).foregroundStyle(.secondary)
                    }
                }
                Text("تصحيح الدقائق لكل صلاة").font(.headline).foregroundStyle(Palette.dark)
                Panel {
                    VStack(spacing:13) {
                        ForEach(Prayer.allCases) { p in
                            Stepper(value:Binding(
                                get:{store.settings.correction(p)},
                                set:{v in store.edit{$0.offsets[p.rawValue]=v}}
                            ),in:-30...30) {
                                HStack {
                                    Text(p.title)
                                    Spacer()
                                    Text("\(store.settings.correction(p)) دقيقة")
                                        .font(.system(size:13)).foregroundStyle(Palette.teal)
                                }
                            }
                            if p != .isha { Divider() }
                        }
                    }
                }
                Text("الموقع يحسب على الهاتف دون رفع الإحداثيات إلى خادم. هذا التطبيق للتذكير بالصلاة فقط.")
                    .font(.system(size:12)).foregroundStyle(.secondary)
            }.padding(20)
        }.background(Palette.background.ignoresSafeArea())
    }
    @ViewBuilder
    private func angleControl(_ prayer: Prayer, title: String,
                              range: ClosedRange<Double>) -> some View {
        Stepper(value:Binding(
            get:{store.settings.angle(prayer)},
            set:{value in store.edit{$0.setAngle(prayer,value:value)}}
        ),in:range,step:0.5) {
            HStack {
                Text(title)
                Spacer()
                Text(String(format:"%.1f°",store.settings.angle(prayer)))
                    .font(.system(size:14,weight:.semibold))
                    .foregroundStyle(Palette.teal)
            }
        }
    }
}
