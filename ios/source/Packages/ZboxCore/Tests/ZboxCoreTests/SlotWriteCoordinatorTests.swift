import XCTest
@testable import ZboxCore

private actor FakeSlotTransport: SlotWriteTransport {
    var camera = "camera-1"
    var payload = RecipePayload.linearTemplate()
    var writes = 0
    var failWrite = false
    var alterReadback = false
    func cameraID() async throws -> String { camera }
    func read(slot: Int) async throws -> Data { payload }
    func writeOnce(slot: Int, payload: Data, expectedCameraID: String) async throws {
        guard camera == expectedCameraID else { throw SlotWriteCoordinator.Failure.invalidCamera }
        writes += 1
        self.payload = payload
        if alterReadback { self.payload[0x50] ^= 1 }
        if failWrite { throw TestError.interrupted }
    }
    func setPayload(_ value: Data) { payload = value }
    func configureFailure() { failWrite = true }
    func configureMismatch() { alterReadback = true }
    enum TestError: Error { case interrupted }
}
private actor FakeSlotJournal: SlotWriteJournal {
    var pending = false
    var backups: [SlotWriteBackup] = []
    var failSave = false
    var failArm = false
    func hasPendingRisk() async throws -> Bool { pending }
    func saveAndVerifyBackup(_ backup: SlotWriteBackup) async throws {
        if failSave { throw TestError.failed }; backups.append(backup)
    }
    func armAndVerifyRisk(_ backup: SlotWriteBackup) async throws {
        if failArm { throw TestError.failed }; pending = true
    }
    func clearVerifiedRisk(_ backup: SlotWriteBackup) async throws { pending = false }
    func configureSaveFailure() { failSave = true }
    func configureArmFailure() { failArm = true }
    enum TestError: Error { case failed }
}
final class SlotWriteCoordinatorTests: XCTestCase {
    private func candidate() throws -> Data {
        try RecipePayload.edit(template: RecipePayload.linearTemplate(), name: "NEXT", lut: (0...255).map { UInt8($0 / 2) })
    }
    func testBackupConfirmationAndReadback() async throws {
        let t = FakeSlotTransport(), j = FakeSlotJournal()
        let initial = await t.payload
        let coordinator = SlotWriteCoordinator(transport: t, journal: j)
        let confirm = try await coordinator.prepare(cameraID: "camera-1", slot: 2, candidate: candidate())
        XCTAssertEqual(confirm.slot, 2); XCTAssertEqual(confirm.newName, "NEXT")
        let backups = await j.backups; XCTAssertEqual(backups.count, 1); XCTAssertEqual(backups[0].original, initial)
        let writesBefore = await t.writes; XCTAssertEqual(writesBefore, 0)
        let result = try await coordinator.confirm(confirm.id)
        XCTAssertEqual(result, .verified)
        let writesAfter = await t.writes, pending = await j.pending
        XCTAssertEqual(writesAfter, 1); XCTAssertFalse(pending)
        do { _ = try await coordinator.confirm(confirm.id); XCTFail("confirmation reused") } catch {}
    }
    func testChangedSlotStopsBeforeRiskAndWrite() async throws {
        let t = FakeSlotTransport(), j = FakeSlotJournal()
        let c = SlotWriteCoordinator(transport: t, journal: j)
        let confirmation = try await c.prepare(cameraID: "camera-1", slot: 1, candidate: candidate())
        var changed = await t.payload; changed[0x50] ^= 1; await t.setPayload(changed)
        do { _ = try await c.confirm(confirmation.id); XCTFail("changed slot accepted") }
        catch { XCTAssertEqual(error as? SlotWriteCoordinator.Failure, .slotChanged) }
        let count = await t.writes, pending = await j.pending
        XCTAssertEqual(count, 0); XCTAssertFalse(pending)
    }
    func testLostAcknowledgementAndReadbackMismatchKeepRiskAndNeverRetry() async throws {
        for mismatch in [false, true] {
            let t = FakeSlotTransport(), j = FakeSlotJournal(), c = SlotWriteCoordinator(transport: t, journal: j)
            if mismatch { await t.configureMismatch() } else { await t.configureFailure() }
            let confirmation = try await c.prepare(cameraID: "camera-1", slot: 1, candidate: candidate())
            let result = try await c.confirm(confirmation.id)
            let count = await t.writes, pending = await j.pending
            XCTAssertEqual(result, .uncertain); XCTAssertEqual(count, 1); XCTAssertTrue(pending)
            do { _ = try await c.prepare(cameraID: "camera-1", slot: 1, candidate: candidate()); XCTFail("pending risk accepted") } catch {}
        }
    }
    func testTargetPreservesUnknownCameraBytesAndCancelDoesNotWrite() async throws {
        let t = FakeSlotTransport(), j = FakeSlotJournal(), c = SlotWriteCoordinator(transport: t, journal: j)
        var original = await t.payload; original[0x50] = 0xab; await t.setPayload(original)
        var proposed = try candidate(); proposed[0x50] = 0x19
        let confirmation = try await c.prepare(cameraID: "camera-1", slot: 3, candidate: proposed)
        let backups = await j.backups
        XCTAssertEqual(backups[0].proposed[0x50], 0xab)
        XCTAssertEqual(backups[0].original, original)
        await c.cancel()
        do { _ = try await c.confirm(confirmation.id); XCTFail("cancelled confirmation accepted") } catch {}
        let writes = await t.writes, pending = await j.pending
        XCTAssertEqual(writes, 0); XCTAssertFalse(pending)
    }
    func testWrongCameraAndEmptySlotNeverWrite() async throws {
        let t = FakeSlotTransport(), j = FakeSlotJournal(), c = SlotWriteCoordinator(transport: t, journal: j)
        do { _ = try await c.prepare(cameraID: "other", slot: 1, candidate: candidate()); XCTFail("wrong camera") } catch {}
        await t.setPayload(Data(repeating: 0, count: 36))
        do { _ = try await c.prepare(cameraID: "camera-1", slot: 1, candidate: candidate()); XCTFail("empty slot") } catch {}
        let writes = await t.writes; XCTAssertEqual(writes, 0)
        let backups = await j.backups; XCTAssertTrue(backups.isEmpty)
    }
    func testPersistenceFailuresNeverSend() async throws {
        for arm in [false, true] {
            let t = FakeSlotTransport(), j = FakeSlotJournal(), c = SlotWriteCoordinator(transport: t, journal: j)
            if arm { await j.configureArmFailure() } else { await j.configureSaveFailure() }
            do {
                let confirmation = try await c.prepare(cameraID: "camera-1", slot: 1, candidate: candidate())
                _ = try await c.confirm(confirmation.id)
                XCTFail("persistence failure accepted")
            } catch {}
            let writes = await t.writes; XCTAssertEqual(writes, 0)
        }
    }
}
