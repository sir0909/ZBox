import XCTest
@testable import ZboxCore

private actor FakePartialSource {
    let bytes: Data
    let maxReturn: Int
    private(set) var requests: [(UInt64, Int)] = []
    init(_ bytes: Data, maxReturn: Int) { self.bytes = bytes; self.maxReturn = maxReturn }
    func read(_ offset: UInt64, _ wanted: Int) -> Data {
        requests.append((offset, wanted))
        let start = Int(offset), amount = min(maxReturn, wanted, bytes.count - start)
        return bytes.slice(at: start, count: amount)
    }
    func receivedOffsets() -> [UInt64] { requests.map { $0.0 } }
}
private actor ContinueSwitch {
    private var allowed = true
    func cancel() { allowed = false }
    func value() -> Bool { allowed }
}

final class STAFileDownloadTests: XCTestCase {
    private func temporaryRoot() throws -> URL {
        let u = FileManager.default.temporaryDirectory.appendingPathComponent("ZboxTests-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: u, withIntermediateDirectories: false)
        return u
    }
    func testShortChunksAdvanceByActualLengthAndProduceExactFile() async throws {
        let root = try temporaryRoot(); defer { try? FileManager.default.removeItem(at: root) }
        let source = FakePartialSource(Data([0, 1, 2, 3, 4, 5, 6]), maxReturn: 3)
        let result = try await STAFileDownload.download(size: 7, fileName: "ZBox-29000001.JPG", root: root,
            shouldContinue: { true }, fetch: { await source.read($0, $1) })
        let url = result.url
        XCTAssertEqual(try Data(contentsOf: url), Data([0, 1, 2, 3, 4, 5, 6]))
        XCTAssertEqual(result.receivedSHA256, "57355ac3303c148f11aef7cb179456b9232cde33a818dfda2c2fcb9325749a6b")
        let offsets = await source.receivedOffsets(); XCTAssertEqual(offsets, [0, 3, 6])
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.deletingLastPathComponent().appendingPathComponent("partial").path))
    }
    func testDigestSpansFullSizedAndFinalShortBlock() async throws {
        let root = try temporaryRoot(); defer { try? FileManager.default.removeItem(at: root) }
        let bytes = Data(repeating: 0x5a, count: STAFileDownload.chunkBytes + 3)
        let source = FakePartialSource(bytes, maxReturn: STAFileDownload.chunkBytes)
        let result = try await STAFileDownload.download(size: UInt64(bytes.count), fileName: "photo.JPG", root: root,
            shouldContinue: { true }, fetch: { await source.read($0, $1) })
        XCTAssertEqual(result.receivedSHA256, "d514a312ba2f8c60e476521b5f768f77f5907bc2ea6c4010676c0c8dc062570a")
        XCTAssertEqual(try Data(contentsOf: result.url), bytes)
        let offsets = await source.receivedOffsets()
        XCTAssertEqual(offsets, [0, UInt64(STAFileDownload.chunkBytes)])
    }
    func testCancelAfterChunkDeletesPartialDirectory() async throws {
        let root = try temporaryRoot(); defer { try? FileManager.default.removeItem(at: root) }
        let control = ContinueSwitch()
        do {
            _ = try await STAFileDownload.download(size: 2, fileName: "photo.JPG", root: root,
                shouldContinue: { await control.value() },
                fetch: { _, _ in await control.cancel(); return Data([1]) })
            XCTFail("cancelled transfer returned a file")
        } catch is CancellationError {}
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path), [])
    }
    func testInvalidOrEmptyChunksCannotProduceSuccessfulFile() async throws {
        let root = try temporaryRoot(); defer { try? FileManager.default.removeItem(at: root) }
        for data in [Data(), Data([1, 2, 3])] {
            do {
                _ = try await STAFileDownload.download(size: 2, fileName: "photo.JPG", root: root,
                    shouldContinue: { true }, fetch: { _, _ in data })
                XCTFail("bad response returned a file")
            } catch STAFileDownloadError.invalidChunk {}
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path), [])
        }
    }
    func testRejectsInvalidNamesAndSizesBeforeCreatingAnything() async throws {
        let root = try temporaryRoot(); defer { try? FileManager.default.removeItem(at: root) }
        for name in ["../evil.JPG", ".hidden", "a/b.JPG", "bad\u{0}file"] {
            do {
                _ = try await STAFileDownload.download(size: 2, fileName: name, root: root,
                    shouldContinue: { true }, fetch: { _, _ in Data([1, 2]) })
                XCTFail("invalid name accepted")
            } catch STAFileDownloadError.invalidFileName {}
        }
        do {
            _ = try await STAFileDownload.download(size: 0, fileName: "ok.JPG", root: root,
                shouldContinue: { true }, fetch: { _, _ in Data() })
            XCTFail("zero size accepted")
        } catch STAFileDownloadError.invalidSize {}
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path), [])
    }
}
