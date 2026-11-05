import XCTest
@testable import ZboxCore

private actor RecoveryTransport: SlotWriteTransport {
    var identity = "camera-1"
    var data: Data
    var writes = 0
    init(_ data: Data) { self.data = data }
    func cameraID() async throws -> String { identity }
    func read(slot: Int) async throws -> Data { data }
    func writeOnce(slot: Int, payload: Data, expectedCameraID: String, expectedOriginal: Data) async throws { writes += 1 }
    func setData(_ value: Data) { data = value }
    func setIdentity(_ value: String) { identity = value }
}
private actor RecoveryJournal: SlotWriteRecoveryJournal {
    var pending: SlotWriteBackup?
    init(_ pending: SlotWriteBackup?) { self.pending = pending }
    func hasPendingRisk() async throws -> Bool { pending != nil }
    func readPendingRisk() async throws -> SlotWriteBackup? { pending }
    func saveAndVerifyBackup(_ backup: SlotWriteBackup) async throws {}
    func armAndVerifyRisk(_ backup: SlotWriteBackup) async throws {}
    func clearVerifiedRisk(_ backup: SlotWriteBackup) async throws {
        guard pending == backup else { throw SlotWriteRecovery.Failure.changed }; pending = nil
    }
}
final class SlotWriteRecoveryTests: XCTestCase {
    private func makeBackup() throws -> SlotWriteBackup {
        let original = RecipePayload.linearTemplate()
        let proposed = try RecipePayload.edit(template: original, name: "NEXT")
        return SlotWriteBackup(id: UUID(), cameraID: "camera-1", slot: 3, original: original, proposed: proposed, createdAt: Date())
    }
    func testBothRecognizedStatesNeedExplicitConfirmationAndRecheck() async throws {
        for proposed in [false, true] {
            let backup = try makeBackup(), journal = RecoveryJournal(backup)
            let transport = RecoveryTransport(proposed ? backup.proposed : backup.original)
            let recovery = SlotWriteRecovery(transport: transport, journal: journal)
            let review = try await recovery.inspect()
            XCTAssertEqual(review.match, proposed ? .proposed : .original)
            let stillPending = await journal.pending; XCTAssertNotNil(stillPending)
            let result = try await recovery.confirm(review.id)
            XCTAssertEqual(result, review.match)
            let pending = await journal.pending, writes = await transport.writes
            XCTAssertNil(pending); XCTAssertEqual(writes, 0)
            do { _ = try await recovery.confirm(review.id); XCTFail("reused confirmation") } catch {}
        }
    }
    func testChangedOrUnknownSlotNeverClears() async throws {
        let backup = try makeBackup(), journal = RecoveryJournal(backup), transport = RecoveryTransport(backup.original)
        let recovery = SlotWriteRecovery(transport: transport, journal: journal)
        let review = try await recovery.inspect()
        await transport.setData(backup.proposed)
        do { _ = try await recovery.confirm(review.id); XCTFail("changed value accepted") }
        catch { XCTAssertEqual(error as? SlotWriteRecovery.Failure, .changed) }
        var unknown = backup.original; unknown[0x50] ^= 1; await transport.setData(unknown)
        do { _ = try await recovery.inspect(); XCTFail("unknown value accepted") }
        catch { XCTAssertEqual(error as? SlotWriteRecovery.Failure, .unrecognizedContents) }
        let pending = await journal.pending, writes = await transport.writes
        XCTAssertNotNil(pending); XCTAssertEqual(writes, 0)
    }
    func testWrongCameraAndCancellationKeepRisk() async throws {
        let backup = try makeBackup(), journal = RecoveryJournal(backup), transport = RecoveryTransport(backup.original)
        let recovery = SlotWriteRecovery(transport: transport, journal: journal)
        await transport.setIdentity("other")
        do { _ = try await recovery.inspect(); XCTFail("wrong camera accepted") }
        catch { XCTAssertEqual(error as? SlotWriteRecovery.Failure, .wrongCamera) }
        await transport.setIdentity("camera-1")
        let review = try await recovery.inspect(); await recovery.cancel()
        do { _ = try await recovery.confirm(review.id); XCTFail("cancelled confirmation accepted") } catch {}
        let pending = await journal.pending; XCTAssertNotNil(pending)
    }
}
