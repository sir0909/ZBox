import Foundation

/// Read-only recovery of a pending attempt. Does not write or restore a camera slot.
public protocol SlotWriteRecoveryJournal: SlotWriteJournal {
    /// Throws for a corrupt/missing backup associated with a present risk file.
    func readPendingRisk() async throws -> SlotWriteBackup?
}
public struct SlotRecoveryConfirmation: Sendable {
    public enum Match: Equatable, Sendable { case original, proposed }
    public let id: UUID
    public let cameraID: String
    public let slot: Int
    public let match: Match
}

/// Prototype only. A production caller must hold an app-wide camera operation gate,
/// show the proposed decision to the user and invalidate attempts on lifecycle change.
public actor SlotWriteRecovery {
    public enum Failure: Error, Equatable { case busy, noRisk, wrongCamera, unrecognizedContents, changed, staleConfirmation }
    private struct Prepared {
        let token: UUID
        let backup: SlotWriteBackup
        let observed: Data
        let match: SlotRecoveryConfirmation.Match
    }
    private enum Stage { case idle, checking, ready(Prepared), confirming }
    private var stage: Stage = .idle
    private let transport: any SlotWriteTransport
    private let journal: any SlotWriteRecoveryJournal
    public init(transport: any SlotWriteTransport, journal: any SlotWriteRecoveryJournal) {
        self.transport = transport; self.journal = journal
    }
    /// A byte-identical match shows current contents, not the past history of a command.
    public func inspect() async throws -> SlotRecoveryConfirmation {
        guard case .idle = stage else { throw Failure.busy }
        stage = .checking
        do {
            guard let backup = try await journal.readPendingRisk() else { throw Failure.noRisk }
            guard (1...9).contains(backup.slot), !backup.cameraID.isEmpty else { throw Failure.unrecognizedContents }
            _ = try PictureControl614.parse(backup.original); _ = try PictureControl614.parse(backup.proposed)
            guard try await transport.cameraID() == backup.cameraID else { throw Failure.wrongCamera }
            let current = try await transport.read(slot: backup.slot)
            guard try await transport.cameraID() == backup.cameraID else { throw Failure.wrongCamera }
            let match: SlotRecoveryConfirmation.Match
            if current == backup.proposed { match = .proposed }
            else if current == backup.original { match = .original }
            else { throw Failure.unrecognizedContents }
            try Task.checkCancellation()
            let prepared = Prepared(token: UUID(), backup: backup, observed: current, match: match)
            stage = .ready(prepared)
            return SlotRecoveryConfirmation(id: prepared.token, cameraID: backup.cameraID, slot: backup.slot, match: match)
        } catch { stage = .idle; throw error }
    }
    public func cancel() { if case .ready = stage { stage = .idle } }
    /// Only after explicit confirmation, re-read and match all bytes again. No retry or write.
    public func confirm(_ id: UUID) async throws -> SlotRecoveryConfirmation.Match {
        guard case .ready(let prepared) = stage, prepared.token == id else { throw Failure.staleConfirmation }
        stage = .confirming
        defer { stage = .idle }
        try Task.checkCancellation()
        guard try await journal.readPendingRisk() == prepared.backup else { throw Failure.changed }
        guard try await transport.cameraID() == prepared.backup.cameraID else { throw Failure.wrongCamera }
        guard try await transport.read(slot: prepared.backup.slot) == prepared.observed else { throw Failure.changed }
        guard try await transport.cameraID() == prepared.backup.cameraID else { throw Failure.wrongCamera }
        try Task.checkCancellation()
        try await journal.clearVerifiedRisk(prepared.backup)
        return prepared.match
    }
}
