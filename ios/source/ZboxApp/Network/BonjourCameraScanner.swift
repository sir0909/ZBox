import Foundation
import Network

/// A discovered service is a candidate, not proof of a Nikon camera.
struct BonjourCameraCandidate: Identifiable {
    let id: String
    let name: String
    let type: String
    let endpoint: NWEndpoint
}

struct BonjourScanResult {
    let candidates: [BonjourCameraCandidate]
    let failures: [String]
}

/// Explicit user-triggered discovery. Browsers and state are confined to one serial queue.
enum BonjourCameraScanner {
    static func scan(seconds: Double = 5) async -> BonjourScanResult {
        await withCheckedContinuation { continuation in
            BrowseState(continuation: continuation).start(seconds: seconds)
        }
    }

    private final class BrowseState: @unchecked Sendable {
        private let queue = DispatchQueue(label: "com.zbox.ai.bonjour-discovery")
        private let continuation: CheckedContinuation<BonjourScanResult, Never>
        private var browsers: [NWBrowser] = []
        private var results: [String: Set<NWBrowser.Result>] = [:]
        private var failures: [String] = []
        private var finished = false

        init(continuation: CheckedContinuation<BonjourScanResult, Never>) {
            self.continuation = continuation
        }

        func start(seconds: Double) {
            queue.async {
                for type in ["_ptp._tcp", "_nikon._tcp"] {
                    let parameters = NWParameters.tcp
                    parameters.includePeerToPeer = true
                    let browser = NWBrowser(for: .bonjour(type: type, domain: nil), using: parameters)
                    self.browsers.append(browser)
                    browser.browseResultsChangedHandler = { [weak self] found, _ in
                        guard let self, !self.finished else { return }
                        self.results[type] = found
                    }
                    browser.stateUpdateHandler = { [weak self] state in
                        guard let self, !self.finished else { return }
                        if case .failed(let error) = state {
                            self.failures.append("\(type): \(error)")
                        }
                    }
                    browser.start(queue: self.queue)
                }
                self.queue.asyncAfter(deadline: .now() + max(0.1, seconds)) { self.finish() }
            }
        }

        private func finish() {
            guard !finished else { return }
            finished = true
            browsers.forEach { $0.cancel() }
            var byID: [String: BonjourCameraCandidate] = [:]
            for group in results.values {
                for result in group {
                    guard case let .service(name, type, domain, interface) = result.endpoint else { continue }
                    let id = [name, type, domain, interface?.name ?? ""].joined(separator: "|")
                    byID[id] = BonjourCameraCandidate(id: id, name: name, type: type, endpoint: result.endpoint)
                }
            }
            let sorted = byID.values.sorted { $0.id.localizedStandardCompare($1.id) == .orderedAscending }
            continuation.resume(returning: BonjourScanResult(candidates: sorted, failures: failures))
        }
    }
}
