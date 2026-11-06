import XCTest
@testable import ZboxCore

final class USBReadOnlyProbeTests: XCTestCase {
    private func start(_ probe: inout USBReadOnlyProbe) -> UUID {
        guard case .openSession(let id) = probe.start() else { XCTFail("expected open"); return UUID() }
        return id
    }
    private func ready(_ probe: inout USBReadOnlyProbe) -> UUID {
        let id = start(&probe)
        XCTAssertNil(probe.sessionOpened(for: id))
        probe.catalogReady(for: id)
        XCTAssertEqual(probe.phase, .ready)
        return id
    }
    private func dataset() -> Data {
        func str(_ value: String) -> Data {
            let chars = Array(value.utf16) + [0]
            var data = Data([UInt8(chars.count)])
            for char in chars { data.appendLE(char) }
            return data
        }
        var d = Data()
        d.appendLE(UInt16(100)); d.appendLE(UInt32(10)); d.appendLE(UInt16(100))
        d.append(0); d.appendLE(UInt16(0))
        d.appendLE(UInt32(1)); d.appendLE(PtpOpCode.getDeviceInfo)
        for _ in 0..<4 { d.appendLE(UInt32(0)) }
        d += str("Nikon") + str("Z 30") + str("1.0") + str("123")
        return d
    }
    private func response(_ tx: UInt32) -> Data {
        PtpContainer.encode(type: .response, code: PtpResponseCode.ok, transactionId: tx, body: Data())
    }
    func testRequiresBothReadySignalsAndExplicitReadInEitherOrder() {
        var probe = USBReadOnlyProbe()
        let id = start(&probe)
        XCTAssertNil(probe.readDeviceInfo())
        probe.catalogReady(for: id)
        XCTAssertEqual(probe.phase, .opening)
        XCTAssertNil(probe.readDeviceInfo())
        XCTAssertNil(probe.sessionOpened(for: id))
        XCTAssertEqual(probe.phase, .ready)
        guard case .getDeviceInfo(let token, let tx, let bytes) = probe.readDeviceInfo() else { return XCTFail("expected read") }
        XCTAssertEqual(token, id); XCTAssertEqual(tx, 1)
        XCTAssertEqual(bytes, PtpContainer.command(code: 0x1001, transactionId: 1))
        XCTAssertNil(probe.readDeviceInfo()) // one at a time
        XCTAssertNil(probe.receivedDeviceInfo(for: id, transactionID: tx, response: response(tx), data: dataset(), format: .bareDataset))
        XCTAssertEqual(probe.phase, .ready)
        XCTAssertEqual(probe.deviceInfo?.model, "Z 30")
        guard case .getDeviceInfo(_, let next, _) = probe.readDeviceInfo() else { return XCTFail("expected manual second read") }
        XCTAssertEqual(next, 2)
    }
    func testStopDuringOpenWaitsForLateOpenThenClosesAndNoPrematureRestart() {
        var probe = USBReadOnlyProbe()
        let id = start(&probe)
        XCTAssertNil(probe.stop())
        XCTAssertEqual(probe.phase, .closing)
        XCTAssertNil(probe.start())
        probe.sessionClosed(for: id, success: true) // cannot prove pending open is closed
        XCTAssertEqual(probe.phase, .closing)
        XCTAssertEqual(probe.sessionOpened(for: id), .closeSession(id))
        XCTAssertNil(probe.sessionOpened(for: id))
        XCTAssertNil(probe.stop())
        probe.sessionClosed(for: id, success: false)
        XCTAssertEqual(probe.phase, .closing)
        XCTAssertNil(probe.start())
        probe.sessionClosed(for: id, success: true)
        XCTAssertEqual(probe.phase, .idle)
        XCTAssertNotNil(probe.start())
    }
    func testTimeoutAndTransportErrorNeverRetryAndLateReadIsIgnored() {
        var probe = USBReadOnlyProbe()
        let id = ready(&probe)
        guard case .getDeviceInfo(_, let tx, _) = probe.readDeviceInfo() else { return XCTFail() }
        XCTAssertEqual(probe.timeout(for: id), .closeSession(id))
        XCTAssertEqual(probe.lastFailure, .timeout)
        XCTAssertNil(probe.transportFailed(for: id, transactionID: tx))
        XCTAssertNil(probe.receivedDeviceInfo(for: id, transactionID: tx, response: response(tx), data: dataset(), format: .bareDataset))
        XCTAssertNil(probe.deviceInfo)
        XCTAssertNil(probe.readDeviceInfo())
        probe.sessionClosed(for: id, success: true)
        let replacement = start(&probe)
        XCTAssertNotEqual(replacement, id)
        probe.catalogReady(for: id)
        XCTAssertNil(probe.sessionOpened(for: id))
        probe.removed(for: id)
        XCTAssertEqual(probe.token, replacement)
        XCTAssertEqual(probe.phase, .opening)
    }
    func testMalformedReplyForcesCloseAndNoStaleReadCanReplaceNewRead() {
        var probe = USBReadOnlyProbe()
        let id = ready(&probe)
        guard case .getDeviceInfo(_, let tx, _) = probe.readDeviceInfo() else { return XCTFail() }
        XCTAssertNil(probe.receivedDeviceInfo(for: id, transactionID: tx + 1, response: response(tx), data: dataset(), format: .bareDataset))
        XCTAssertEqual(probe.phase, .reading)
        XCTAssertEqual(probe.receivedDeviceInfo(for: id, transactionID: tx, response: Data(), data: dataset(), format: .bareDataset), .closeSession(id))
        XCTAssertEqual(probe.lastFailure, .malformedReply)
        XCTAssertNil(probe.readDeviceInfo())
        probe.removed(for: id)
        XCTAssertEqual(probe.phase, .idle)
    }
    func testOpenFailureRemovalAndUnexpectedCloseFailClosed() {
        var probe = USBReadOnlyProbe()
        let first = start(&probe)
        XCTAssertNil(probe.timeout(for: first)) // open callback still pending
        XCTAssertNil(probe.start())
        probe.openFailed(for: first)
        XCTAssertEqual(probe.phase, .idle)
        XCTAssertEqual(probe.lastFailure, .openFailed)
        let second = ready(&probe)
        XCTAssertEqual(probe.sessionClosed(for: second, success: false), .closeSession(second))
        XCTAssertEqual(probe.phase, .closing)
        XCTAssertNil(probe.start())
        probe.removed(for: second)
        let third = ready(&probe)
        probe.sessionClosed(for: third, success: true) // unsolicited definite close
        XCTAssertEqual(probe.phase, .idle)
        XCTAssertNil(probe.readDeviceInfo())
    }
}
