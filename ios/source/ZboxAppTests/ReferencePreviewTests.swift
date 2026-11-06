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
