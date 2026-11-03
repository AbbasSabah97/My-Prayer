import Foundation

/// Meeus/NOAA apparent solar declination and shadow-ratio equations.
/// Used for Jafari Asr *virtue*, not for defining the start of Asr permissibility.
enum SolarShadow {
    static func rad(_ d: Double) -> Double { d * .pi / 180 }
    static func deg(_ r: Double) -> Double { r * 180 / .pi }
    static func declination(at instant: Date) -> Double {
        let jd = instant.timeIntervalSince1970 / 86400 + 2440587.5
        let t = (jd - 2451545) / 36525
        let l = (280.46646 + t * (36000.76983 + 0.0003032*t)).truncatingRemainder(dividingBy:360)
        let m = rad(357.52911 + t * (35999.05029 - 0.0001537*t))
        let c = sin(m)*(1.914602-t*(0.004817+0.000014*t))
              + sin(2*m)*(0.019993-0.000101*t) + sin(3*m)*0.000289
        let o=rad(125.04-1934.136*t)
        let longitude=rad(l+c-0.00569-0.00478*sin(o))
        let obliquity=23+(26+(21.448-t*(46.815+t*(0.00059-0.001813*t)))/60)/60+0.00256*cos(o)
        return deg(asin(sin(rad(obliquity))*sin(longitude)))
    }
    static func shadowIncreaseTime(latitude: Double, solarNoon: Date, increase: Double) -> Date? {
        guard (-90...90).contains(latitude), increase.isFinite, increase>0 else { return nil }
        let lat=rad(latitude), dec=rad(declination(at:solarNoon))
        let difference=abs(lat-dec)
        guard difference < .pi/2 else { return nil }
        let altitude=atan(1/(tan(difference)+increase))
        let denominator=cos(lat)*cos(dec)
        guard abs(denominator)>1e-12 else { return nil }
        let cosine=(sin(altitude)-sin(lat)*sin(dec))/denominator
        guard cosine.isFinite, (-1...1).contains(cosine) else { return nil }
        return solarNoon.addingTimeInterval(acos(cosine)*43200/Double.pi)
    }
    static func roundedUpToMinute(_ date: Date) -> Date {
        Date(timeIntervalSince1970:ceil(date.timeIntervalSince1970/60)*60)
    }
}
