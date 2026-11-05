import Foundation
import XCTest
@testable import ZboxCore

final class PictureControlAndProbeTests: XCTestCase {
    private func session(payload: Data, code: UInt16 = PtpResponseCode.ok) -> (PtpIpSession, ScriptedChannel) {
        let fallback = FakeCamera.command()
        let command = ScriptedChannel { bytes in
            if bytes.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue {
                let op = bytes.readLE(UInt16.self, at: 12)
                let tx = bytes.readLE(UInt32.self, at: 14)
                if op == PtpOpCode.nikonGetPictureControl || op == PtpOpCode.getStorageIDs {
                    return dataIn(tx, payload) + [opResponse(code, tx)]
                }
            }
            return fallback(bytes)
        }
        let event = ScriptedChannel(FakeCamera.event)
        return (PtpIpSession(commandChannel: command, openEventChannel: { event },
                             config: testConfig(), settle: { _ in }, randomSource: { 5 }), command)
    }

    func testSlotNineRequestAndName() async throws {
        var raw = Data(repeating: 0, count: 614)
        raw[0] = 2
        raw.replaceSubrange(2..<7, with: Data("Astia".utf8))
        let (camera, command) = session(payload: raw)
        _ = try await camera.connect()
        let slot = try await camera.fetchPictureControl(index: 9)
        XCTAssertEqual(slot.state, .loaded(name: "Astia", bytes: 614))
        let request = operationOf(await command.sent.last!)
        XCTAssertEqual(request.opCode, 0x90CC)
        XCTAssertEqual(request.params, [0x00D1, 0])
    }

    func testFirstSlotAndOutOfRange() async throws {
        let (camera, command) = session(payload: Data(repeating: 0, count: 36))
        _ = try await camera.connect()
        let slot = try await camera.fetchPictureControl(index: 1)
        XCTAssertEqual(slot.state, .empty)
        let request = operationOf(await command.sent.last!)
        XCTAssertEqual(request.params, [0x00C9, 0])
        do { _ = try await camera.fetchPictureControl(index: 0); XCTFail("invalid slot") }
        catch PtpIpSessionError.malformed(_, _) { }
    }

    func testAccessDeniedIsNotMistakenForEmpty() {
        XCTAssertEqual(PictureControlSlot.decode(index: 1, responseCode: 0x200F, data: Data()).state, .unavailable(responseCode: 0x200F))
    }

    func testMalformedAndPrefixedPayloads() {
        XCTAssertEqual(PictureControlSlot.decode(index: 1, responseCode: 0x2001, data: Data()).state, .unexpectedData(bytes: 0))
        XCTAssertEqual(PictureControlSlot.decode(index: 1, responseCode: 0x2001, data: Data([1, 2])).state, .unexpectedData(bytes: 2))
        var payload = Data(repeating: 0, count: 614); payload[0] = 2
        let prefixed = Data([9, 9]) + payload
        XCTAssertEqual(PictureControlSlot.decode(index: 3, responseCode: 0x2001, data: prefixed).state, .loaded(name: "C-3", bytes: 616))
    }

    func testProbeUsesGetStorageIdsAndAcceptsAnyPtpResponse() async throws {
        let (camera, command) = session(payload: Data(), code: PtpResponseCode.operationNotSupported)
        _ = try await camera.connect()
        try await camera.probeConnection()
        let request = operationOf(await command.sent.last!)
        XCTAssertEqual(request.opCode, PtpOpCode.getStorageIDs)
        XCTAssertTrue(request.params.isEmpty)
    }
}
