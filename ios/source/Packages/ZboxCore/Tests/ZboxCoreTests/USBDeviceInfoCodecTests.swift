import XCTest
@testable import ZboxCore

final class USBDeviceInfoCodecTests: XCTestCase {
    private func dataset() -> Data {
        func str(_ value: String) -> Data {
            let chars = Array(value.utf16) + [0]
            var data = Data([UInt8(chars.count)])
            for char in chars { data.appendLE(char) }
            return data
        }
        var data = Data()
        data.appendLE(UInt16(100)); data.appendLE(UInt32(10)); data.appendLE(UInt16(100))
        data.append(0); data.appendLE(UInt16(0))
        data.appendLE(UInt32(1)); data.appendLE(PtpOpCode.getDeviceInfo)
        for _ in 0..<4 { data.appendLE(UInt32(0)) }
        data += str("Nikon") + str("Z 30") + str("1.0") + str("123")
        return data
    }
    private func response(_ tx: UInt32, code: UInt16 = PtpResponseCode.ok, params: Data = Data()) -> Data {
        PtpContainer.encode(type: .response, code: code, transactionId: tx, body: params)
    }
    func testBareAndExplicitWrappedData() throws {
        let body = dataset(), tx: UInt32 = 7
        let a = try USBDeviceInfoCodec.decode(response: response(tx), data: body, format: .bareDataset, transactionID: tx)
        XCTAssertEqual(a.manufacturer, "Nikon"); XCTAssertEqual(a.model, "Z 30")
        XCTAssertTrue(a.operations.contains(PtpOpCode.getDeviceInfo))
        let wrapped = PtpContainer.data(code: PtpOpCode.getDeviceInfo, transactionId: tx, payload: body)
        let b = try USBDeviceInfoCodec.decode(response: response(tx), data: wrapped, format: .ptpDataContainer, transactionID: tx)
        XCTAssertEqual(a, b)
        XCTAssertThrowsError(try USBDeviceInfoCodec.decode(response: response(tx), data: wrapped, format: .bareDataset, transactionID: tx))
    }
    func testRejectsWrongTransactionStatusAndMalformedResponse() {
        let body = dataset()
        XCTAssertThrowsError(try USBDeviceInfoCodec.decode(response: response(8), data: body, format: .bareDataset, transactionID: 7)) {
            XCTAssertEqual($0 as? USBDeviceInfoCodec.Failure, .wrongTransaction)
        }
        XCTAssertThrowsError(try USBDeviceInfoCodec.decode(response: response(7, code: 0x2005), data: body, format: .bareDataset, transactionID: 7)) {
            XCTAssertEqual($0 as? USBDeviceInfoCodec.Failure, .cameraResponse(0x2005))
        }
        let junk = response(7) + Data([1])
        XCTAssertThrowsError(try USBDeviceInfoCodec.decode(response: junk, data: body, format: .bareDataset, transactionID: 7)) {
            XCTAssertEqual($0 as? USBDeviceInfoCodec.Failure, .responseMalformed)
        }
        let tooManyParams = response(7, params: Data(repeating: 0, count: 24))
        XCTAssertThrowsError(try USBDeviceInfoCodec.decode(response: tooManyParams, data: body, format: .bareDataset, transactionID: 7))
        let wrongType = PtpContainer.encode(type: .event, code: 0x2001, transactionId: 7, body: Data())
        XCTAssertThrowsError(try USBDeviceInfoCodec.decode(response: wrongType, data: body, format: .bareDataset, transactionID: 7))
    }
    func testRejectsUnsafeDataContainerAndOversizedAndTruncatedDataset() {
        let tx: UInt32 = 7, body = dataset()
        for invalid in [PtpContainer.data(code: 0x1009, transactionId: tx, payload: body),
                        PtpContainer.data(code: 0x1001, transactionId: tx + 1, payload: body),
                        PtpContainer.data(code: 0x1001, transactionId: tx, payload: body) + Data([0])] {
            XCTAssertThrowsError(try USBDeviceInfoCodec.decode(response: response(tx), data: invalid, format: .ptpDataContainer, transactionID: tx)) {
                XCTAssertEqual($0 as? USBDeviceInfoCodec.Failure, .invalidDataContainer)
            }
        }
        let huge = Data(repeating: 0, count: USBDeviceInfoCodec.maxDatasetBytes + 1)
        XCTAssertThrowsError(try USBDeviceInfoCodec.decode(response: response(tx), data: huge, format: .bareDataset, transactionID: tx)) {
            XCTAssertEqual($0 as? USBDeviceInfoCodec.Failure, .dataTooLarge)
        }
        XCTAssertThrowsError(try USBDeviceInfoCodec.decode(response: response(tx), data: Data(body.dropLast()), format: .bareDataset, transactionID: tx)) {
            XCTAssertEqual($0 as? USBDeviceInfoCodec.Failure, .invalidDataset)
        }
    }
}
