import Foundation

/// Sequential, bounded thumbnail orchestration; callers own caching and UI.
public enum ThumbnailBatch {
    public static let pageSize = 12
    public static func run<Item: Sendable>(
        _ items: [Item],
        shouldContinue: @escaping @Sendable () async -> Bool,
        fetch: @escaping @Sendable (Item) async throws -> Data?,
        receive: @escaping @Sendable (Item, Data?) async -> Void
    ) async throws {
        guard items.count <= pageSize else {
            throw PtpIpSessionError.malformed(stage: "相册", detail: "缩略图批次数量超限")
        }
        for item in items {
            guard await shouldContinue() else { throw CancellationError() }
            let data = try await fetch(item)
            guard await shouldContinue() else { throw CancellationError() }
            await receive(item, data)
        }
    }
}
