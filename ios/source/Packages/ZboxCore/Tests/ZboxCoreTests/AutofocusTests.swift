import XCTest
@testable import ZboxCore

final class AutofocusTests: XCTestCase {
    private func makeSession(advertised: Bool, response: UInt16 = PtpResponseCode.ok) async throws -> (PtpIpSession, ScriptedChannel) {
        let ops: [UInt16] = advertised ? [0x1001, 0x1002, 0x90c1] : [0x1001, 0x1002]
        let fallback = FakeCamera.command(deviceInfo: deviceInfoDataset(operations: ops))
        let channel = ScriptedChannel { bytes in
            if bytes.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue,
               bytes.readLE(UInt16.self, at: 12) == PtpOpCode.nikonAutofocus {
                return [opResponse(response, bytes.readLE(UInt32.self, at: 14))]
            }
            return fallback(bytes)
        }
        let session = PtpIpSession(commandChannel: channel, openEventChannel: { ScriptedChannel(FakeCamera.event) },
                                   config: testConfig(), settle: { _ in })
        _ = try await session.connect()
        _ = try await session.fetchDeviceInfo()
        return (session, channel)
    }
    private func autofocusCount(_ channel: ScriptedChannel) async -> Int {
        let packets = await channel.sent
        return packets.filter { $0.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue &&
            $0.readLE(UInt16.self, at: 12) == PtpOpCode.nikonAutofocus }.count
    }
    func testAdvertisedAutofocusSendsExactlyOnce() async throws {
        let (session, channel) = try await makeSession(advertised: true)
        try await session.autofocusOnce(shouldContinue: { true })
        let count = await autofocusCount(channel)
        XCTAssertEqual(count, 1)
    }
    func testUnsupportedOrCancelledNeverSend() async throws {
        let (unsupported, first) = try await makeSession(advertised: false)
        do { try await unsupported.autofocusOnce(shouldContinue: { true }); XCTFail("not advertised") }
        catch AutofocusError.notAdvertised {}
        let (cancelled, second) = try await makeSession(advertised: true)
        do { try await cancelled.autofocusOnce(shouldContinue: { false }); XCTFail("cancelled") }
        catch AutofocusError.cancelledBeforeCommand {}
        let firstCount = await autofocusCount(first), secondCount = await autofocusCount(second)
        XCTAssertEqual(firstCount, 0); XCTAssertEqual(secondCount, 0)
    }
    func testBusyDoesNotRetry() async throws {
        let (session, channel) = try await makeSession(advertised: true, response: PtpResponseCode.deviceBusy)
        do { try await session.autofocusOnce(shouldContinue: { true }); XCTFail("busy") }
        catch PtpIpSessionError.operationFailed(let code, let op) {
            XCTAssertEqual(code, PtpResponseCode.deviceBusy); XCTAssertEqual(op, PtpOpCode.nikonAutofocus)
        }
        let count = await autofocusCount(channel)
        XCTAssertEqual(count, 1)
    }
}
