import XCTest
@testable import ZboxCore

final class PreviewColorAdjustmentsTests: XCTestCase {
    func testNeutralIsExactlyIdentity() {
        let model = PreviewColorAdjustments.neutral
        for i in 0...255 {
            let c = UInt8(i), result = model.apply(red: c, green: 255 - c, blue: c / 2)
            XCTAssertEqual(result.0, c); XCTAssertEqual(result.1, 255 - c); XCTAssertEqual(result.2, c / 2)
        }
    }
    func testMonochromeAndAdjustmentsActuallyChangePixels() throws {
        let mono = try XCTUnwrap(PreviewColorAdjustments(saturation: 0, hue: 0, contrast: 0, brightness: 0, monochrome: true))
        let gray = mono.apply(red: 240, green: 40, blue: 10)
        XCTAssertEqual(gray.0, gray.1); XCTAssertEqual(gray.1, gray.2)
        let modified = try XCTUnwrap(PreviewColorAdjustments(saturation: 2, hue: 2, contrast: 1, brightness: 1, monochrome: false))
        let result = modified.apply(red: 170, green: 80, blue: 20)
        XCTAssertNotEqual([result.0, result.1, result.2], [170, 80, 20])
    }
    func testOutOfRangeAndNonfiniteFailClosed() {
        XCTAssertNil(PreviewColorAdjustments(saturation: .nan, hue: 0, contrast: 0, brightness: 0, monochrome: false))
        XCTAssertNil(PreviewColorAdjustments(saturation: 0, hue: .infinity, contrast: 0, brightness: 0, monochrome: false))
        XCTAssertNil(PreviewColorAdjustments(saturation: 4, hue: 0, contrast: 0, brightness: 0, monochrome: false))
        XCTAssertNil(PreviewColorAdjustments(saturation: 0, hue: 0, contrast: 0, brightness: -3, monochrome: false))
    }
}
