import Foundation

/// A single explicitly authorized download; no cookies, redirects, credentials or automatic retries.
final class PoseArchiveDownload: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    enum Failure: Error { case rejected, tooLarge, noFile }
    private let limit: Int64 = 180 * 1024 * 1024
    private let lock = NSLock()
    private var continuation: CheckedContinuation<URL, Error>?
    private var task: URLSessionDownloadTask?
    private var session: URLSession?
    private var cancelled = false
    private var tooLarge = false
    private var completed = false
    private var lastReportedMiB: Int64 = -1
    #if DEBUG
    /// Test-only local URLProtocol; production uses the system HTTPS transport.
    var protocolClassForTesting: AnyClass?
    #endif
    private let report: @Sendable (Int64, Int64) -> Void
    init(report: @escaping @Sendable (Int64, Int64) -> Void) { self.report = report }

    func download(_ url: URL) async throws -> URL {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                if cancelled { lock.unlock(); continuation.resume(throwing: CancellationError()); return }
                self.continuation = continuation
                let config = URLSessionConfiguration.ephemeral
                #if DEBUG
                if let protocolClassForTesting { config.protocolClasses = [protocolClassForTesting] }
                #endif
                config.urlCache = nil; config.urlCredentialStorage = nil; config.httpCookieStorage = nil
                config.timeoutIntervalForRequest = 60; config.timeoutIntervalForResource = 1800
                let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
                let task = session.downloadTask(with: url)
                self.session = session; self.task = task
                lock.unlock()
                task.resume()
            }
        } onCancel: { self.cancel() }
    }
    func cancel() {
        lock.lock(); cancelled = true; let task = self.task; lock.unlock()
        task?.cancel()
    }
    private func finish(_ result: Result<URL, Error>) {
        lock.lock()
        guard !completed else {
            lock.unlock()
            if case .success(let url) = result { try? FileManager.default.removeItem(at: url) }
            return
        }
        completed = true; let continuation = self.continuation; self.continuation = nil
        let session = self.session; self.session = nil; self.task = nil
        let wasCancelled = cancelled
        lock.unlock()
        session?.invalidateAndCancel()
        if wasCancelled {
            if case .success(let url) = result { try? FileManager.default.removeItem(at: url) }
            continuation?.resume(throwing: CancellationError())
        } else { continuation?.resume(with: result) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        if totalBytesWritten > limit || totalBytesExpectedToWrite > limit {
            lock.lock(); tooLarge = true; lock.unlock(); downloadTask.cancel(); return
        }
        lock.lock()
        let mib = totalBytesWritten / 1_048_576
        let changed = mib != lastReportedMiB
        if changed { lastReportedMiB = mib }
        lock.unlock()
        if changed { report(totalBytesWritten, totalBytesExpectedToWrite) }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        do {
            guard let response = downloadTask.response as? HTTPURLResponse, response.statusCode == 200,
                  let size = try FileManager.default.attributesOfItem(atPath: location.path)[.size] as? NSNumber,
                  size.int64Value <= limit else { throw Failure.rejected }
            let target = FileManager.default.temporaryDirectory.appendingPathComponent("zbox-pose-\(UUID().uuidString).zip")
            try FileManager.default.moveItem(at: location, to: target)
            finish(.success(target))
        } catch { finish(.failure(error)) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock(); let exceeded = tooLarge; lock.unlock()
        if exceeded { finish(.failure(Failure.tooLarge)) }
        else if let error { finish(.failure(error)) }
        else { finish(.failure(Failure.noFile)) }
    }
}
