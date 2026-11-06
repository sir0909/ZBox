import XCTest
@testable import Zbox

@MainActor
final class USBDiscoveryTests: XCTestCase {
    func testManualStartDeduplicationRemovalAndStop() async {
        let driver = FakeUSBDiscoveryDriver()
        let store = USBDiscoveryStore(driver: driver)
        XCTAssertFalse(store.scanning)
        XCTAssertEqual(driver.starts, 0)
        store.start(); store.start()
        XCTAssertEqual(driver.starts, 1)
        let a = UUID(), b = UUID()
        driver.emit(.added(a, "  Camera\n A  "))
        driver.emit(.added(a, "Renamed"))
        driver.emit(.added(b, nil))
        await drainMainQueue()
        XCTAssertEqual(store.candidates, [USBDiscoveryCandidate(id: a, name: "Renamed"), USBDiscoveryCandidate(id: b, name: "未命名设备")])
        driver.emit(.removed(a))
        await drainMainQueue()
        XCTAssertEqual(store.candidates.map(\.id), [b])
        store.stop(); store.stop()
        XCTAssertEqual(driver.stops, 1)
        XCTAssertFalse(store.scanning)
        XCTAssertTrue(store.candidates.isEmpty)
    }
    func testOldCallbacksCannotPopulateNewScanOrAfterStop() async {
        let driver = FakeUSBDiscoveryDriver()
        let store = USBDiscoveryStore(driver: driver)
        store.start()
        let old = driver.callback!
        old(.added(UUID(), "queued before stop"))
        store.stop()
        store.start()
        let currentID = UUID()
        old(.added(UUID(), "old scan"))
        driver.emit(.added(currentID, "current"))
        await drainMainQueue()
        XCTAssertEqual(store.candidates.map(\.id), [currentID])
        store.stop()
        driver.emit(.added(UUID(), "after stop"))
        await drainMainQueue()
        XCTAssertTrue(store.candidates.isEmpty)
    }
    func testNamesAreBoundedAndCandidateCountLimited() async {
        let driver = FakeUSBDiscoveryDriver()
        let store = USBDiscoveryStore(driver: driver)
        store.start()
        let first = UUID()
        driver.emit(.added(first, "\n" + String(repeating: "A", count: 100) + "\u{0000}"))
        for _ in 0..<70 { driver.emit(.added(UUID(), "device")) }
        await drainMainQueue()
        XCTAssertEqual(store.candidates.count, 64)
        XCTAssertEqual(store.candidates.first?.name, String(repeating: "A", count: 80))
        store.stop()
    }
    private func drainMainQueue() async {
        await withCheckedContinuation { continuation in
            DispatchQueue.main.async { continuation.resume() }
        }
    }
}
private final class FakeUSBDiscoveryDriver: USBDiscoveryDriver {
    var starts = 0
    var stops = 0
    var callback: ((USBDiscoveryEvent) -> Void)?
    func start(deliver: @escaping (USBDiscoveryEvent) -> Void) { starts += 1; callback = deliver }
    func stop() { stops += 1 }
    func emit(_ event: USBDiscoveryEvent) { callback?(event) }
}
