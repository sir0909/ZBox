import Foundation
import ZboxCore

/// The real ImageCaptureCore implementation is intentionally not supplied yet.
/// A future driver must bind callbacks to the original token, deliver actual close
/// acknowledgement, and never pretend an unresolved open or failed close is closed.
@MainActor
protocol USBReadOnlyProbeDriver: AnyObject {
    func open(token: UUID, events: @escaping (USBProbeDriverEvent) -> Void)
    func readDeviceInfo(token: UUID, transactionID: UInt32, command: Data,
                        completion: @escaping (Result<USBProbeDriverReply, Error>) -> Void)
    func close(token: UUID)
}
enum USBProbeDriverEvent {
    case opened, openFailed, catalogReady, closed(success: Bool), removed
}
struct USBProbeDriverReply {
    let response: Data
    let data: Data
    /// Must be known by the driver, not inferred from arbitrary photo/device bytes.
    let format: USBDeviceInfoCodec.DataFormat
}

/// Internal testable orchestration; no live driver or production UI is connected.
/// No automatic PTP read, retries, raw-command interface, persistent identity, or logs.
@MainActor
final class USBReadOnlyProbeRunner: ObservableObject {
    @Published private(set) var probe = USBReadOnlyProbe()
    private let driver: USBReadOnlyProbeDriver
    private let timeoutNanoseconds: UInt64
    private var watchdog: Task<Void, Never>?
    private var timeoutIdentity = UUID()

    init(driver: USBReadOnlyProbeDriver, timeoutNanoseconds: UInt64 = 30_000_000_000) {
        self.driver = driver
        self.timeoutNanoseconds = timeoutNanoseconds
    }
    func start() {
        guard let action = probe.start(), let token = probe.token else { return }
        armTimeout(token)
        execute(action)
    }
    func readDeviceInfo() {
        guard let action = probe.readDeviceInfo(), let token = probe.token else { return }
        armTimeout(token)
        execute(action)
    }
    func stop() {
        disarmTimeout()
        execute(probe.stop())
    }
    private func receive(_ event: USBProbeDriverEvent, token: UUID) {
        guard probe.token == token else { return }
        var effect: USBReadOnlyProbe.Effect?
        switch event {
        case .opened: effect = probe.sessionOpened(for: token)
        case .openFailed: probe.openFailed(for: token)
        case .catalogReady: probe.catalogReady(for: token)
        case .closed(let success): effect = probe.sessionClosed(for: token, success: success)
        case .removed: probe.removed(for: token)
        }
        if probe.phase == .ready || probe.phase == .idle || probe.phase == .closing { disarmTimeout() }
        execute(effect)
    }
    private func receive(_ result: Result<USBProbeDriverReply, Error>, token: UUID, tx: UInt32) {
        guard probe.token == token, probe.phase == .reading else { return }
        let effect: USBReadOnlyProbe.Effect?
        switch result {
        case .success(let reply):
            effect = probe.receivedDeviceInfo(for: token, transactionID: tx, response: reply.response,
                                              data: reply.data, format: reply.format)
        case .failure: effect = probe.transportFailed(for: token, transactionID: tx)
        }
        if probe.phase != .reading { disarmTimeout() }
        execute(effect)
    }
    private func execute(_ effect: USBReadOnlyProbe.Effect?) {
        guard let effect else { return }
        switch effect {
        case .openSession(let token):
            driver.open(token: token) { [weak self] event in
                // Break reentrancy and preserve the originating token across late callbacks.
                DispatchQueue.main.async { [weak self] in self?.receive(event, token: token) }
            }
        case .getDeviceInfo(let token, let tx, let command):
            driver.readDeviceInfo(token: token, transactionID: tx, command: command) { [weak self] result in
                DispatchQueue.main.async { [weak self] in self?.receive(result, token: token, tx: tx) }
            }
        case .closeSession(let token): driver.close(token: token)
        }
    }
    private func armTimeout(_ token: UUID) {
        disarmTimeout()
        let identity = UUID(); timeoutIdentity = identity
        let nanos = timeoutNanoseconds
        watchdog = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: nanos) } catch { return }
            guard let self, self.timeoutIdentity == identity, self.probe.token == token else { return }
            self.watchdog = nil
            self.execute(self.probe.timeout(for: token))
        }
    }
    private func disarmTimeout() {
        timeoutIdentity = UUID()
        watchdog?.cancel(); watchdog = nil
    }
}
