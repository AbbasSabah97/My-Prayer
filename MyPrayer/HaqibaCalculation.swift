import Foundation

/// Independent Swift reconstruction of the PrayTime/PrayTimes v2 mathematics
/// identified in the user-supplied Haqibat Elmomen APK. This is an optional
/// compatibility mode, not an official religious timetable or a copied asset.
/// No network access; date/coordinates/angles are explicit inputs.
struct HaqibaPrayerDay {
    let fajr: Date
    let sunrise: Date
    let dhuhr: Date
    let asr: Date
    let sunset: Date
    let maghrib: Date
    let isha: Date
}

enum HaqibaCalculation {
    private static func radians(_ degrees: Double) -> Double { degrees * .pi / 180 }
    private static func degrees(_ radians: Double) -> Double { radians * 180 / .pi }
    private static func sinD(_ degrees: Double) -> Double { sin(radians(degrees)) }
    private static func cosD(_ degrees: Double) -> Double { cos(radians(degrees)) }
    private static func tanD(_ degrees: Double) -> Double { tan(radians(degrees)) }
    private static func mod(_ value: Double, _ divisor: Double) -> Double {
        let result = value.truncatingRemainder(dividingBy:divisor)
        return result < 0 ? result + divisor : result
    }
    private static func julianDate(year: Int, month: Int, day: Int) -> Double {
        var y=year, m=month
        if m <= 2 { y-=1; m+=12 }
        let a=floor(Double(y)/100)
        let b=2-a+floor(a/4)
        return floor(365.25*Double(y+4716))
            + floor(30.6001*Double(m+1))
            + Double(day)+b-1524.5
    }
    private static func sunPosition(_ jd: Double) -> (declination: Double,equation:Double) {
        let d=jd-2451545
        let g=mod(357.529+0.98560028*d,360)
        let q=mod(280.459+0.98564736*d,360)
        let longitude=mod(q+1.915*sinD(g)+0.020*sinD(2*g),360)
        let obliquity=23.439-0.00000036*d
        let declination=degrees(asin(sinD(obliquity)*sinD(longitude)))
        let ra=mod(degrees(atan2(cosD(obliquity)*sinD(longitude),cosD(longitude)))/15,24)
        return (declination,q/15-ra)
    }
    /// Local day computation follows PrayTime v2: initial estimates, one
    /// solar-position refinement, timezone and longitude correction.
    static func calculate(day: Date, latitude: Double, longitude: Double,
                          timeZone: TimeZone, fajrAngle: Double=18,
                          maghribAngle: Double=4, ishaAngle: Double=14,
                          asrFactor: Double=1) -> HaqibaPrayerDay? {
        guard (-90...90).contains(latitude), (-180...180).contains(longitude),
              (0...30).contains(fajrAngle), (0...30).contains(maghribAngle),
              (0...30).contains(ishaAngle), (0.1...3).contains(asrFactor) else { return nil }
        var calendar=Calendar(identifier:.gregorian)
        calendar.timeZone=timeZone
        let c=calendar.dateComponents([.year,.month,.day],from:day)
        guard let y=c.year,let m=c.month,let d=c.day,
              let midnight=calendar.date(from:DateComponents(year:y,month:m,day:d)),
              let noonLocal=calendar.date(byAdding:.hour,value:12,to:midnight) else { return nil }
        let zone=Double(timeZone.secondsFromGMT(for:noonLocal))/3600
        let jd=julianDate(year:y,month:m,day:d)-longitude/360
        func noon(_ t:Double) -> Double {
            mod(12-sunPosition(jd+t).equation,24)
        }
        func time(_ angle:Double,_ t:Double) -> Double {
            let delta=sunPosition(jd+t).declination
            let denominator=cosD(delta)*cosD(latitude)
            guard abs(denominator)>1e-12 else { return .nan }
            let cosine=(-sinD(angle)-sinD(delta)*sinD(latitude))/denominator
            guard cosine.isFinite,(-1...1).contains(cosine) else { return .nan }
            let h=degrees(acos(cosine))/15
            return noon(t)+(angle>90 ? -h : h)
        }
        func asr(_ t:Double) -> Double {
            let delta=sunPosition(jd+t).declination
            let altAngle = -degrees(atan2(1,asrFactor+tanD(abs(latitude-delta))))
            return time(altAngle,t)
        }

        // PrayTimes v2 uses guesses [5,6,12,13,18,18,18] and one refinement.
        let guesses:[Double]=[5,6,12,13,18,18,18].map{$0/24}
        var times:[Double]=[
            time(180-fajrAngle,guesses[0]),
            time(180-0.833,guesses[1]),
            noon(guesses[2]),
            asr(guesses[3]),
            time(0.833,guesses[4]),
            time(maghribAngle,guesses[5]),
            time(ishaAngle,guesses[6])
        ]
        let change=zone-longitude/15
        for index in times.indices { if times[index].isFinite { times[index] += change } }
        // Reproduce APK's AngleBased high-latitude fallback when required.
        if times[1].isFinite && times[4].isFinite {
            let night=mod(times[1]-times[4],24)
            let fajrMax=fajrAngle/60*night
            if !times[0].isFinite || mod(times[1]-times[0],24)>fajrMax {
                times[0]=times[1]-fajrMax
            }
            let ishaMax=ishaAngle/60*night
            if !times[6].isFinite || mod(times[6]-times[4],24)>ishaMax {
                times[6]=times[4]+ishaMax
            }
            let maghribMax=maghribAngle/60*night
            if !times[5].isFinite || mod(times[5]-times[4],24)>maghribMax {
                times[5]=times[4]+maghribMax
            }
        }
        guard times.allSatisfy({$0.isFinite}) else { return nil }
        func date(_ index:Int)->Date {
            midnight.addingTimeInterval(mod(times[index],24)*3600)
        }
        return HaqibaPrayerDay(fajr:date(0),sunrise:date(1),
                               dhuhr:date(2),asr:date(3),sunset:date(4),
                               maghrib:date(5),isha:date(6))
    }
}
