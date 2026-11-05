import Foundation
import XCTest
import ZboxCore
@testable import Zbox

private func little<T: FixedWidthInteger>(_ value: T) -> Data {
    var number = value.littleEndian
    return withUnsafeBytes(of: &number) { Data($0) }
}
private func u32(_ data: Data, _ offset: Int) -> UInt32 {
    (0..<4).reduce(0) { $0 | (UInt32(data[offset + $1]) << (8 * $1)) }
}
private func u16(_ data: Data, _ offset: Int) -> UInt16 {
    UInt16(data[offset]) | (UInt16(data[offset + 1]) << 8)
}
private func response(_ tx: UInt32) -> Data {
    PtpIpCodec.frame(.operationResponse, payload: little(UInt16(0x2001)) + little(tx))
}
private func input(_ tx: UInt32, _ data: Data) -> [Data] {
    [PtpIpCodec.frame(.startData, payload: little(tx) + little(UInt64(data.count))),
     PtpIpCodec.frame(.endData, payload: little(tx) + data), response(tx)]
}

private actor PausePoint {
    private var pending: CheckedContinuation<Void, Never>?
    private var entered = false
    private var observers: [CheckedContinuation<Void, Never>] = []
    func wait() async {
        entered = true
        observers.forEach { $0.resume() }; observers.removeAll()
        await withCheckedContinuation { pending = $0 }
    }
    func untilEntered() async {
        if entered { return }
        await withCheckedContinuation { observers.append($0) }
    }
    func release() { pending?.resume(); pending = nil }
}

private actor FakeAppCommand: PtpIpByteChannel {
    let pausedOp: UInt16
    let pause: PausePoint
    let pauseOccurrence: Int
    private var inbox: [Data] = []
    private var ops: [UInt16] = []
    init(pausedOp: UInt16, pause: PausePoint, pauseOccurrence: Int) {
        self.pausedOp = pausedOp; self.pause = pause; self.pauseOccurrence = pauseOccurrence
    }
    func send(_ data: Data) async throws {
        let type = u32(data, 4)
        if type == PtpIpPacketType.initCommandRequest.rawValue {
            let camera = little(UInt16(0x005A)) + little(UInt16(0))
            inbox.append(PtpIpCodec.frame(.initCommandAck, payload: little(UInt32(7)) + Data(repeating: 0xAB, count: 16) + camera))
        } else if type == PtpIpPacketType.operationRequest.rawValue {
            let op = u16(data, 12), tx = u32(data, 14)
            ops.append(op)
            if op == 0x1004 {
                inbox.append(contentsOf: input(tx, little(UInt32(1)) + little(UInt32(0x10001))))
            } else if op == 0x1014 {
                var descriptor = little(UInt16(0x500f)) + little(UInt16(4)) + Data([1])
                descriptor += little(UInt16(400)) + little(UInt16(400)) + Data([2]) + little(UInt16(2))
                descriptor += little(UInt16(400)) + little(UInt16(800))
                inbox.append(contentsOf: input(tx, descriptor))
            } else if op == 0x1015 {
                inbox.append(contentsOf: input(tx, little(UInt16(400))))
            } else {
                inbox.append(response(tx))
            }
            if op == pausedOp && ops.filter({ $0 == op }).count == pauseOccurrence { await pause.wait() }
        }
    }
    func receive() async throws -> Data { inbox.isEmpty ? Data() : inbox.removeFirst() }
    func close() async {}
    func operations() -> [UInt16] { ops }
}
private actor FakeAppEvent: PtpIpByteChannel {
    private var inbox: [Data] = []
    func send(_ data: Data) async throws {
        if u32(data, 4) == PtpIpPacketType.initEventRequest.rawValue { inbox.append(PtpIpCodec.frame(.initEventAck)) }
    }
    func receive() async throws -> Data { inbox.isEmpty ? Data() : inbox.removeFirst() }
    func close() async {}
}

@MainActor
final class CameraLifecycleTests: XCTestCase {
    private func makeModel(pausedOp: UInt16, pauseOccurrence: Int = 1) async throws -> (CameraConnectionModel, FakeAppCommand, PausePoint) {
        let pause = PausePoint(), command = FakeAppCommand(pausedOp: pausedOp, pause: pause, pauseOccurrence: pauseOccurrence)
        let config = PtpIpSessionConfig(initiatorGuid: Data(repeating: 1, count: 16), initiatorName: "Test", staMode: true)
        let session = PtpIpSession(commandChannel: command, openEventChannel: { FakeAppEvent() }, config: config, settle: { _ in })
        _ = try await session.connect()
        let model = CameraConnectionModel()
        model.installSessionForTesting(session)
        return (model, command, pause)
    }
    private func awaitIdle(_ model: CameraConnectionModel) async throws {
        for _ in 0..<200 {
            if model.liveViewPhase == .idle { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("live view did not return to idle")
    }
    func testInactiveDuringStartStopsBeforeAnyFrameAndBlocksBattery() async throws {
        let (model, command, pause) = try await makeModel(pausedOp: 0x9201)
        model.startLiveView()
        await pause.untilEntered()
        await model.refreshBattery()
        model.appBecameInactive()
        // Allow the lifecycle task to set its stop flag while start is still in flight.
        for _ in 0..<200 {
            if model.liveViewPhase == .stopping { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertEqual(model.liveViewPhase, .stopping)
        await pause.release()
        try await awaitIdle(model)
        let ops = await command.operations()
        XCTAssertTrue(ops.contains(0x9201))
        XCTAssertTrue(ops.contains(0x9202))
        XCTAssertFalse(ops.contains(0x9203))
        XCTAssertFalse(ops.contains(0x1015))
        await model.disconnect()
    }
    func testInactiveDuringAlbumStorageReadCancelsBeforeMetadata() async throws {
        let (model, command, pause) = try await makeModel(pausedOp: 0x1004)
        let loading = Task { await model.loadSTAAlbum() }
        await pause.untilEntered()
        model.appBecameInactive()
        await pause.release()
        await loading.value
        XCTAssertFalse(model.albumBusy)
        XCTAssertTrue(model.albumItems.isEmpty)
        let ops = await command.operations()
        XCTAssertTrue(ops.contains(0x1004))
        XCTAssertFalse(ops.contains(0x9434))
        await model.disconnect()
    }
    func testInactiveDuringDeviceReadyNeverSendsShutter() async throws {
        let (model, command, pause) = try await makeModel(pausedOp: 0x90c8)
        let capture = Task { await model.captureOneToCard() }
        await pause.untilEntered()
        model.appBecameInactive()
        await pause.release()
        await capture.value
        XCTAssertFalse(model.captureBusy)
        let ops = await command.operations()
        XCTAssertTrue(ops.contains(0x90c8))
        XCTAssertFalse(ops.contains(0x9207))
        await model.disconnect()
    }
    func testInactiveDuringExposureRecheckNeverWrites() async throws {
        let (model, command, pause) = try await makeModel(pausedOp: 0x1014, pauseOccurrence: 2)
        await model.loadExposureDescriptor(.iso)
        let descriptor = try XCTUnwrap(model.exposureDescriptor)
        let write = Task { await model.writeExposure(descriptor, desired: little(UInt16(800))) }
        await pause.untilEntered()
        model.appBecameInactive()
        await pause.release()
        await write.value
        XCTAssertFalse(model.exposureBusy)
        XCTAssertEqual(model.exposureEditMessage, "已取消，未发送写入命令")
        let ops = await command.operations()
        XCTAssertFalse(ops.contains(0x1015))
        XCTAssertFalse(ops.contains(0x1016))
        await model.disconnect()
    }
    func testInactiveRejectsNewCameraWorkUntilActive() async throws {
        let (model, command, _) = try await makeModel(pausedOp: 0xffff)
        model.appBecameInactive()
        model.startLiveView()
        await model.captureOneToCard()
        await model.loadSTAAlbum()
        await model.refreshBattery()
        await model.loadExposureDescriptor(.iso)
        let whileInactive = await command.operations()
        XCTAssertEqual(whileInactive, [0x1002]) // handshake only
        XCTAssertFalse(model.canStartLiveView)
        model.appBecameActive()
        XCTAssertTrue(model.canStartLiveView)
        await model.refreshBattery()
        let afterActive = await command.operations()
        XCTAssertTrue(afterActive.contains(0x1015))
        await model.disconnect()
    }
}
