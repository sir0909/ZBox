import Foundation
import CryptoKit
import Darwin
import ZboxCore

/// App-local journal prototype. Do not connect to camera writes until its process-wide
/// exclusivity, crash behavior, recovery UI and PTP lifecycle gates are verified.
actor SlotBackupStore: SlotWriteJournal {
    enum Failure: Error { case invalid, pending, missing, tooMany }
    private struct Envelope: Codable {
        let record: Data
        let sha256: String
    }
    private let directory: URL
    private var riskURL: URL { directory.appendingPathComponent("pending-risk.json") }
    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Zbox", isDirectory: true).appendingPathComponent("slot-backups", isDirectory: true)
    }
    private func ensureDirectory() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    private func backupURL(_ id: UUID) -> URL {
        directory.appendingPathComponent("backup-\(id.uuidString).json")
    }
    private func encode(_ backup: SlotWriteBackup) throws -> Data {
        guard (1...9).contains(backup.slot), !backup.cameraID.isEmpty, backup.cameraID.utf8.count <= 128 else { throw Failure.invalid }
        _ = try PictureControl614.parse(backup.original); _ = try PictureControl614.parse(backup.proposed)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let record = try encoder.encode(backup)
        let digest = SHA256.hash(data: record).map { String(format: "%02x", $0) }.joined()
        return try encoder.encode(Envelope(record: record, sha256: digest))
    }
    private func read(_ url: URL) throws -> SlotWriteBackup {
        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        guard let size = attrs[.size] as? NSNumber, size.intValue > 0, size.intValue <= 16_384 else { throw Failure.invalid }
        let data = try Data(contentsOf: url)
        let envelope = try JSONDecoder().decode(Envelope.self, from: data)
        let digest = SHA256.hash(data: envelope.record).map { String(format: "%02x", $0) }.joined()
        guard digest == envelope.sha256 else { throw Failure.invalid }
        return try JSONDecoder().decode(SlotWriteBackup.self, from: envelope.record)
    }
    /// O_EXCL guarantees another store instance cannot replace an existing record.
    /// A failed partial write is deliberately left in place to fail closed.
    private func createExclusive(_ data: Data, at url: URL) throws {
        let descriptor = open(url.path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, mode_t(S_IRUSR | S_IWUSR))
        guard descriptor >= 0 else { throw Failure.pending }
        let handle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
        defer { try? handle.close() }
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUnlessOpen], ofItemAtPath: url.path)
        try handle.write(contentsOf: data)
        try handle.synchronize()
    }
    func hasPendingRisk() async throws -> Bool {
        try ensureDirectory()
        // A corrupt or unreadable risk file is still a risk; never silently remove it.
        return FileManager.default.fileExists(atPath: riskURL.path)
    }
    func saveAndVerifyBackup(_ backup: SlotWriteBackup) async throws {
        try ensureDirectory()
        let files = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        guard files.filter({ $0.hasPrefix("backup-") && $0.hasSuffix(".json") }).count < 10_000 else { throw Failure.tooMany }
        let url = backupURL(backup.id)
        guard !FileManager.default.fileExists(atPath: url.path) else { throw Failure.invalid }
        try createExclusive(encode(backup), at: url)
        guard try read(url) == backup else { throw Failure.invalid }
    }
    func armAndVerifyRisk(_ backup: SlotWriteBackup) async throws {
        try ensureDirectory()
        guard !FileManager.default.fileExists(atPath: riskURL.path) else { throw Failure.pending }
        guard try read(backupURL(backup.id)) == backup else { throw Failure.missing }
        try createExclusive(encode(backup), at: riskURL)
        guard try read(riskURL) == backup else { throw Failure.invalid }
    }
    func clearVerifiedRisk(_ backup: SlotWriteBackup) async throws {
        guard try read(riskURL) == backup else { throw Failure.invalid }
        try FileManager.default.removeItem(at: riskURL)
    }
}
