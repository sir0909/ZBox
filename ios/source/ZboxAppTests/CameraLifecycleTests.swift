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
    private var inbox: [Data] = []
    private var ops: [UInt16] = []
    init(pausedOp: UInt16, pause: PausePoint) { self.pausedOp = pausedOp; self.pause = pause }
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
            } else {
                inbox.append(response(tx))
            }
            if op == pausedOp { await pause.wait() }
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
    private func makeModel(pausedOp: UInt16) async throws -> (CameraConnectionModel, FakeAppCommand, PausePoint) {
        let pause = PausePoint(), command = FakeAppCommand(pausedOp: pausedOp, pause: pause)
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
}
