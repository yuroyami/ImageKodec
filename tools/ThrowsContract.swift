import Foundation
import ImageKodec

var caught = 0
func expectsError(_ name: String, _ body: () throws -> Void) {
    do {
        try body()
        fatalError("\(name) accepted an invalid argument")
    } catch let error as NSError {
        caught += 1
        print("\(name): caught NSError \(error.domain)")
    }
}

expectsError("bitmap constructor") {
    _ = try KiteBitmap(width: 0, height: 1, argb: KotlinIntArray(size: 0))
}
expectsError("animation constructor") {
    _ = try KiteAnimation(width: 1, height: 1, frames: [], loopCount: 1)
}
let bitmap = try KiteBitmap(width: 1, height: 1, argb: KotlinIntArray(size: 1))
let frame = KiteFrame(bitmap: bitmap, delayMillis: 100, delayRawCentiseconds: 10)
let animation = try KiteAnimation(width: 1, height: 1, frames: [frame], loopCount: 1)
expectsError("bitmap indexing") { _ = try bitmap.get(x: 1, y: 0) }
expectsError("bitmap crop") { _ = try bitmap.cropped(x: 0, y: 0, width: 2, height: 1) }
expectsError("animation crop") { _ = try animation.cropped(x: 0, y: 0, width: 2, height: 1) }
expectsError("bitmap scale") { _ = try bitmap.scaled(maxWidth: 0, maxHeight: 1) }
expectsError("animation scale") { _ = try animation.scaled(maxWidth: 0, maxHeight: 1) }
precondition(caught == 7)
print("All seven argument failures were recovered in Swift")
