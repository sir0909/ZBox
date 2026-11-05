import Foundation

/// Prototype only: not connected to PTP, UI or an actual camera. Implementations of
/// transport and journal must enforce their contracts before any production use.
public protocol SlotWriteTransport: Sendable {
    /// Session-reported camera identity, stable throughout a transaction; PTP GUID is not authentication.
    func cameraID() async throws -> String
    /// Exact raw 614-byte occupied custom slot. Do not normalize arbitrary wrappers.
    func read(slot: Int) async throws -> Data
    /// Under the wire lock: re-check session identity, foreground and operation gates,
    /// then re-read and compare expectedOriginal before sending. Never retry. A thrown
    /// error may mean the command already happened. Physical camera edits can still race.
    func writeOnce(slot: Int, payload: Data, expectedCameraID: String, expectedOriginal: Data) async throws
}

public struct SlotWriteBackup: Codable, Equatable, Sendable {
    public let id: UUID
    public let cameraID: String
    public let slot: Int
    public let original: Data
    public let proposed: Data
    public let createdAt: Date
    public init(id: UUID, cameraID: String, slot: Int, original: Data, proposed: Data, createdAt: Date) {
        self.id = id; self.cameraID = cameraID; self.slot = slot
        self.original = original; self.proposed = proposed; self.createdAt = createdAt
    }
}

/// These methods must be durable and independently read-back-verified in the real app.
/// An armed risk survives termination and blocks all future writes until explicit recovery.
public protocol SlotWriteJournal: Sendable {
    func hasPendingRisk() async throws -> Bool
    func saveAndVerifyBackup(_ backup: SlotWriteBackup) async throws
    /// Atomically reject an existing risk; never replace it.
    func armAndVerifyRisk(_ backup: SlotWriteBackup) async throws
    func clearVerifiedRisk(_ backup: SlotWriteBackup) async throws
}

public struct SlotWriteConfirmation: Sendable {
    public let id: UUID
    public let cameraID: String
    public let slot: Int
    public let oldName: String
    public let newName: String
}

public actor SlotWriteCoordinator {
    public enum Failure: Swift.Error, Equatable {
        case busy, pendingRisk, invalidCamera, invalidSlot, invalidPayload, staleConfirmation, slotChanged, readbackMismatch
    }
    public enum Outcome: Equatable, Sendable {
        case verified
        /// A write might have been sent; risk must remain and there is no automatic retry.
        case uncertain
    }
    private enum Stage { case idle, preparing, ready(SlotWriteBackup), committing }
    private var stage: Stage = .idle
    private let transport: any SlotWriteTransport
    private let journal: any SlotWriteJournal

    public init(transport: any SlotWriteTransport, journal: any SlotWriteJournal) {
        self.transport = transport; self.journal = journal
    }

    /// Backup occurs BEFORE the user is asked to confirm. Empty slots are refused:
    /// restoring an empty slot would require a delete operation which is not implemented.
    public func prepare(cameraID: String, slot: Int, candidate: Data) async throws -> SlotWriteConfirmation {
        guard case .idle = stage else { throw Failure.busy }
        guard !cameraID.isEmpty, cameraID.utf8.count <= 128 else { throw Failure.invalidCamera }
        guard (1...9).contains(slot) else { throw Failure.invalidSlot }
        let proposed = try PictureControl614.parse(candidate)
        guard proposed.curveIsNondecreasing else { throw Failure.invalidPayload }
        stage = .preparing
        do {
            guard try await !journal.hasPendingRisk() else { throw Failure.pendingRisk }
            guard try await transport.cameraID() == cameraID else { throw Failure.invalidCamera }
            let original = try await transport.read(slot: slot)
            let old = try PictureControl614.parse(original)
            // Unknown bytes and all parameters come from the actual camera slot, never
            // from an offline synthetic template. Only name, points and LUT are copied.
            var target = try RecipePayload.edit(template: original, name: proposed.name)
            let bytes = [UInt8](candidate)
            target.replaceSubrange(0x2f..<0x37, with: bytes[0x2f..<0x37])
            target.replaceSubrange(0x66..<0x266, with: bytes[0x66..<0x266])
            _ = try PictureControl614.parse(target)
            guard try await transport.cameraID() == cameraID else { throw Failure.invalidCamera }
            let backup = SlotWriteBackup(id: UUID(), cameraID: cameraID, slot: slot,
                                         original: original, proposed: target, createdAt: Date())
            try await journal.saveAndVerifyBackup(backup)
            try Task.checkCancellation()
            stage = .ready(backup)
            return SlotWriteConfirmation(id: backup.id, cameraID: cameraID, slot: slot,
                                         oldName: old.name, newName: proposed.name)
        } catch { stage = .idle; throw error }
    }

    public func cancel() {
        if case .ready = stage { stage = .idle }
    }

    /// Caller must show the confirmation details and pass the one-time id only after
    /// explicit user approval. Pending risk is armed before write, including on cancellation.
    public func confirm(_ id: UUID) async throws -> Outcome {
        guard case .ready(let backup) = stage, id == backup.id else { throw Failure.staleConfirmation }
        stage = .committing
        defer { stage = .idle }
        guard try await !journal.hasPendingRisk() else { throw Failure.pendingRisk }
        try Task.checkCancellation()
        guard try await transport.cameraID() == backup.cameraID else { throw Failure.invalidCamera }
        guard try await transport.read(slot: backup.slot) == backup.original else { throw Failure.slotChanged }
        guard try await transport.cameraID() == backup.cameraID else { throw Failure.invalidCamera }
        try Task.checkCancellation()
        // If risk persistence throws, no command is sent. The store must fail closed.
        try await journal.armAndVerifyRisk(backup)
        do {
            try Task.checkCancellation()
            try await transport.writeOnce(slot: backup.slot, payload: backup.proposed,
                                          expectedCameraID: backup.cameraID, expectedOriginal: backup.original)
            guard try await transport.cameraID() == backup.cameraID else { return .uncertain }
            guard try await transport.read(slot: backup.slot) == backup.proposed else { return .uncertain }
            try await journal.clearVerifiedRisk(backup)
            return .verified
        } catch { return .uncertain }
    }
}
