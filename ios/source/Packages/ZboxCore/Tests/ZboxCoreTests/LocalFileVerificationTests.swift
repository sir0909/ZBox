import XCTest
@testable import ZboxCore

private actor VerificationControl {
    private var allowed = true
    private(set) var progressValues: [UInt64] = []
    func active() -> Bool { allowed }
    func cancel() { allowed = false }
    func record(_ offset: UInt64) { progressValues.append(offset) }
    func values() -> [UInt64] { progressValues }
}

final class LocalFileVerificationTests: XCTestCase {
    private let digest = "d514a312ba2f8c60e476521b5f768f77f5907bc2ea6c4010676c0c8dc062570a"
    private func temporaryFile(_ bytes: Data) throws -> URL {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent("zbox-verify-" + UUID().uuidString)
        try bytes.write(to: path)
        return path
    }
    func testReadbackMatchesAndReportsBoundedProgress() async throws {
        let size = STAFileDownload.chunkBytes + 3
        let path = try temporaryFile(Data(repeating: 0x5a, count: size))
        defer { try? FileManager.default.removeItem(at: path) }
        let control = VerificationControl()
        let result = try await LocalFileVerification.verify(url: path, expectedSize: UInt64(size), expectedSHA256: digest,
            shouldContinue: { await control.active() }, progress: { offset, _ in await control.record(offset) })
        XCTAssertEqual(result, .matches)
        let values = await control.values()
        XCTAssertEqual(values, [UInt64(STAFileDownload.chunkBytes), UInt64(size)])
    }
    func testChangedBytesTruncatedAndExtraBytesMismatch() async throws {
        let size = STAFileDownload.chunkBytes + 3
        let path = try temporaryFile(Data(repeating: 0x5a, count: size))
        defer { try? FileManager.default.removeItem(at: path) }
        for bytes in [Data(repeating: 0x5b, count: size), Data(repeating: 0x5a, count: size - 1),
                      Data(repeating: 0x5a, count: size + 1)] {
            try bytes.write(to: path)
            let result = try await LocalFileVerification.verify(url: path, expectedSize: UInt64(size), expectedSHA256: digest,
                shouldContinue: { true })
            XCTAssertEqual(result, .mismatch)
        }
    }
    func testCancellationAndInvalidArguments() async throws {
        let path = try temporaryFile(Data(repeating: 0x5a, count: STAFileDownload.chunkBytes + 3))
        defer { try? FileManager.default.removeItem(at: path) }
        let control = VerificationControl()
        do {
            _ = try await LocalFileVerification.verify(url: path, expectedSize: UInt64(STAFileDownload.chunkBytes + 3), expectedSHA256: digest,
                shouldContinue: { await control.active() }, progress: { _, _ in await control.cancel() })
            XCTFail("cancelled verification returned a result")
        } catch is CancellationError {}
        do {
            _ = try await LocalFileVerification.verify(url: path, expectedSize: 0, expectedSHA256: digest, shouldContinue: { true })
            XCTFail("zero size accepted")
        } catch LocalFileVerificationError.invalidExpectedSize {}
        do {
            _ = try await LocalFileVerification.verify(url: path, expectedSize: 3, expectedSHA256: "not-a-hash", shouldContinue: { true })
            XCTFail("invalid hash accepted")
        } catch LocalFileVerificationError.invalidExpectedDigest {}
    }
}
