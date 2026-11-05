import XCTest
@testable import ZboxCore

final class PictureControl614Tests: XCTestCase {
    private func payload() -> Data {
        var data = Data(repeating: 0, count: 614)
        data[0] = 2
        data.replaceSubrange(2..<7, with: Data("Astia".utf8))
        for i in 0..<256 {
            let value = UInt16(i * 128)
            data[0x66 + 2*i] = UInt8(value >> 8)
            data[0x67 + 2*i] = UInt8(value & 0xff)
        }
        data[0x2f] = 10
        data[0x30] = 20
        return data
    }

    func testReadsNameBigEndianCurveAndPoints() throws {
        let parsed = try PictureControl614.parse(payload())
        XCTAssertEqual(parsed.name, "Astia")
        XCTAssertEqual(parsed.curve.count, 256)
        XCTAssertEqual(parsed.curve[1], 128)
        XCTAssertEqual(parsed.curve[255], 32640)
        XCTAssertTrue(parsed.curveIsNondecreasing)
        XCTAssertEqual(parsed.controlPoints.first?.x, 10)
        XCTAssertEqual(parsed.controlPoints.first?.y, 20)
    }

    func testRejectsInvalidLengthVersionAndLut() {
        XCTAssertThrowsError(try PictureControl614.parse(Data(repeating: 0, count: 613)))
        var badVersion = payload(); badVersion[0] = 1
        XCTAssertThrowsError(try PictureControl614.parse(badVersion))
        var badCurve = payload(); badCurve[0x66] = 0x80
        XCTAssertThrowsError(try PictureControl614.parse(badCurve))
    }

    func testNonMonotoneCurveIsReportedNotRewritten() throws {
        var bytes = payload(); bytes[0x6a] = 0; bytes[0x6b] = 0
        let parsed = try PictureControl614.parse(bytes)
        XCTAssertFalse(parsed.curveIsNondecreasing)
        XCTAssertEqual(parsed.curve[2], 0)
    }
}
