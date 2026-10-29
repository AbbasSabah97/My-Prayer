import AppKit
import Foundation
let size=1024
let image=NSImage(size:NSSize(width:size,height:size))
image.lockFocus()
let rect=NSRect(x:0,y:0,width:size,height:size)
NSColor(calibratedRed:0.08,green:0.37,blue:0.37,alpha:1).setFill()
NSBezierPath(rect:rect).fill()
let moon=NSRect(x:210,y:220,width:600,height:600)
NSColor(calibratedRed:0.91,green:0.75,blue:0.44,alpha:1).setFill()
NSBezierPath(ovalIn:moon).fill()
NSColor(calibratedRed:0.08,green:0.37,blue:0.37,alpha:1).setFill()
NSBezierPath(ovalIn:NSRect(x:350,y:300,width:520,height:540)).fill()
let center=NSPoint(x:715,y:740)
let star=NSBezierPath()
for i in 0..<10 {
    let a=Double(i)*Double.pi/5-Double.pi/2
    let r=Double(i%2==0 ? 76 : 32)
    let point=NSPoint(x:center.x+CGFloat(cos(a)*r),y:center.y+CGFloat(sin(a)*r))
    if i==0 { star.move(to:point) } else { star.line(to:point) }
}
star.close()
NSColor.white.setFill()
star.fill()
image.unlockFocus()
guard let tiff=image.tiffRepresentation,
      let bitmap=NSBitmapImageRep(data:tiff),
      let png=bitmap.representation(using:.png,properties:[:]) else {
    fatalError("Could not generate icon")
}
let url=URL(fileURLWithPath:"MyPrayer/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
try FileManager.default.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
try png.write(to:url)
print("Generated app icon at \(url.path)")
