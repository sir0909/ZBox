import Foundation

/// 连接器:与安卓一致,仅当相机以 InitFail reason=1 拒绝当前客户端名称时,
/// 使用兼容名称再尝试一次(之后不再重试)。
public enum PtpIpConnector {
    public static let defaultPrimaryName = "Zbox"
    public static let defaultFallbackName = "Z-Cloud AI"

    public struct Connected: Sendable {
        public let session: PtpIpSession
        public let info: PtpIpConnectionInfo
        public let usedName: String
    }

    /// - Parameter makeSession: 为给定客户端名称创建一个全新的会话(新的命令通道)。
    public static func connect(
        primaryName: String = defaultPrimaryName,
        fallbackName: String = defaultFallbackName,
        retryDelayNanoseconds: UInt64 = 400_000_000,
        makeSession: @escaping @Sendable (String) async throws -> PtpIpSession
    ) async throws -> Connected {
        let first = try await makeSession(primaryName)
        do {
            let info = try await first.connect()
            return Connected(session: first, info: info, usedName: primaryName)
        } catch PtpIpSessionError.initFailed(let reason) where reason == PtpIpInitFailReason.rejected.rawValue && fallbackName != primaryName {
            await first.close()
            try await Task.sleep(nanoseconds: retryDelayNanoseconds)
            let second = try await makeSession(fallbackName)
            let info = try await second.connect()
            return Connected(session: second, info: info, usedName: fallbackName)
        }
    }
}
