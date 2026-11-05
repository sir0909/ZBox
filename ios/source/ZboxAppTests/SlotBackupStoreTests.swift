import XCTest
import ZboxCore
@testable import Zbox

final class SlotBackupStoreTests: XCTestCase {
    func testBackupRiskAndCorruptionFailClosed() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SlotBackupStore(directory: folder)
        let payload = RecipePayload.linearTemplate()
        let backup = SlotWriteBackup(id: UUID(), cameraID: "camera-1", slot: 1, original: payload, proposed: payload, createdAt: Date())
        try await store.saveAndVerifyBackup(backup)
        try await store.armAndVerifyRisk(backup)
        let risk = try await store.hasPendingRisk(); XCTAssertTrue(risk)
        let riskURL = folder.appendingPathComponent("pending-risk.json")
        try Data("corrupted".utf8).write(to: riskURL)
        do { try await store.clearVerifiedRisk(backup); XCTFail("corruption accepted") } catch {}
        let stillRisk = try await store.hasPendingRisk(); XCTAssertTrue(stillRisk)
        do { try await store.armAndVerifyRisk(backup); XCTFail("pending overwritten") } catch {}
        XCTAssertTrue(FileManager.default.fileExists(atPath: folder.appendingPathComponent("backup-\(backup.id.uuidString).json").path))
    }
}
