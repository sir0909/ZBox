import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import ZboxCore

struct PreviewAnalysisOutput: Sendable {
    let overlay: Data?
    let histogram: [Int]
    let highlights: Int
    let pixels: Int
}

enum PreviewAnalyzer {
    static func analyze(jpeg: Data, zebra: Bool, peaking: Bool) -> PreviewAnalysisOutput? {
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil) else { return nil }
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                      kCGImageSourceThumbnailMaxPixelSize: 320,
                                      kCGImageSourceCreateThumbnailWithTransform: true]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let w = image.width, h = image.height
        guard w > 0, h > 0, w <= 320, h <= 320 else { return nil }
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        let info = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let ctx = CGContext(data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8,
                bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info) else { return false }
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h)); return true
        }
        guard drawn, let result = PreviewAnalysis.analyze(rgba: pixels, width: w, height: h, zebra: zebra, peaking: peaking) else { return nil }
        var png: Data?
        if zebra || peaking {
            var rgba = result.overlayRGBA
            png = rgba.withUnsafeMutableBytes { buffer -> Data? in
                guard let ctx = CGContext(data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8,
                    bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info),
                      let image = ctx.makeImage() else { return nil }
                let data = NSMutableData()
                guard let dest = CGImageDestinationCreateWithData(data as CFMutableData, UTType.png.identifier as CFString, 1, nil) else { return nil }
                CGImageDestinationAddImage(dest, image, nil)
                guard CGImageDestinationFinalize(dest) else { return nil }
                return data as Data
            }
        }
        return PreviewAnalysisOutput(overlay: png, histogram: result.histogram, highlights: result.highlightCount, pixels: w * h)
    }
}
