import XCTest
@testable import ZboxCore

private actor SlotWireFake: PtpIpByteChannel {
    private let fallback = FakeCamera.command(deviceInfo: deviceInfoDataset(operations: [0x1001, 0x1002, 0x90CC, 0x90CD]))
    private var inbox: [Data] = []
    private var packets: [Data] = []
    private var current = RecipePayload.linearTemplate()
    private var writes = 0
    private var loseAck = false
    enum Fault: Error { case interrupted }
    func loseNextAcknowledgement() { loseAck = true }
    func send(_ data: Data) async throws {
        packets.append(data)
        let type = data.readLE(UInt32.self, at: 4)
        if type == PtpIpPacketType.operationRequest.rawValue {
            let op = data.readLE(UInt16.self, at: 12), tx = data.readLE(UInt32.self, at: 14)
            if op == 0x90CC { inbox += dataIn(tx, current) + [opResponse(PtpResponseCode.ok, tx)]; return }
            if op == 0x90CD { writes += 1; return }
        } else if type == PtpIpPacketType.startData.rawValue { return }
        else if type == PtpIpPacketType.endData.rawValue {
            let tx = data.readLE(UInt32.self, at: 8)
            current = data.slice(at: 12, count: data.count - 12)
            if loseAck { throw Fault.interrupted }
            inbox.append(opResponse(PtpResponseCode.ok, tx)); return
        }
        inbox += fallback(data)
    }
    func receive() async throws -> Data { inbox.isEmpty ? Data() : inbox.removeFirst() }
    func close() async {}
    func snapshot() -> (Int, [Data]) { (writes, packets) }
}

final class ExperimentalSlotWireTests: XCTestCase {
    private func session(_ camera: SlotWireFake) async throws -> PtpIpSession {
        let s = PtpIpSession(commandChannel: camera, openEventChannel: { ScriptedChannel(FakeCamera.event) },
                             config: testConfig(), settle: { _ in })
        _ = try await s.connect(); _ = try await s.fetchDeviceInfo(); return s
    }
    func testOneCorrectWireWriteAfterOriginalComparison() async throws {
        let camera = SlotWireFake(), s = try await session(camera)
        let original = RecipePayload.linearTemplate()
        let target = try RecipePayload.edit(template: original, name: "NEXT")
        try await s.experimentalSetPictureControlOnce(index: 2, expectedGUID: FakeCamera.guid,
                                                       original: original, target: target, shouldContinue: { true })
        let (count, packets) = await camera.snapshot(); XCTAssertEqual(count, 1)
        let request = try XCTUnwrap(packets.first { $0.readLE(UInt32.self, at: 4) == PtpIpPacketType.operationRequest.rawValue && $0.readLE(UInt16.self, at: 12) == 0x90CD })
        XCTAssertEqual(operationOf(request).params, [0xCA, 0]); XCTAssertEqual(request.readLE(UInt32.self, at: 8), 2)
        let end = try XCTUnwrap(packets.last { $0.readLE(UInt32.self, at: 4) == PtpIpPacketType.endData.rawValue })
        XCTAssertEqual(end.slice(at: 12, count: 614), target)
    }
    func testLostAcknowledgementClosesSessionAndNeverRetries() async throws {
        let camera = SlotWireFake(), s = try await session(camera)
        let original = RecipePayload.linearTemplate(), target = try RecipePayload.edit(template: RecipePayload.linearTemplate(), name: "NEXT")
        await camera.loseNextAcknowledgement()
        do {
            try await s.experimentalSetPictureControlOnce(index: 1, expectedGUID: FakeCamera.guid,
                original: original, target: target, shouldContinue: { true })
            XCTFail("lost acknowledgement accepted")
        } catch {}
        let (count, _) = await camera.snapshot(); XCTAssertEqual(count, 1)
        let info = await s.connectionInfo; XCTAssertNil(info)
    }
    func testChangedSlotBadTemplateAndCancellationNeverWrite() async throws {
        for scenario in 0...2 {
            let camera = SlotWireFake(), s = try await session(camera)
            var original = RecipePayload.linearTemplate()
            var target = try RecipePayload.edit(template: original, name: "NEXT")
            if scenario == 0 { original[0x50] = 1; target[0x50] = 1 }
            if scenario == 1 { target[0x50] = 1 }
            do {
                try await s.experimentalSetPictureControlOnce(index: 1, expectedGUID: FakeCamera.guid,
                    original: original, target: target, shouldContinue: { scenario != 2 })
                XCTFail("unsafe write was allowed")
            } catch {}
            let (count, _) = await camera.snapshot(); XCTAssertEqual(count, 0)
        }
    }
}
