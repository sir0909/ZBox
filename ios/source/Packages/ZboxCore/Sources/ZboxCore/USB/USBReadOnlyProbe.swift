import Foundation

/// Pure, fail-closed lifecycle for a future USB GetDeviceInfo adapter. It performs no
/// I/O. An adapter must serialize calls, bind each callback to its original token, and
/// provide genuine open/close/remove acknowledgements. Never equate a timeout with closure.
public struct USBReadOnlyProbe: Sendable {
    public enum Phase: Equatable, Sendable { case idle, opening, awaitingCatalog, ready, reading, closing }
    public enum Effect: Equatable, Sendable {
        case openSession(UUID)
        case getDeviceInfo(UUID, transactionID: UInt32, command: Data)
        case closeSession(UUID)
    }
    public enum Failure: Equatable, Sendable { case openFailed, transport, malformedReply, timeout }
    public private(set) var phase: Phase = .idle
    public private(set) var token: UUID?
    public private(set) var lastFailure: Failure?
    /// Transient data only; may contain a serial number. Callers must not log it.
    public private(set) var deviceInfo: PtpDeviceInfo?
    private var opened = false
    private var catalogSeen = false
    private var closeRequested = false
    private var pendingTransaction: UInt32?
    private var nextTransaction: UInt32 = 1

    public init() {}

    /// User-initiated only. A pending open or unacknowledged close blocks new sessions.
    public mutating func start() -> Effect? {
        guard phase == .idle, token == nil else { return nil }
        let current = UUID()
        token = current; phase = .opening; lastFailure = nil; deviceInfo = nil
        opened = false; catalogSeen = false; closeRequested = false
        pendingTransaction = nil; nextTransaction = 1
        return .openSession(current)
    }

    /// Accept readiness in either order, but require *both* signals before any command.
    public mutating func catalogReady(for current: UUID) {
        guard token == current, phase == .opening || phase == .awaitingCatalog else { return }
        catalogSeen = true
        if opened { phase = .ready }
    }
    public mutating func sessionOpened(for current: UUID) -> Effect? {
        guard token == current, !opened else { return nil }
        guard phase == .opening || (phase == .closing && !closeRequested) else { return nil }
        opened = true
        if phase == .closing {
            closeRequested = true
            return .closeSession(current)
        }
        phase = catalogSeen ? .ready : .awaitingCatalog
        return nil
    }
    /// Only a definite failed open callback proves no session was opened.
    public mutating func openFailed(for current: UUID) {
        guard token == current, !opened, phase == .opening || phase == .closing else { return }
        lastFailure = .openFailed
        resetToIdle()
    }

    /// Explicit user action only, never automatic after opening or after a failed request.
    /// The only command this type can construct is the standard read-only GetDeviceInfo.
    public mutating func readDeviceInfo() -> Effect? {
        guard phase == .ready, let current = token, nextTransaction < UInt32.max else { return nil }
        let tx = nextTransaction; nextTransaction += 1
        pendingTransaction = tx; phase = .reading; deviceInfo = nil
        return .getDeviceInfo(current, transactionID: tx,
                              command: PtpContainer.command(code: PtpOpCode.getDeviceInfo, transactionId: tx))
    }
    public mutating func receivedDeviceInfo(for current: UUID, transactionID: UInt32,
                                            response: Data, data: Data,
                                            format: USBDeviceInfoCodec.DataFormat) -> Effect? {
        guard token == current, phase == .reading, pendingTransaction == transactionID else { return nil }
        do {
            deviceInfo = try USBDeviceInfoCodec.decode(response: response, data: data, format: format,
                                                       transactionID: transactionID)
            pendingTransaction = nil; phase = .ready
            return nil
        } catch {
            lastFailure = .malformedReply
            return beginClose()
        }
    }
    /// No transport error or timeout is retried; an opened session is closed once.
    public mutating func transportFailed(for current: UUID, transactionID: UInt32) -> Effect? {
        guard token == current, phase == .reading, pendingTransaction == transactionID else { return nil }
        lastFailure = .transport
        return beginClose()
    }
    public mutating func timeout(for current: UUID) -> Effect? {
        guard token == current, phase == .opening || phase == .awaitingCatalog || phase == .reading else { return nil }
        lastFailure = .timeout
        return beginClose()
    }
    /// If opening is still in flight, wait for its definitive callback before requesting
    /// close. If that callback never comes, stay closing; do not start another session.
    public mutating func stop() -> Effect? {
        guard phase != .idle && phase != .closing else { return nil }
        return beginClose()
    }
    private mutating func beginClose() -> Effect? {
        phase = .closing; pendingTransaction = nil; deviceInfo = nil
        guard opened, !closeRequested, let current = token else { return nil }
        closeRequested = true
        return .closeSession(current)
    }
    /// Ignore stray close callbacks while an open is still unresolved. An unsolicited
    /// successful close after opening invalidates the session. A close error is never
    /// proof of closure; confirmed removal can retire an otherwise stuck session.
    @discardableResult
    public mutating func sessionClosed(for current: UUID, success: Bool) -> Effect? {
        guard token == current, opened else { return nil }
        if success {
            guard phase != .closing || closeRequested else { return nil }
            resetToIdle()
            return nil
        }
        lastFailure = .transport
        return beginClose()
    }
    public mutating func removed(for current: UUID) {
        guard token == current else { return }
        resetToIdle()
    }
    private mutating func resetToIdle() {
        phase = .idle; token = nil; opened = false; catalogSeen = false
        closeRequested = false; pendingTransaction = nil; deviceInfo = nil
    }
}
