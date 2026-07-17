import CoreGraphics
import Foundation

enum BlackFrameDetector {
    private static let sampleGrid = 16
    private static let blackThreshold: UInt8 = 12
    private static let minBlackRatio = 0.92

    static func isMostlyBlack(_ image: CGImage) -> Bool {
        let thumbW = sampleGrid
        let thumbH = sampleGrid

        guard
            let context = CGContext(
                data: nil,
                width: thumbW,
                height: thumbH,
                bitsPerComponent: 8,
                bytesPerRow: thumbW * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ),
            let data = context.data
        else { return false }

        context.interpolationQuality = .none
        context.draw(image, in: CGRect(x: 0, y: 0, width: thumbW, height: thumbH))

        let bytesPerPixel = 4
        let total = thumbW * thumbH
        var blackCount = 0

        for i in 0..<total {
            let offset = i * bytesPerPixel
            let r = data.load(fromByteOffset: offset, as: UInt8.self)
            let g = data.load(fromByteOffset: offset + 1, as: UInt8.self)
            let b = data.load(fromByteOffset: offset + 2, as: UInt8.self)
            if max(r, g, b) <= blackThreshold {
                blackCount += 1
            }
        }

        return Double(blackCount) / Double(total) >= minBlackRatio
    }
}
