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
    func active(_ p: Prayer) -> Bool { enabled[p.rawValue] ?? true }
    func correction(_ p: Prayer) -> Int { offsets[p.rawValue] ?? 0 }
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
enum PrayerClock {
    static func entries(for day: Date, latitude: Double, longitude: Double, settings: AppSettings) -> [PrayerEntry] {
        let c = Calendar(identifier:.gregorian).dateComponents([.year,.month,.day],from:day)
        var parameters: CalculationParameters
        switch settings.method {
        case "tehran": parameters = CalculationMethod.tehran.params
        case "mwl": parameters = CalculationMethod.muslimWorldLeague.params
        case "umm": parameters = CalculationMethod.ummAlQura.params
        case "karachi": parameters = CalculationMethod.karachi.params
        default:
            parameters = CalculationMethod.tehran.params
            parameters.fajrAngle = 16
            parameters.maghribAngle = 4
            parameters.ishaAngle = 14
        }
        parameters.madhab = .shafi
        guard let t = PrayerTimes(coordinates: Coordinates(latitude:latitude,longitude:longitude),
                                  date:c,calculationParameters:parameters) else { return [] }
        let pairs: [(Prayer,Date)] = [
            (.fajr,t.fajr),(.dhuhr,t.dhuhr),(.asr,t.asr),(.maghrib,t.maghrib),(.isha,t.isha)
        ]
        return pairs.map { p,date in
            PrayerEntry(prayer:p,date:date.addingTimeInterval(Double(settings.correction(p)*60)))
        }
    }
    static let time: DateFormatter = {
        let f=DateFormatter(); f.locale=Locale(identifier:"ar_IQ"); f.dateFormat="h:mm a"; return f
    }()
    static let day: DateFormatter = {
        let f=DateFormatter(); f.locale=Locale(identifier:"ar_IQ"); f.dateFormat="EEEE، d MMMM yyyy"; return f
    }()
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
        locator.desiredAccuracy = kCLLocationAccuracyKilometer
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
    func entries(on date:Date = .now) -> [PrayerEntry] {
        let (lat,lon)=coordinates
        return PrayerClock.entries(for:date,latitude:lat,longitude:lon,settings:settings)
    }
    func next(after date:Date) -> PrayerEntry? {
        let tomorrow=Calendar.current.date(byAdding:.day,value:1,to:date) ?? date.addingTimeInterval(86400)
        return (entries(on:date)+entries(on:tomorrow))
            .filter{$0.date>date}.sorted{$0.date<$1.date}.first
    }
    func edit(_ change:(inout AppSettings)->Void) {
        change(&settings)
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
        var rows:[(String,Date,String,String)]=[]
        for i in 0..<16 {
            guard let day=Calendar.current.date(byAdding:.day,value:i,to:now) else { continue }
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
                        Text(PrayerClock.day.string(from:.now))
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
                    Text("مواقيت الصلاة اليوم").font(.system(size:19,weight:.bold))
                    Spacer()
                    Button("جدول 30 يوماً") { monthly=true }
                        .font(.system(size:12,weight:.semibold))
                }.foregroundStyle(Palette.teal)
                Panel {
                    VStack(spacing:0) {
                        let events=store.entries()
                        ForEach(events) { event in
                            HStack(spacing:13) {
                                Image(systemName:event.prayer.icon)
                                    .frame(width:44,height:44)
                                    .foregroundStyle(Palette.teal)
                                    .background(Palette.pale,in:RoundedRectangle(cornerRadius:12))
                                VStack(alignment:.leading,spacing:4) {
                                    Text(event.prayer.title).font(.system(size:17,weight:.bold))
                                    Text(store.settings.active(event.prayer) ? "التذكير مفعّل" : "التذكير متوقف")
                                        .font(.system(size:11)).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(PrayerClock.time.string(from:event.date))
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
                        Text(event.map{PrayerClock.time.string(from:$0.date)} ?? "—")
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
                        Text("اليوم").frame(width:120)
                        ForEach(Prayer.allCases) { Text($0.title).frame(width:85) }
                    }.font(.system(size:13,weight:.bold))
                        .foregroundStyle(Palette.teal).padding(.vertical,15)
                    ForEach(0..<30,id:\.self) { offset in
                        if let d=Calendar.current.date(byAdding:.day,value:offset,to:.now) {
                            HStack {
                                Text(Self.short.string(from:d)).frame(width:120)
                                ForEach(store.entries(on:d)) { p in
                                    Text(PrayerClock.time.string(from:p.date)).frame(width:85)
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
    static let short:DateFormatter = {
        let f=DateFormatter(); f.locale=Locale(identifier:"ar_IQ"); f.dateFormat="E d/MM"; return f
    }()
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
                        set:{v in store.edit{$0.method=v}}
                    )) {
                        ForEach(0..<methods.count,id:\.self) { i in Text(methods[i].1).tag(methods[i].0) }
                    }.tint(Palette.teal)
                    Text("الطريقة الجعفرية الافتراضية: الفجر 16°، المغرب 4°، العشاء 14°. راجع توقيت المرجعية أو المسجد.")
                        .font(.system(size:12)).foregroundStyle(.secondary)
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
}
