import XCTest
@testable import ZboxCore

final class ShutterTests: XCTestCase {
    func testExplicitSingleShotUsesCameraCardAndChecksResponse() async throws {
        let fallback = FakeCamera.command()
        let command = ScriptedChannel { bytes in
            if bytes.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue {
                let op = bytes.readLE(UInt16.self, at: 12), tx = bytes.readLE(UInt32.self, at: 14)
                if op == PtpOpCode.nikonDeviceReady || op == PtpOpCode.nikonCaptureToMedia {
                    return [opResponse(PtpResponseCode.ok, tx)]
                }
            }
            return fallback(bytes)
        }
        let session = PtpIpSession(commandChannel: command,
            openEventChannel: { ScriptedChannel(FakeCamera.event) }, config: testConfig(), settle: { _ in })
        _ = try await session.connect()
        let ready = try await session.nikonDeviceReady()
        XCTAssertTrue(ready)
        try await session.captureToCardOnce()
        let sent = await command.sent
        let operations = sent.dropFirst().map { operationOf($0) }
        XCTAssertEqual(Array(operations.suffix(2).map { $0.opCode }), [0x90c8, 0x9207])
        XCTAssertEqual(operations.last?.params, [UInt32.max, 0])
    }
    func testBusyReadyDoesNotTriggerCaptureAndUnknownErrorThrows() async throws {
        let fallback = FakeCamera.command()
        let command = ScriptedChannel { bytes in
            if bytes.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue,
               bytes.readLE(UInt16.self, at: 12) == PtpOpCode.nikonDeviceReady {
                return [opResponse(PtpResponseCode.deviceBusy, bytes.readLE(UInt32.self, at: 14))]
            }
            return fallback(bytes)
        }
        let session = PtpIpSession(commandChannel: command,
            openEventChannel: { ScriptedChannel(FakeCamera.event) }, config: testConfig(), settle: { _ in })
        _ = try await session.connect()
        let ready = try await session.nikonDeviceReady()
        XCTAssertFalse(ready)
        let sent = await command.sent
        XCTAssertFalse(sent.dropFirst().map { operationOf($0).opCode }.contains(PtpOpCode.nikonCaptureToMedia))
        do { try await session.captureToCardOnce(); XCTFail("capture failure ignored") }
        catch PtpIpSessionError.operationFailed(let code, let op) {
            XCTAssertEqual(code, PtpResponseCode.operationNotSupported)
            XCTAssertEqual(op, PtpOpCode.nikonCaptureToMedia)
        }
    }
}
