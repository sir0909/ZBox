import Foundation
import ImageCaptureCore

/// Ephemeral UI-only identity: never treated as a camera serial number or persisted.
struct USBDiscoveryCandidate: Identifiable, Equatable {
    let id: UUID
    let name: String
}

enum USBDiscoveryEvent {
    case added(UUID, String?)
    case removed(UUID)
}

/// A browser driver does discovery only. It has no session, PTP, or media methods.
protocol USBDiscoveryDriver: AnyObject {
    func start(deliver: @escaping (USBDiscoveryEvent) -> Void)
    func stop()
}

@MainActor
final class USBDiscoveryStore: ObservableObject {
    @Published private(set) var scanning = false
    @Published private(set) var candidates: [USBDiscoveryCandidate] = []
    private let driver: USBDiscoveryDriver
    private var generation: UUID?
    private let maximumCandidates = 64

    init(driver: USBDiscoveryDriver = ImageCaptureDiscoveryDriver()) { self.driver = driver }

    func start() {
        guard !scanning else { return }
        let token = UUID()
        generation = token
        candidates = []
        scanning = true
        driver.start { [weak self] event in
            // Defer to the main queue, preventing synchronous callbacks from reentering
            // start and rejecting queued events from an earlier scan after stop/restart.
            DispatchQueue.main.async { [weak self] in self?.accept(event, generation: token) }
        }
    }
    func stop() {
        guard scanning else { return }
        generation = nil
        scanning = false
        candidates = []
        driver.stop()
    }
    private func accept(_ event: USBDiscoveryEvent, generation token: UUID) {
        guard scanning, generation == token else { return }
        switch event {
        case .added(let id, let name):
            let clean = Self.displayName(name)
            if let index = candidates.firstIndex(where: { $0.id == id }) {
                candidates[index] = USBDiscoveryCandidate(id: id, name: clean)
            } else if candidates.count < maximumCandidates {
                candidates.append(USBDiscoveryCandidate(id: id, name: clean))
            }
        case .removed(let id): candidates.removeAll { $0.id == id }
        }
    }
    private static func displayName(_ raw: String?) -> String {
        let text = (raw ?? "").unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }
        let trimmed = String(String.UnicodeScalarView(text)).trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "未命名设备" : String(trimmed.prefix(80))
    }
}

/// ImageCaptureCore browser only. Delegate must be retained while scanning.
/// Created and started on the main run loop; no session or device command is issued.
final class ImageCaptureDiscoveryDriver: USBDiscoveryDriver {
    private var browser: ICDeviceBrowser?
    private var delegate: BrowserDelegate?
    func start(deliver: @escaping (USBDiscoveryEvent) -> Void) {
        guard browser == nil else { return }
        let browser = ICDeviceBrowser()
        let delegate = BrowserDelegate(deliver: deliver)
        browser.delegate = delegate
        // Do not fall back to the default (possibly network) mask if this cannot be formed.
        guard let mask = ICDeviceTypeMask(rawValue: ICDeviceTypeMask.camera.rawValue | ICDeviceLocationTypeMask.local.rawValue) else { return }
        browser.browsedDeviceTypeMask = mask
        self.browser = browser
        self.delegate = delegate
        browser.start()
    }
    func stop() {
        guard let browser else { return }
        browser.delegate = nil
        browser.stop()
        self.browser = nil
        delegate = nil
    }
    private final class BrowserDelegate: NSObject, ICDeviceBrowserDelegate {
        private let lock = NSLock()
        private var ids: [ObjectIdentifier: UUID] = [:]
        private let deliver: (USBDiscoveryEvent) -> Void
        init(deliver: @escaping (USBDiscoveryEvent) -> Void) { self.deliver = deliver }
        func deviceBrowser(_ browser: ICDeviceBrowser, didAdd device: ICDevice, moreComing: Bool) {
            guard device is ICCameraDevice else { return }
            let key = ObjectIdentifier(device)
            let name = device.name
            lock.lock()
            let id = ids[key] ?? UUID()
            ids[key] = id
            deliver(.added(id, name))
            lock.unlock()
        }
        func deviceBrowser(_ browser: ICDeviceBrowser, didRemove device: ICDevice, moreGoing: Bool) {
            let key = ObjectIdentifier(device)
            lock.lock()
            if let id = ids.removeValue(forKey: key) { deliver(.removed(id)) }
            lock.unlock()
        }
    }
}
