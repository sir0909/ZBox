import XCTest
@testable import ZboxCore

final class PtpContainerTests: XCTestCase {
    func testCommandEncodingIsLittleEndian() {
        let bytes = PtpContainer.command(code: 0x1002, transactionId: 1, params: [1, 2])
        XCTAssertEqual([UInt8](bytes), [
            0x14, 0x00, 0x00, 0x00,   // length = 12 + 8
            0x01, 0x00,               // type = command
            0x02, 0x10,               // code = OpenSession
            0x01, 0x00, 0x00, 0x00,   // transaction id
            0x01, 0x00, 0x00, 0x00,   // param 1
            0x02, 0x00, 0x00, 0x00,   // param 2
        ])
    }

    func testParseHeaderRoundTrip() throws {
        let bytes = PtpContainer.encode(type: .data, code: 0x1009, transactionId: 7, body: Data([1, 2, 3]))
        let header = try PtpContainer.parseHeader(bytes)
        XCTAssertEqual(header.length, 15)
        XCTAssertEqual(header.type, PtpContainerType.data)
        XCTAssertEqual(header.code, 0x1009)
        XCTAssertEqual(header.transactionId, 7)
        XCTAssertTrue(header.payload.isEmpty)
    }

    func testRejectsLengthShorterThanHeader() {
        let bytes = Data([0x04, 0, 0, 0, 0x01, 0, 0x10, 0x10, 0, 0, 0, 0])
        XCTAssertThrowsError(try PtpContainer.parseHeader(bytes)) { error in
            XCTAssertEqual(error as? PtpError, PtpError.invalidLength(4))
        }
    }

    func testParamsDecodedFromPayload() {
        let payload = Data([0x05, 0, 0, 0, 0x06, 0, 0, 0, 0xFF])
        let container = PtpContainer(length: 21, typeRaw: 2, code: 0, transactionId: 0, payload: payload)
        XCTAssertEqual(container.params(), [5, 6])
    }
}
