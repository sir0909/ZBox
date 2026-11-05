import XCTest
import ZboxCore
@testable import Zbox

final class SlotBackupStoreTests: XCTestCase {
    func testListingRejectsCorruptBackupWithoutDeletingIt() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SlotBackupStore(directory: folder), payload = RecipePayload.linearTemplate()
        let good = SlotWriteBackup(id: UUID(), cameraID: "camera-1", slot: 1, original: payload, proposed: payload, createdAt: Date())
        let bad = SlotWriteBackup(id: UUID(), cameraID: "camera-1", slot: 2, original: payload, proposed: payload, createdAt: Date())
        try await store.saveAndVerifyBackup(good); try await store.saveAndVerifyBackup(bad)
        let badURL = folder.appendingPathComponent("backup-\(bad.id.uuidString).json")
        let damaged = Data("damaged".utf8); try damaged.write(to: badURL)
        let listing = try await store.listBackups()
        XCTAssertEqual(listing.records.map(\.id), [good.id]); XCTAssertEqual(listing.corruptCount, 1)
        XCTAssertEqual(try Data(contentsOf: badURL), damaged)
        XCTAssertFalse(listing.riskExists)
    }
    func testReadPendingRequiresIntactBackupAndKeepsCorruption() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SlotBackupStore(directory: folder), payload = RecipePayload.linearTemplate()
        let backup = SlotWriteBackup(id: UUID(), cameraID: "camera-1", slot: 1, original: payload, proposed: payload, createdAt: Date())
        try await store.saveAndVerifyBackup(backup); try await store.armAndVerifyRisk(backup)
        let pending = try await store.readPendingRisk(); XCTAssertEqual(pending, backup)
        let backupURL = folder.appendingPathComponent("backup-\(backup.id.uuidString).json")
        try Data("bad".utf8).write(to: backupURL)
        do { _ = try await store.readPendingRisk(); XCTFail("damaged backup accepted") } catch {}
        let stillRisk = try await store.hasPendingRisk(); XCTAssertTrue(stillRisk)
    }
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
