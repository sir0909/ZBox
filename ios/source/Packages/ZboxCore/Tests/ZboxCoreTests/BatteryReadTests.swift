import Foundation
import XCTest
@testable import ZboxCore

final class BatteryReadTests: XCTestCase {
    private func makeSession(payload: Data, responseCode: UInt16 = PtpResponseCode.ok) -> (PtpIpSession, ScriptedChannel) {
        let fallback = FakeCamera.command()
        let command = ScriptedChannel { bytes in
            let type = bytes.readLE(UInt32.self, at: 4)
            if type == PtpIpPacketType.operationRequest.rawValue &&
                bytes.readLE(UInt16.self, at: 12) == PtpOpCode.getDevicePropValue {
                let tx = bytes.readLE(UInt32.self, at: 14)
                return dataIn(tx, payload) + [opResponse(responseCode, tx)]
            }
            return fallback(bytes)
        }
        let event = ScriptedChannel(FakeCamera.event)
        let session = PtpIpSession(commandChannel: command, openEventChannel: { event },
                                   config: testConfig(), settle: { _ in }, randomSource: { 5 })
        return (session, command)
    }

    func testReadsBatteryWithCorrectOperationAndProperty() async throws {
        let (session, channel) = makeSession(payload: Data([82]))
        _ = try await session.connect()
        let level = try await session.fetchBatteryLevel()
        XCTAssertEqual(level, 82)
        let request = operationOf(await channel.sent.last!)
        XCTAssertEqual(request.opCode, PtpOpCode.getDevicePropValue)
        XCTAssertEqual(request.params, [0x5001])
    }

    func testUnknownAndMalformedBatteryValuesReturnNil() async throws {
        for payload in [Data(), Data([0xff]), Data([101]), Data([50, 0])] {
            let (session, _) = makeSession(payload: payload)
            _ = try await session.connect()
            let level = try await session.fetchBatteryLevel()
            XCTAssertNil(level)
        }
    }

    func testFailureIsNotReportedAsBatteryValue() async throws {
        let (session, _) = makeSession(payload: Data(), responseCode: PtpResponseCode.operationNotSupported)
        _ = try await session.connect()
        do {
            _ = try await session.fetchBatteryLevel()
            XCTFail("expected operationFailed")
        } catch PtpIpSessionError.operationFailed(let code, let opCode) {
            XCTAssertEqual(code, PtpResponseCode.operationNotSupported)
            XCTAssertEqual(opCode, PtpOpCode.getDevicePropValue)
        }
    }
}
