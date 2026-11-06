import XCTest
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import ZboxCore
@testable import Zbox

final class ReferencePreviewTests: XCTestCase {
    func testPreviewAppliesLUTAndRejectsBadInput() throws {
        var pixel: [UInt8] = [255, 0, 0, 255]
        let info = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
        let image: CGImage? = pixel.withUnsafeMutableBytes { buffer in
            CGContext(data: buffer.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info)?.makeImage()
        }
        let original = try XCTUnwrap(image)
        let input = try XCTUnwrap(ReferencePreview.encode(original, type: UTType.png.identifier as CFString))
        let output = try XCTUnwrap(ReferencePreview.apply(jpeg: input, lut: (0...255).map { UInt8(255 - $0) }))
        let src = try XCTUnwrap(CGImageSourceCreateWithData(output as CFData, nil))
        let result = try XCTUnwrap(CGImageSourceCreateImageAtIndex(src, 0, nil))
        var readback = [UInt8](repeating: 0, count: 4)
        let ok = readback.withUnsafeMutableBytes { buffer -> Bool in
            guard let ctx = CGContext(data: buffer.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info) else { return false }
            ctx.draw(result, in: CGRect(x: 0, y: 0, width: 1, height: 1)); return true
        }
        XCTAssertTrue(ok); XCTAssertEqual(readback, [0, 255, 255, 255])
        XCTAssertNil(ReferencePreview.apply(jpeg: input, lut: [0]))
        XCTAssertNil(ReferencePreview.apply(jpeg: Data([0]), lut: (0...255).map(UInt8.init)))
    }
    func testPickerThumbnailRemovesGPSAndAppliesSizeBoundWithoutChangingSource() throws {
        let info = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
        let context = try XCTUnwrap(CGContext(data: nil, width: 800, height: 8, bitsPerComponent: 8,
                                              bytesPerRow: 800 * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info))
        context.setFillColor(CGColor(red: 0.8, green: 0.4, blue: 0.2, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 800, height: 8))
        let image = try XCTUnwrap(context.makeImage())
        let data = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(data as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil))
        let properties: [CFString: Any] = [
            kCGImagePropertyOrientation: 6,
            kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 35.6, kCGImagePropertyGPSLatitudeRef: "N",
                                            kCGImagePropertyGPSLongitude: 140.1, kCGImagePropertyGPSLongitudeRef: "E"],
            kCGImagePropertyExifDictionary: [kCGImagePropertyExifDateTimeOriginal: "2025:01:02 03:04:05"]
        ]
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        let original = data as Data
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
        defer { try? FileManager.default.removeItem(at: url) }
        try original.write(to: url)
        let inputSource = try XCTUnwrap(CGImageSourceCreateWithData(original as CFData, nil))
        let inputProps = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(inputSource, 0, nil) as? [CFString: Any])
        XCTAssertNotNil(inputProps[kCGImagePropertyGPSDictionary])
        let thumbnail = try ReferenceThumbnail.readFile(url)
        let source = try XCTUnwrap(CGImageSourceCreateWithData(thumbnail.jpeg as CFData, nil))
        let outputProps = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        XCTAssertNil(outputProps[kCGImagePropertyGPSDictionary])
        let exif = outputProps[kCGImagePropertyExifDictionary] as? [CFString: Any]
        XCTAssertNil(exif?[kCGImagePropertyExifDateTimeOriginal])
        let outWidth = try XCTUnwrap(outputProps[kCGImagePropertyPixelWidth] as? Int)
        let outHeight = try XCTUnwrap(outputProps[kCGImagePropertyPixelHeight] as? Int)
        XCTAssertLessThanOrEqual(max(outWidth, outHeight), 768)
        XCTAssertGreaterThan(outHeight, outWidth) // original orientation 6 has been applied
        XCTAssertEqual(try Data(contentsOf: url), original)
    }

    func testPreviewAppliesMonochromeWithoutChangingInput() throws {
        var pixel: [UInt8] = [200, 60, 20, 255]
        let info = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
        let image = try XCTUnwrap(pixel.withUnsafeMutableBytes { buffer in
            CGContext(data: buffer.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info)?.makeImage()
        })
        let input = try XCTUnwrap(ReferencePreview.encode(image, type: UTType.png.identifier as CFString))
        let originalInput = input
        let mono = try XCTUnwrap(PreviewColorAdjustments(saturation: 0, hue: 0, contrast: 0, brightness: 0, monochrome: true))
        let output = try XCTUnwrap(ReferencePreview.apply(jpeg: input, lut: (0...255).map(UInt8.init), adjustments: mono))
        let src = try XCTUnwrap(CGImageSourceCreateWithData(output as CFData, nil))
        let result = try XCTUnwrap(CGImageSourceCreateImageAtIndex(src, 0, nil))
        var readback = [UInt8](repeating: 0, count: 4)
        let ok = readback.withUnsafeMutableBytes { buffer -> Bool in
            guard let ctx = CGContext(data: buffer.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info) else { return false }
            ctx.draw(result, in: CGRect(x: 0, y: 0, width: 1, height: 1)); return true
        }
        XCTAssertTrue(ok); XCTAssertEqual(readback[0], readback[1]); XCTAssertEqual(readback[1], readback[2])
        XCTAssertEqual(input, originalInput)
    }

}
