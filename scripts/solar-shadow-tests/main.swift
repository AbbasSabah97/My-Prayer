import Foundation

let zone=TimeZone(identifier:"Asia/Baghdad")!
var cal=Calendar(identifier:.gregorian);cal.timeZone=zone
let noon=cal.date(from:DateComponents(year:2026,month:11,day:3,hour:11,minute:46))!
precondition(abs(SolarShadow.declination(at:noon)+14.83)<0.5)
let a=SolarShadow.shadowIncreaseTime(latitude:32.4721,solarNoon:noon,increase:2.0/7.0)!
let b=SolarShadow.shadowIncreaseTime(latitude:32.4721,solarNoon:noon,increase:4.0/7.0)!
let c=SolarShadow.shadowIncreaseTime(latitude:32.4721,solarNoon:noon,increase:6.0/7.0)!
let d=SolarShadow.shadowIncreaseTime(latitude:32.4721,solarNoon:noon,increase:1.0)!
precondition(noon<a && a<b && b<c && c<d)
precondition((50...160).contains(a.timeIntervalSince(noon)/60))
let rounded=SolarShadow.roundedUpToMinute(a)
precondition(rounded >= a && rounded.timeIntervalSince(a)<60)
precondition(SolarShadow.shadowIncreaseTime(latitude:100,solarNoon:noon,increase:2.0/7.0)==nil)
for month in 1...12 {
    let date=cal.date(from:DateComponents(year:2026,month:month,day:15,hour:12))!
    let decl=SolarShadow.declination(at:date)
    precondition(decl.isFinite && (-24...24).contains(decl))
    let start=SolarShadow.shadowIncreaseTime(latitude:32.4721,solarNoon:date,increase:2.0/7.0)!
    let end=SolarShadow.shadowIncreaseTime(latitude:32.4721,solarNoon:date,increase:6.0/7.0)!
    precondition(start<end)
}
print("PASS: 12 months solar shadow and rounding tests")
