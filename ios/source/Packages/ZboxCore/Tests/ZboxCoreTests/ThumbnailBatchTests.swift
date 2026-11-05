import XCTest
@testable import ZboxCore

private actor BatchProbe {
    var fetched: [Int] = []
    var delivered: [Int] = []
    var allowed = true
    func fetch(_ i: Int) -> Data? { fetched.append(i); return i == 2 ? nil : Data([UInt8(i)]) }
    func deliver(_ i: Int) { delivered.append(i) }
    func cancel() { allowed = false }
    func active() -> Bool { allowed }
    func snapshot() -> ([Int], [Int]) { (fetched, delivered) }
}

final class ThumbnailBatchTests: XCTestCase {
    func testProcessesSequentiallyAndToleratesMissingThumbnail() async throws {
        let probe = BatchProbe()
        try await ThumbnailBatch.run([1, 2, 3], shouldContinue: { await probe.active() },
            fetch: { await probe.fetch($0) }, receive: { item, _ in await probe.deliver(item) })
        let (fetched, delivered) = await probe.snapshot()
        XCTAssertEqual(fetched, [1, 2, 3]); XCTAssertEqual(delivered, [1, 2, 3])
    }
    func testCancellationAfterFetchDoesNotDeliverStaleImage() async throws {
        let probe = BatchProbe()
        do {
            try await ThumbnailBatch.run([1, 2], shouldContinue: { await probe.active() },
                fetch: { value in await probe.cancel(); return await probe.fetch(value) },
                receive: { item, _ in await probe.deliver(item) })
            XCTFail("cancelled thumbnail was delivered")
        } catch is CancellationError {}
        let (fetched, delivered) = await probe.snapshot()
        XCTAssertEqual(fetched, [1]); XCTAssertTrue(delivered.isEmpty)
    }
    func testRejectsOversizedBatchBeforeFetch() async throws {
        let probe = BatchProbe()
        do {
            try await ThumbnailBatch.run(Array(0...12), shouldContinue: { true },
                fetch: { await probe.fetch($0) }, receive: { item, _ in await probe.deliver(item) })
            XCTFail("oversized batch accepted")
        } catch PtpIpSessionError.malformed {}
        let (fetched, _) = await probe.snapshot(); XCTAssertTrue(fetched.isEmpty)
    }
}
