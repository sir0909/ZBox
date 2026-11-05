import Foundation
import CryptoKit

public enum STAFileDownloadError: Error, Equatable, Sendable {
    case invalidSize
    case invalidFileName
    case insufficientSpace
    case invalidChunk
    case lengthMismatch
    case unableToCreateFile
}

/// SHA-256 of the bytes received and successfully written during the transfer.
/// It is not a hash supplied by the camera and is not an independent disk readback.
public struct STAFileDownloadResult: Sendable {
    public let url: URL
    public let receivedSHA256: String
}

/// Cancellable, bounded sequential transfer to a unique temporary directory.
/// It validates lengths but has no camera-supplied hash, so it cannot verify content identity.
public enum STAFileDownload {
    public static let maxSize: UInt64 = 32 * 1024 * 1024 * 1024
    public static let chunkBytes = 512 * 1024

    public static func download(
        size: UInt64,
        fileName: String,
        root: URL = FileManager.default.temporaryDirectory,
        shouldContinue: @escaping @Sendable () async -> Bool,
        fetch: @escaping @Sendable (_ offset: UInt64, _ length: Int) async throws -> Data,
        progress: @escaping @Sendable (_ completed: UInt64, _ total: UInt64) async -> Void = { _, _ in }
    ) async throws -> STAFileDownloadResult {
        guard size > 0, size <= maxSize else { throw STAFileDownloadError.invalidSize }
        let permitted = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-")
        guard !fileName.isEmpty, fileName.count <= 64, !fileName.hasPrefix("."),
              !fileName.contains(".."), fileName.unicodeScalars.allSatisfy({ permitted.contains($0) }) else {
            throw STAFileDownloadError.invalidFileName
        }
        guard await shouldContinue() else { throw CancellationError() }
        let values = try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        if let free = values?.volumeAvailableCapacityForImportantUsage, free >= 0,
           UInt64(free) < size + 8 * 1024 * 1024 { throw STAFileDownloadError.insufficientSpace }
        let fm = FileManager.default
        let directory = root.appendingPathComponent("ZBox-" + UUID().uuidString, isDirectory: true)
        let partial = directory.appendingPathComponent("partial")
        let final = directory.appendingPathComponent(fileName)
        var completed = false
        defer { if !completed { try? fm.removeItem(at: directory) } }
        try fm.createDirectory(at: directory, withIntermediateDirectories: false)
        guard fm.createFile(atPath: partial.path, contents: nil) else { throw STAFileDownloadError.unableToCreateFile }
        let writer = try FileHandle(forWritingTo: partial)
        defer { try? writer.close() }
        var offset: UInt64 = 0
        var digest = SHA256()
        while offset < size {
            guard await shouldContinue() else { throw CancellationError() }
            let wanted = Int(min(UInt64(chunkBytes), size - offset))
            let data = try await fetch(offset, wanted)
            guard await shouldContinue() else { throw CancellationError() }
            guard !data.isEmpty, data.count <= wanted else { throw STAFileDownloadError.invalidChunk }
            try writer.write(contentsOf: data)
            digest.update(data: data)
            offset += UInt64(data.count)
            await progress(offset, size)
        }
        try writer.close()
        let actual = try fm.attributesOfItem(atPath: partial.path)[.size] as? NSNumber
        guard actual?.uint64Value == size else { throw STAFileDownloadError.lengthMismatch }
        guard await shouldContinue() else { throw CancellationError() }
        try fm.moveItem(at: partial, to: final)
        let receivedSHA256 = digest.finalize().map { String(format: "%02x", $0) }.joined()
        completed = true
        return STAFileDownloadResult(url: final, receivedSHA256: receivedSHA256)
    }
}
