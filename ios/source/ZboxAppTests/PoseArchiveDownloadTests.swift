import XCTest
@testable import Zbox

final class PoseArchiveDownloadTests: XCTestCase {
    func testOfflineDownloadAndCancelBeforeStarting() async throws {
        let url = try XCTUnwrap(URL(string: "https://pose-catalog.invalid/archive.zip"))
        let downloader = PoseArchiveDownload { _, _ in }
        downloader.protocolClassForTesting = PoseOfflineProtocol.self
        let output = try await downloader.download(url)
        defer { try? FileManager.default.removeItem(at: output) }
        XCTAssertEqual(try Data(contentsOf: output), Data("fake archive".utf8))
        let cancelled = PoseArchiveDownload { _, _ in }
        cancelled.protocolClassForTesting = PoseOfflineProtocol.self
        cancelled.cancel()
        do {
            _ = try await cancelled.download(url)
            XCTFail("expected cancellation before transport")
        } catch { XCTAssertTrue(error is CancellationError) }
    }
    func testRedirectIsRejected() throws {
        let downloader = PoseArchiveDownload { _, _ in }
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let original = try XCTUnwrap(URL(string: "https://pose-catalog.invalid/archive.zip"))
        let target = try XCTUnwrap(URL(string: "https://elsewhere.invalid/archive.zip"))
        let task = session.dataTask(with: original)
        let response = try XCTUnwrap(HTTPURLResponse(url: original, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: ["Location": target.absoluteString]))
        var invoked = false
        downloader.urlSession(session, task: task, willPerformHTTPRedirection: response, newRequest: URLRequest(url: target)) { request in
            invoked = true; XCTAssertNil(request)
        }
        XCTAssertTrue(invoked)
    }
}
private final class PoseOfflineProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "pose-catalog.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url, let response = HTTPURLResponse(url: url, statusCode: 200,
              httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/zip"]) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("fake archive".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
