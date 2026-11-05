import XCTest
@testable import ZboxCore

final class TIFFPreviewRangeTests: XCTestCase {
    private func littleTIFF(offset: UInt32 = 300_000, length: UInt32 = 1_000) -> Data {
        var d = Data([0x49, 0x49, 0x2a, 0x00]); d += le(UInt32(8)); d += le(UInt16(2))
        for (tag, value) in [(UInt16(0x0201), offset), (UInt16(0x0202), length)] {
            d += le(tag); d += le(UInt16(4)); d += le(UInt32(1)); d += le(value)
        }
        d += le(UInt32(0)); return d
    }
    func testFindsBoundedLittleEndianRangeOutsidePrefix() {
        XCTAssertEqual(TIFFPreviewRange.largest(in: littleTIFF(), fileSize: 400_000), 300_000..<301_000)
    }
    func testFindsExifTiffAndAccountsForBase() {
        var jpeg = Data([0xff, 0xd8, 0xff, 0xe1, 0x00, 0x40, 69, 120, 105, 102, 0, 0])
        jpeg += littleTIFF(offset: 300, length: 20)
        XCTAssertEqual(TIFFPreviewRange.largest(in: jpeg, fileSize: 1000), 312..<332)
    }
    func testRejectsOutOfBoundsHugeAndTruncatedRanges() {
        XCTAssertNil(TIFFPreviewRange.largest(in: littleTIFF(offset: 999, length: 10), fileSize: 1000))
        XCTAssertNil(TIFFPreviewRange.largest(in: littleTIFF(offset: 1, length: 500_000), fileSize: 900_000))
        XCTAssertNil(TIFFPreviewRange.largest(in: littleTIFF().dropLast(), fileSize: 400_000))
        XCTAssertNil(TIFFPreviewRange.largest(in: Data([0, 1, 2, 3]), fileSize: 1000))
    }
    func testBigEndianTiff() {
        let d = Data([0x4d, 0x4d, 0, 42, 0, 0, 0, 8, 0, 2,
                      0x02, 0x01, 0, 4, 0, 0, 0, 1, 0, 0, 0x01, 0x2c,
                      0x02, 0x02, 0, 4, 0, 0, 0, 1, 0, 0, 0, 20,
                      0, 0, 0, 0])
        XCTAssertEqual(TIFFPreviewRange.largest(in: d, fileSize: 1000), 300..<320)
    }
}
