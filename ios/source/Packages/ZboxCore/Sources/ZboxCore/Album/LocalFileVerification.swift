import Foundation
import CryptoKit

public enum LocalFileVerificationError: Error, Equatable, Sendable {
    case invalidExpectedSize
    case invalidExpectedDigest
}

/// Bounded disk re-read compared to a previously calculated receive-stream digest.
/// Matching cannot authenticate the camera source or prevent later local changes.
public enum LocalFileVerification {
    public enum Result: Equatable, Sendable { case matches, mismatch }
    public static func verify(
        url: URL, expectedSize: UInt64, expectedSHA256: String,
        shouldContinue: @escaping @Sendable () async -> Bool,
        progress: @escaping @Sendable (UInt64, UInt64) async -> Void = { _, _ in }
    ) async throws -> Result {
        guard expectedSize > 0, expectedSize <= STAFileDownload.maxSize else {
            throw LocalFileVerificationError.invalidExpectedSize
        }
        let hex = CharacterSet(charactersIn: "0123456789abcdef")
        guard expectedSHA256.utf8.count == 64,
              expectedSHA256.unicodeScalars.allSatisfy({ hex.contains($0) }) else {
            throw LocalFileVerificationError.invalidExpectedDigest
        }
        guard await shouldContinue() else { throw CancellationError() }
        let reader = try FileHandle(forReadingFrom: url)
        defer { try? reader.close() }
        var digest = SHA256()
        var offset: UInt64 = 0
        while offset < expectedSize {
            guard await shouldContinue() else { throw CancellationError() }
            let amount = Int(min(UInt64(STAFileDownload.chunkBytes), expectedSize - offset))
            let bytes = try reader.read(upToCount: amount) ?? Data()
            guard await shouldContinue() else { throw CancellationError() }
            guard !bytes.isEmpty, bytes.count <= amount else { return .mismatch }
            digest.update(data: bytes)
            offset += UInt64(bytes.count)
            await progress(offset, expectedSize)
        }
        guard await shouldContinue() else { throw CancellationError() }
        guard (try reader.read(upToCount: 1) ?? Data()).isEmpty else { return .mismatch }
        guard await shouldContinue() else { throw CancellationError() }
        let actual = digest.finalize().map { String(format: "%02x", $0) }.joined()
        return actual == expectedSHA256 ? .matches : .mismatch
    }
}
