import Foundation

let city=TimeZone(identifier:"Asia/Baghdad")!
var cal=Calendar(identifier:.gregorian);cal.timeZone=city
func date(_ y:Int,_ m:Int,_ d:Int)->Date {
    cal.date(from:DateComponents(year:y,month:m,day:d,hour:12))!
}
func minutes(_ day:Date,_ event:Date)->Double {
    event.timeIntervalSince(cal.startOfDay(for:day))/60
}
let sample=date(2026,11,3)
let p=HaqibaCalculation.calculate(day:sample,latitude:32.4721,
        longitude:44.4217,timeZone:city)!
let times=[p.fajr,p.sunrise,p.dhuhr,p.asr,p.sunset,p.maghrib,p.isha]
precondition(zip(times,times.dropFirst()).allSatisfy{$0<$1})
let estimates=[298,381,706,888,1030,1046,1094] // APK reconstruction minutes
for (actual,expected) in zip(times,estimates) {
    precondition(abs(minutes(sample,actual)-Double(expected))<=3,
       "Haqiba reconstruction deviates by >3 min: \(minutes(sample,actual)) vs \(expected)")
}
let custom=HaqibaCalculation.calculate(day:sample,latitude:32.4721,
        longitude:44.4217,timeZone:city,fajrAngle:16)!
precondition(custom.fajr>p.fajr.addingTimeInterval(7*60))
for month in 1...12 {
    let d=date(2026,month,15)
    guard let events=HaqibaCalculation.calculate(day:d,latitude:32.4721,
                        longitude:44.4217,timeZone:city) else {
        fatalError("No events for month \(month)")
    }
    precondition(events.fajr<events.sunrise && events.sunrise<events.dhuhr)
    precondition(events.dhuhr<events.asr && events.asr<events.sunset)
    precondition(events.sunset<events.maghrib && events.maghrib<events.isha)
}
precondition(HaqibaCalculation.calculate(day:sample,latitude:100,
                    longitude:44.4217,timeZone:city)==nil)
print("PASS: Haqibat compatibility equations, 12 months, angles and chronology")
