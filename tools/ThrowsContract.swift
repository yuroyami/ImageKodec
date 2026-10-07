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
expectsError("negative animation play count") {
    _ = try KiteAnimation(width: 1, height: 1, frames: [frame], loopCount: -1)
}
let largeCount = try KiteAnimation(width: 1, height: 1, frames: [frame], loopCount: 4294967295)
precondition(largeCount.loopCount == 4294967295)
let wideBitmap = try KiteBitmap(width: 3, height: 2, argb: KotlinIntArray(size: 6))
let exactBitmap = try wideBitmap.downscaledTo(targetWidth: 2, targetHeight: 1)
precondition(exactBitmap.width == 2 && exactBitmap.height == 1)
let wideFrame = KiteFrame(bitmap: wideBitmap, delayMillis: 250, delayRawCentiseconds: 25)
let wideAnimation = try KiteAnimation(width: 3, height: 2, frames: [wideFrame], loopCount: 4294967295)
let exactAnimation = try wideAnimation.downscaledTo(targetWidth: 2, targetHeight: 1)
precondition(exactAnimation.width == 2 && exactAnimation.height == 1)
precondition(exactAnimation.loopCount == 4294967295)
expectsError("bitmap indexing") { _ = try bitmap.get(x: 1, y: 0) }
expectsError("bitmap crop") { _ = try bitmap.cropped(x: 0, y: 0, width: 2, height: 1) }
expectsError("animation crop") { _ = try animation.cropped(x: 0, y: 0, width: 2, height: 1) }
expectsError("bitmap scale") { _ = try bitmap.scaled(maxWidth: 0, maxHeight: 1) }
expectsError("animation scale") { _ = try animation.scaled(maxWidth: 0, maxHeight: 1) }
expectsError("bitmap exact downscale") { _ = try bitmap.downscaledTo(targetWidth: 2, targetHeight: 1) }
expectsError("animation exact downscale") { _ = try animation.downscaledTo(targetWidth: 0, targetHeight: 1) }
precondition(caught == 10)
print("All ten argument failures were recovered in Swift; the large play count is exact")
