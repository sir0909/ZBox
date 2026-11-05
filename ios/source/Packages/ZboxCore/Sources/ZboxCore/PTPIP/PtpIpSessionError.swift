import Foundation

/// PTP/IP 会话层错误。`stage` 用于告诉用户失败发生在哪一步。
public enum PtpIpSessionError: Error, Equatable, Sendable {
    case timeout(stage: String)
    case closed
    case notConnected
    /// 相机返回 InitFail。reason=1 表示拒绝当前客户端身份(安卓的兼容名称重试只针对它)。
    case initFailed(reason: UInt32?)
    case unexpectedPacket(stage: String, type: UInt32)
    case malformed(stage: String, detail: String)
    case operationFailed(code: UInt16, opCode: UInt16)
    case transactionMismatch(expected: UInt32, actual: UInt32)
    case hostRegistrationUnsupported
    case pairingNotPrepared
    case pairingCodeUnrecognized
}

/// 为一个异步操作加超时。超时后由调用方关闭通道,不会自动重试。
public func withPtpIpTimeout<T: Sendable>(
    stage: String,
    seconds: Double,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T?.self) { group in
        group.addTask { () async throws -> T? in try await operation() }
        group.addTask { () async throws -> T? in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            return nil
        }
        defer { group.cancelAll() }
        guard let first = try await group.next(), let value = first else {
            throw PtpIpSessionError.timeout(stage: stage)
        }
        return value
    }
}

/// 串行化 PTP 操作:同一会话同一时间只允许一个事务在途。
actor PtpIpAsyncLock {
    private var held = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func withLock<T: Sendable>(_ body: @Sendable () async throws -> T) async throws -> T {
        await acquire()
        do {
            let value = try await body()
            release()
            return value
        } catch {
            release()
            throw error
        }
    }

    private func acquire() async {
        if !held {
            held = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    private func release() {
        if waiters.isEmpty {
            held = false
        } else {
            waiters.removeFirst().resume()
        }
    }
}
