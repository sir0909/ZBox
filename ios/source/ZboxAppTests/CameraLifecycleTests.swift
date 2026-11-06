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
private func ptpString(_ text: String) -> Data {
    let units = Array(text.utf16) + [0]
    var data = Data([UInt8(units.count)])
    for unit in units { data += little(unit) }
    return data
}
private func advertisedDeviceInfo() -> Data {
    var d = little(UInt16(100)) + little(UInt32(10)) + little(UInt16(100)) + Data([0]) + little(UInt16(0))
    let ops: [UInt16] = [0x1001, 0x1002, 0x1015, 0x90c1, 0x90c8, 0x90cc, 0x90cd, 0x920a, 0x920b]
    d += little(UInt32(ops.count))
    for op in ops { d += little(op) }
    for _ in 0..<4 { d += little(UInt32(0)) }
    d += ptpString("Nikon") + ptpString("Z 30") + ptpString("1.0") + ptpString("123")
    return d
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
    private(set) var closed = false
    private var slotPayload = RecipePayload.linearTemplate()
    private var loseSlotAck = false
    enum Fault: Error { case lostSlotAck }
    func loseSlotAcknowledgement() { loseSlotAck = true }
    func setSlotPayload(_ data: Data) { slotPayload = data }
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
            if op == 0x1001 {
                inbox.append(contentsOf: input(tx, advertisedDeviceInfo()))
            } else if op == 0x90cc {
                inbox.append(contentsOf: input(tx, slotPayload))
            } else if op == 0x90cd {
                // Wait for the data-out frames before acknowledging.
            } else if op == 0x9203 {
                inbox.append(contentsOf: input(tx, Data([0xff, 0xd8, 0xff, 0xd9])))
            } else if op == 0x1004 {
                inbox.append(contentsOf: input(tx, little(UInt32(1)) + little(UInt32(0x10001))))
            } else if op == 0x9434 {
                var metadata = little(UInt32(1)) + little(UInt32(1)) + little(UInt32(0x29000042))
                metadata += little(UInt32(0)) + Data([0, 3, 4, 5, 2, 1]) + little(UInt16(2025))
                inbox.append(contentsOf: input(tx, metadata))
            } else if op == 0x9421 {
                inbox.append(contentsOf: input(tx, little(UInt64(7))))
            } else if op == 0x9431 {
                inbox.append(contentsOf: input(tx, Data([0, 1, 2, 3, 4, 5, 6])))
            } else if op == 0x1014 {
                var descriptor = little(UInt16(0x500f)) + little(UInt16(4)) + Data([1])
                descriptor += little(UInt16(400)) + little(UInt16(400)) + Data([2]) + little(UInt16(2))
                descriptor += little(UInt16(400)) + little(UInt16(800))
                inbox.append(contentsOf: input(tx, descriptor))
            } else if op == 0x1015 {
                let property = u32(data, 18)
                let value: Data
                if property == 0xd1f0 || property == 0xd1a6 { value = Data([1]) }
                else if property == 0xd0a4 { value = little(UInt32(0)) }
                else { value = little(UInt16(400)) }
                inbox.append(contentsOf: input(tx, value))
            } else {
                inbox.append(response(tx))
            }
            if op == pausedOp && ops.filter({ $0 == op }).count == pauseOccurrence { await pause.wait() }
        } else if type == PtpIpPacketType.startData.rawValue {
            return
        } else if type == PtpIpPacketType.endData.rawValue {
            let tx = u32(data, 8)
            slotPayload = Data(data.dropFirst(12))
            if loseSlotAck { throw Fault.lostSlotAck }
            inbox.append(response(tx))
        }
    }
    func receive() async throws -> Data { inbox.isEmpty ? Data() : inbox.removeFirst() }
    func close() async { closed = true }
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
    private func makeModel(pausedOp: UInt16, pauseOccurrence: Int = 1, supportsMovie: Bool = false,
                           cameraID: String? = nil, backupStore: SlotBackupStore? = nil) async throws -> (CameraConnectionModel, FakeAppCommand, PausePoint) {
        let pause = PausePoint(), command = FakeAppCommand(pausedOp: pausedOp, pause: pause, pauseOccurrence: pauseOccurrence)
        let config = PtpIpSessionConfig(initiatorGuid: Data(repeating: 1, count: 16), initiatorName: "Test", staMode: true)
        let session = PtpIpSession(commandChannel: command, openEventChannel: { FakeAppEvent() }, config: config, settle: { _ in })
        _ = try await session.connect()
        _ = try await session.fetchDeviceInfo()
        let model = CameraConnectionModel(slotBackupStore: backupStore ?? SlotBackupStore())
        model.installSessionForTesting(session, supportsAutofocus: true, supportsMovie: supportsMovie,
                                       cameraID: cameraID ?? (supportsMovie ? "test-camera-guid" : ""))
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
        XCTAssertEqual(whileInactive, [0x1002, 0x1001]) // setup only
        XCTAssertFalse(model.canStartLiveView)
        model.appBecameActive()
        XCTAssertTrue(model.canStartLiveView)
        await model.refreshBattery()
        let afterActive = await command.operations()
        XCTAssertTrue(afterActive.contains(0x1015))
        await model.disconnect()
    }
    func testInactiveDuringPartialDownloadDeletesIncompleteFile() async throws {
        let (model, command, pause) = try await makeModel(pausedOp: 0x9431)
        await model.loadSTAAlbum()
        let item = try XCTUnwrap(model.albumItems.first)
        let temp = FileManager.default.temporaryDirectory
        let before = Set(try FileManager.default.contentsOfDirectory(atPath: temp.path).filter { $0.hasPrefix("ZBox-") })
        let download = Task { await model.downloadSTAItem(item) }
        await pause.untilEntered()
        model.appBecameInactive()
        await pause.release()
        await download.value
        XCTAssertFalse(model.albumBusy)
        XCTAssertNil(model.albumDownloadURL)
        XCTAssertEqual(model.albumMessage, "下载已取消；不完整文件已删除")
        let after = Set(try FileManager.default.contentsOfDirectory(atPath: temp.path).filter { $0.hasPrefix("ZBox-") })
        XCTAssertTrue(after.subtracting(before).isEmpty)
        let ops = await command.operations()
        XCTAssertTrue(ops.contains(0x9431))
        await model.disconnect()
    }
    func testCompletedAlbumDownloadIsNotLostWhenAnotherStarts() async throws {
        let (model, command, _) = try await makeModel(pausedOp: 0xFFFF)
        await model.loadSTAAlbum()
        let item = try XCTUnwrap(model.albumItems.first)
        await model.downloadSTAItem(item)
        let firstURL = try XCTUnwrap(model.albumDownloadURL)
        XCTAssertTrue(FileManager.default.fileExists(atPath: firstURL.path))
        let before = await command.operations()
        await model.downloadSTAItem(item)
        let after = await command.operations()
        XCTAssertEqual(model.albumDownloadURL, firstURL)
        XCTAssertEqual(after.filter { $0 == 0x9421 }.count, before.filter { $0 == 0x9421 }.count)
        XCTAssertTrue(FileManager.default.fileExists(atPath: firstURL.path))
        model.deleteDownloadedAlbumFile()
        XCTAssertNil(model.albumDownloadURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: firstURL.path))
        await model.downloadSTAItem(item)
        let secondURL = try XCTUnwrap(model.albumDownloadURL)
        // iOS may purge a temporary file. Explicit removal then clears only the stale
        // reference so a subsequent download is not permanently blocked.
        try FileManager.default.removeItem(at: secondURL)
        model.deleteDownloadedAlbumFile()
        XCTAssertNil(model.albumDownloadURL)
        XCTAssertTrue(model.albumMessage.contains("已不存在"))
        await model.disconnect()
    }

    func testDisconnectDuringAlbumReadDoesNotPublishStaleResults() async throws {
        let (model, command, pause) = try await makeModel(pausedOp: 0x1004)
        let loading = Task { await model.loadSTAAlbum() }
        await pause.untilEntered()
        await model.disconnect()
        await pause.release()
        await loading.value
        XCTAssertEqual(model.phase, .idle)
        XCTAssertTrue(model.albumItems.isEmpty)
        let ops = await command.operations()
        XCTAssertFalse(ops.contains(0x9434))
    }
    func testInactiveWhileAutofocusQueuedDoesNotSendAF() async throws {
        let (model, command, pause) = try await makeModel(pausedOp: 0x9203)
        model.startLiveView()
        await pause.untilEntered()
        let focus = Task { await model.autofocusOnce() }
        for _ in 0..<200 {
            if model.focusBusy { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertTrue(model.focusBusy)
        model.appBecameInactive()
        await pause.release()
        await focus.value
        try await awaitIdle(model)
        let ops = await command.operations()
        XCTAssertFalse(ops.contains(0x90c1))
        XCTAssertTrue(ops.contains(0x9202))
        await model.disconnect()
    }
    func testBackgroundDuringMovieStartKeepsDurableRiskAndNeverAutoStops() async throws {
        UserDefaults.standard.removeObject(forKey: "camera.movie.mayBeActive.guid")
        defer { UserDefaults.standard.removeObject(forKey: "camera.movie.mayBeActive.guid") }
        let (model, command, pause) = try await makeModel(pausedOp: 0x920a, supportsMovie: true)
        model.startLiveView()
        for _ in 0..<200 {
            if model.liveViewPhase == .streaming { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertTrue(model.canStartMovie)
        let start = Task { await model.startMovie() }
        await pause.untilEntered()
        XCTAssertTrue(model.movieMayBeActive)
        let restored = CameraConnectionModel()
        XCTAssertTrue(restored.movieMayBeActive)
        XCTAssertEqual(UserDefaults.standard.string(forKey: "camera.movie.mayBeActive.guid"), "test-camera-guid")
        model.appBecameInactive()
        await pause.release()
        await start.value
        try await awaitIdle(model)
        let before = await command.operations()
        XCTAssertFalse(before.contains(0x920b)); XCTAssertFalse(before.contains(0x9202))
        XCTAssertTrue(model.movieMayBeActive)
        model.appBecameActive()
        XCTAssertTrue(model.canStopMovie)
        await model.stopMovie()
        XCTAssertTrue(model.movieMayBeActive) // acknowledgement alone is not proof of camera state
        let after = await command.operations()
        XCTAssertEqual(after.filter { $0 == 0x920b }.count, 1)
        model.acknowledgeMovieStopped()
        XCTAssertFalse(model.movieMayBeActive)
        await model.disconnect()
    }
    func testInactiveDuringMoviePrerequisitesNeverStartsRecording() async throws {
        UserDefaults.standard.removeObject(forKey: "camera.movie.mayBeActive.guid")
        defer { UserDefaults.standard.removeObject(forKey: "camera.movie.mayBeActive.guid") }
        let (model, command, pause) = try await makeModel(pausedOp: 0x1015, supportsMovie: true)
        model.startLiveView()
        for _ in 0..<200 {
            if model.liveViewPhase == .streaming { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertTrue(model.canStartMovie)
        let start = Task { await model.startMovie() }
        await pause.untilEntered()
        model.appBecameInactive()
        await pause.release()
        await start.value
        try await awaitIdle(model)
        let ops = await command.operations()
        XCTAssertFalse(ops.contains(0x920a))
        XCTAssertFalse(model.movieMayBeActive)
        XCTAssertNil(UserDefaults.standard.string(forKey: "camera.movie.mayBeActive.guid"))
        await model.disconnect()
    }
    func testManualSlotBackupReadsOnceAndNeverWrites() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SlotBackupStore(directory: folder)
        let (model, command, pause) = try await makeModel(pausedOp: 0x90cc,
            cameraID: String(repeating: "ab", count: 16), backupStore: store)
        XCTAssertTrue(model.canBackupSlot)
        let work = Task { await model.backupSlot(index: 2) }
        await pause.untilEntered()
        XCTAssertFalse(model.canBackupSlot)
        await pause.release(); await work.value
        let listing = try await store.listBackups()
        XCTAssertEqual(listing.records.count, 1)
        XCTAssertEqual(listing.records[0].slot, 2)
        XCTAssertEqual(listing.records[0].original, RecipePayload.linearTemplate())
        let ops = await command.operations()
        XCTAssertEqual(ops.filter { $0 == 0x90cc }.count, 1)
        XCTAssertFalse(ops.contains(0x90cd)); XCTAssertFalse(ops.contains(0x90ce))
        await model.disconnect()
    }
    func testInactiveThenActiveCannotSaveStaleSlotBackup() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SlotBackupStore(directory: folder)
        let (model, command, pause) = try await makeModel(pausedOp: 0x90cc,
            cameraID: String(repeating: "ab", count: 16), backupStore: store)
        let work = Task { await model.backupSlot(index: 2) }
        await pause.untilEntered()
        model.appBecameInactive(); model.appBecameActive()
        await pause.release(); await work.value
        let listing = try await store.listBackups(); XCTAssertTrue(listing.records.isEmpty)
        let ops = await command.operations(); XCTAssertFalse(ops.contains(0x90cd))
        await model.disconnect()
    }
    private func armSlotRisk(_ store: SlotBackupStore) async throws -> SlotWriteBackup {
        let original = RecipePayload.linearTemplate()
        let proposed = try RecipePayload.edit(template: original, name: "NEXT")
        let backup = SlotWriteBackup(id: UUID(), cameraID: String(repeating: "ab", count: 16),
                                     slot: 2, original: original, proposed: proposed, createdAt: Date())
        try await store.saveAndVerifyBackup(backup)
        try await store.armAndVerifyRisk(backup)
        return backup
    }
    func testReadOnlyRiskReviewNeedsConfirmationAndTwoReads() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SlotBackupStore(directory: folder); let backup = try await armSlotRisk(store)
        let (model, command, pause) = try await makeModel(pausedOp: 0x90cc, pauseOccurrence: 1,
            cameraID: backup.cameraID, backupStore: store)
        let check = Task { await model.inspectSlotRisk() }; await pause.untilEntered(); await pause.release(); await check.value
        XCTAssertEqual(model.slotRecoveryReview?.match, .original)
        let before = try await store.hasPendingRisk(); XCTAssertTrue(before)
        await model.confirmSlotRiskReview()
        let after = try await store.hasPendingRisk(); XCTAssertFalse(after)
        let listing = try await store.listBackups(); XCTAssertEqual(listing.records.count, 1)
        let ops = await command.operations(); XCTAssertEqual(ops.filter { $0 == 0x90cc }.count, 2)
        XCTAssertFalse(ops.contains(0x90cd)); XCTAssertFalse(ops.contains(0x90ce))
        await model.disconnect()
    }
    func testRiskReviewInvalidatedByBackgroundEvenAfterForeground() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SlotBackupStore(directory: folder); let backup = try await armSlotRisk(store)
        let (model, command, pause) = try await makeModel(pausedOp: 0x90cc, pauseOccurrence: 1,
            cameraID: backup.cameraID, backupStore: store)
        let check = Task { await model.inspectSlotRisk() }; await pause.untilEntered(); await pause.release(); await check.value
        XCTAssertNotNil(model.slotRecoveryReview)
        model.appBecameInactive(); model.appBecameActive()
        XCTAssertNil(model.slotRecoveryReview)
        await model.confirmSlotRiskReview()
        let pending = try await store.hasPendingRisk(); XCTAssertTrue(pending)
        let ops = await command.operations(); XCTAssertEqual(ops.filter { $0 == 0x90cc }.count, 1)
        XCTAssertFalse(ops.contains(0x90cd)); await model.disconnect()
    }
    func testRiskReviewChangedSlotNeverClearsMarker() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SlotBackupStore(directory: folder); let backup = try await armSlotRisk(store)
        let (model, command, pause) = try await makeModel(pausedOp: 0x90cc, pauseOccurrence: 1,
            cameraID: backup.cameraID, backupStore: store)
        let check = Task { await model.inspectSlotRisk() }; await pause.untilEntered(); await pause.release(); await check.value
        await command.setSlotPayload(backup.proposed)
        await model.confirmSlotRiskReview()
        let pending = try await store.hasPendingRisk(); XCTAssertTrue(pending)
        let ops = await command.operations(); XCTAssertEqual(ops.filter { $0 == 0x90cc }.count, 2)
        XCTAssertFalse(ops.contains(0x90cd)); await model.disconnect()
    }
    func testBackgroundDuringFinalRiskReadCannotClearMarker() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SlotBackupStore(directory: folder); let backup = try await armSlotRisk(store)
        let (model, command, pause) = try await makeModel(pausedOp: 0x90cc, pauseOccurrence: 2,
            cameraID: backup.cameraID, backupStore: store)
        await model.inspectSlotRisk(); XCTAssertNotNil(model.slotRecoveryReview)
        let finalRead = Task { await model.confirmSlotRiskReview() }
        await pause.untilEntered(); model.appBecameInactive(); model.appBecameActive()
        await pause.release(); await finalRead.value
        let pending = try await store.hasPendingRisk(); XCTAssertTrue(pending)
        let ops = await command.operations(); XCTAssertFalse(ops.contains(0x90cd)); await model.disconnect()
    }
    func testGuardedSlotWriteFullAppFlowWithFakeCameraOnly() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SlotBackupStore(directory: folder)
        let (model, command, pause) = try await makeModel(pausedOp: 0x90cc, pauseOccurrence: 1,
            cameraID: String(repeating: "ab", count: 16), backupStore: store)
        let target = try RecipePayload.edit(template: RecipePayload.linearTemplate(), name: "NEXT")
        let prep = Task { await model.prepareSlotWriteForTesting(slot: 2, candidate: target) }
        await pause.untilEntered()
        await model.refreshBattery() // busy gate must block another operation
        await pause.release(); await prep.value
        XCTAssertNotNil(model.slotWriteTestReview)
        let listing = try await store.listBackups(); XCTAssertEqual(listing.records.count, 1)
        let before = await command.operations(); XCTAssertFalse(before.contains(0x90cd)); XCTAssertFalse(before.contains(0x1015))
        let result = await model.confirmSlotWriteForTesting()
        XCTAssertEqual(result, .verified)
        let pending = try await store.hasPendingRisk(); XCTAssertFalse(pending)
        let ops = await command.operations(); XCTAssertEqual(ops.filter { $0 == 0x90cd }.count, 1)
        XCTAssertFalse(ops.contains(0x90ce)); await model.disconnect()
    }
    func testBackgroundBetweenBackupAndConfirmationBlocksFakeWrite() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SlotBackupStore(directory: folder)
        let (model, command, pause) = try await makeModel(pausedOp: 0x90cc, pauseOccurrence: 1,
            cameraID: String(repeating: "ab", count: 16), backupStore: store)
        let prep = Task { await model.prepareSlotWriteForTesting(slot: 2, candidate: RecipePayload.linearTemplate()) }
        await pause.untilEntered(); await pause.release(); await prep.value
        XCTAssertNotNil(model.slotWriteTestReview)
        model.appBecameInactive(); model.appBecameActive()
        let result = await model.confirmSlotWriteForTesting(); XCTAssertNil(result)
        let ops = await command.operations(); XCTAssertFalse(ops.contains(0x90cd))
        let pending = try await store.hasPendingRisk(); XCTAssertFalse(pending)
        await model.disconnect()
    }
    func testFakeWriteAckLostPersistsRiskAndDoesNotRetry() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SlotBackupStore(directory: folder)
        let (model, command, pause) = try await makeModel(pausedOp: 0x90cc, pauseOccurrence: 1,
            cameraID: String(repeating: "ab", count: 16), backupStore: store)
        let target = try RecipePayload.edit(template: RecipePayload.linearTemplate(), name: "NEXT")
        let prep = Task { await model.prepareSlotWriteForTesting(slot: 2, candidate: target) }
        await pause.untilEntered(); await pause.release(); await prep.value
        await command.loseSlotAcknowledgement()
        let outcome = await model.confirmSlotWriteForTesting(); XCTAssertEqual(outcome, .uncertain)
        let risk = try await SlotBackupStore(directory: folder).hasPendingRisk(); XCTAssertTrue(risk)
        let ops = await command.operations(); XCTAssertEqual(ops.filter { $0 == 0x90cd }.count, 1)
        await model.prepareSlotWriteForTesting(slot: 2, candidate: target)
        XCTAssertNil(model.slotWriteTestReview)
        let after = await command.operations(); XCTAssertEqual(after.filter { $0 == 0x90cd }.count, 1)
        await model.disconnect()
    }
    func testBackgroundDuringFakeWriteKeepsPendingRisk() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SlotBackupStore(directory: folder)
        let (model, command, pause) = try await makeModel(pausedOp: 0x90cd, pauseOccurrence: 1,
            cameraID: String(repeating: "ab", count: 16), backupStore: store)
        let target = try RecipePayload.edit(template: RecipePayload.linearTemplate(), name: "NEXT")
        await model.prepareSlotWriteForTesting(slot: 2, candidate: target)
        XCTAssertNotNil(model.slotWriteTestReview)
        let write = Task { await model.confirmSlotWriteForTesting() }
        await pause.untilEntered(); model.appBecameInactive(); model.appBecameActive()
        await pause.release(); _ = await write.value
        let risk = try await store.hasPendingRisk(); XCTAssertTrue(risk)
        let ops = await command.operations(); XCTAssertEqual(ops.filter { $0 == 0x90cd }.count, 1)
        await model.disconnect()
    }
    func testInactiveDuringHandshakeCannotPublishOldConnection() async throws {
        let pause = PausePoint(), command = FakeAppCommand(pausedOp: 0x1002, pause: pause, pauseOccurrence: 1)
        let model = CameraConnectionModel()
        let config = PtpIpSessionConfig(initiatorGuid: Data(repeating: 1, count: 16), initiatorName: "Test", staMode: true)
        let session = PtpIpSession(commandChannel: command, openEventChannel: { FakeAppEvent() }, config: config, settle: { _ in })
        let connecting = Task { await model.connectForTesting { _ in session } }
        await pause.untilEntered()
        model.appBecameInactive()
        model.appBecameActive() // reactivation must not revive the old attempt
        await pause.release()
        await connecting.value
        XCTAssertEqual(model.phase, .idle)
        let ops = await command.operations()
        XCTAssertFalse(ops.contains(0x1001))
        let closed = await command.closed
        XCTAssertTrue(closed)
    }
}
