import XCTest
@testable import ZboxCore

final class ExposureReadingTests: XCTestCase {
    func testKnownValuesAndSignedCompensation() {
        XCTAssertEqual(ExposureProperty.iso.displayValue(Data([0x90, 0x01])), "400")
        XCTAssertEqual(ExposureProperty.fNumber.displayValue(Data([0x18, 0x01])), "f/2.80")
        XCTAssertEqual(ExposureProperty.compensation.displayValue(Data([0x0c, 0xfe])), "-0.500 EV")
        XCTAssertEqual(ExposureProperty.nikonShutter.displayValue(Data([125, 0, 1, 0])), "1/125 秒")
        XCTAssertEqual(ExposureProperty.whiteBalance.displayValue(Data([2, 0])), "0x0002")
    }
    func testInvalidAndUnknownValuesAreNotInvented() {
        XCTAssertNil(ExposureProperty.iso.displayValue(Data([1])))
        XCTAssertNil(ExposureProperty.nikonShutter.displayValue(Data([0, 0, 1, 0])))
        XCTAssertNil(ExposureProperty.fNumber.displayValue(Data([0, 0])))
        XCTAssertEqual(ExposureProperty.iso.displayValue(Data([0xff, 0xff])), "特殊值 (0xFFFF)")
    }
    func testFetchUsesReadOnlyPropertyOperationAndReportsUnavailable() async throws {
        let fallback = FakeCamera.command()
        let command = ScriptedChannel { bytes in
            if bytes.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue {
                let op = bytes.readLE(UInt16.self, at: 12), tx = bytes.readLE(UInt32.self, at: 14)
                if op == PtpOpCode.getDevicePropValue {
                    let property = bytes.readLE(UInt32.self, at: 18)
                    if property == UInt32(ExposureProperty.iso.rawValue) {
                        return dataIn(tx, Data([0x90, 0x01])) + [opResponse(PtpResponseCode.ok, tx)]
                    }
                    return [opResponse(PtpResponseCode.operationNotSupported, tx)]
                }
            }
            return fallback(bytes)
        }
        let session = PtpIpSession(commandChannel: command,
            openEventChannel: { ScriptedChannel(FakeCamera.event) }, config: testConfig(), settle: { _ in })
        _ = try await session.connect()
        let iso = try await session.fetchExposure(.iso)
        let f = try await session.fetchExposure(.fNumber)
        XCTAssertEqual(iso, "400")
        XCTAssertNil(f)
        let sent = await command.sent
        XCTAssertEqual(Array(sent.dropFirst().map { operationOf($0).opCode }.suffix(2)), [0x1015, 0x1015])
    }
}
