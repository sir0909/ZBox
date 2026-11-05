import Foundation

/// Pure state gate for a future ImageCaptureCore transport. It does not enumerate USB
/// devices or issue PTP commands. Platform behavior must be verified on real iOS hardware.
public struct USBCameraSessionGate: Sendable, Equatable {
    public enum Phase: Sendable, Equatable { case detached, opening, awaitingCatalog, ready }
    public private(set) var phase: Phase = .detached
    public private(set) var connectionID: UUID?
    public var canSendPTP: Bool { phase == .ready && connectionID != nil }

    public init() {}

    /// Replacing a device invalidates all callbacks from earlier generations.
    @discardableResult
    public mutating func beginConnection() -> UUID {
        let id = UUID()
        connectionID = id
        phase = .opening
        return id
    }

    /// An open-session callback alone must not permit PTP commands.
    public mutating func sessionOpened(for id: UUID) {
        guard id == connectionID, phase == .opening else { return }
        phase = .awaitingCatalog
    }

    /// Call only after the complete-content-catalog readiness signal is confirmed.
    public mutating func catalogReady(for id: UUID) {
        guard id == connectionID, phase == .awaitingCatalog else { return }
        phase = .ready
    }

    public mutating func detach(for id: UUID) {
        guard id == connectionID else { return }
        connectionID = nil
        phase = .detached
    }
}
