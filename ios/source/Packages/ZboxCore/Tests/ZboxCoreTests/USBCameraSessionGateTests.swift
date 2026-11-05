import XCTest
@testable import ZboxCore

final class USBCameraSessionGateTests: XCTestCase {
    func testReadyRequiresBothOpenAndCompleteCatalog() {
        var gate = USBCameraSessionGate()
        let id = gate.beginConnection()
        XCTAssertFalse(gate.canSendPTP)
        gate.catalogReady(for: id)
        XCTAssertFalse(gate.canSendPTP)
        gate.sessionOpened(for: id)
        XCTAssertFalse(gate.canSendPTP)
        gate.catalogReady(for: id)
        XCTAssertTrue(gate.canSendPTP)
        gate.detach(for: id)
        XCTAssertFalse(gate.canSendPTP)
    }

    func testOldCallbacksCannotOpenOrCloseNewSession() {
        var gate = USBCameraSessionGate()
        let old = gate.beginConnection()
        let current = gate.beginConnection()
        gate.sessionOpened(for: old)
        gate.catalogReady(for: old)
        gate.detach(for: old)
        XCTAssertEqual(gate.connectionID, current)
        XCTAssertEqual(gate.phase, .opening)
        gate.sessionOpened(for: current)
        gate.catalogReady(for: current)
        XCTAssertTrue(gate.canSendPTP)
        gate.detach(for: old)
        XCTAssertTrue(gate.canSendPTP)
    }
}
