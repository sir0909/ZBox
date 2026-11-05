import Foundation

/// Synchronous admission policy; all check-and-mark operations must run on one actor.
/// The caller still owns flags, cancellation, command sequencing and connection identity.
public struct CameraOperationPolicy: Sendable {
    public enum Operation: CaseIterable, Hashable, Sendable {
        case battery, monitor, slots, exposure, album, capture, focus, registration, liveView
    }
    public enum LiveView: Sendable { case idle, streaming, transitioning }
    public enum Request: CaseIterable, Sendable {
        case battery, monitor, slots, exposure, album, capture, focus, prepareRegistration, confirmRegistration, liveView
    }

    public let connected: Bool
    public let pairingPrepared: Bool
    public let busy: Set<Operation>
    public let liveView: LiveView

    public init(connected: Bool, pairingPrepared: Bool, busy: Set<Operation>, liveView: LiveView) {
        self.connected = connected
        self.pairingPrepared = pairingPrepared
        self.busy = busy
        self.liveView = liveView
    }

    public func permits(_ request: Request) -> Bool {
        guard connected, busy.isEmpty else { return false }
        if request == .confirmRegistration {
            guard pairingPrepared else { return false }
        } else if pairingPrepared { return false }
        switch liveView {
        case .idle: return request != .focus
        case .streaming: return request == .capture || request == .focus
        case .transitioning: return false
        }
    }
}
