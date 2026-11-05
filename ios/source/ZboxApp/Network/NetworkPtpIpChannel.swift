import Foundation
import Network
import ZboxCore

/// 只允许 resume 一次的门闩(状态回调、超时与取消可能竞争)。
final class OnceGate: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func take() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

/// Network.framework 实现的 PTP/IP TCP 通道。
/// `localAddress` 为手机在热点网络上的地址(STA 模式),用于把 socket 绑定到热点接口,
/// 避免蜂窝网络接管第二条连接。
actor NetworkPtpIpChannel: PtpIpByteChannel {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "com.zbox.ai.ptpip.channel")

    init(host: String, port: UInt16 = PtpIpCodec.defaultPort, localAddress: String?) {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        if let localAddress, let local = IPv4Address(localAddress) {
            parameters.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(local), port: 0)
        }
        connection = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port) ?? NWEndpoint.Port(rawValue: PtpIpCodec.defaultPort)!,
            using: parameters
        )
    }

    /// 建立 TCP 连接;超时或失败时抛出 `PtpIpSessionError`。
    func open(timeoutSeconds: Double) async throws {
        let connection = connection
        let queue = queue
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let gate = OnceGate()
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if gate.take() { continuation.resume() }
                case .failed(let error):
                    if gate.take() {
                        connection.cancel()
                        continuation.resume(throwing: error)
                    }
                case .cancelled:
                    if gate.take() { continuation.resume(throwing: PtpIpSessionError.closed) }
                default:
                    break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeoutSeconds) {
                if gate.take() {
                    connection.cancel()
                    continuation.resume(throwing: PtpIpSessionError.timeout(stage: "TCP连接"))
                }
            }
        }
        connection.stateUpdateHandler = nil
    }

    func send(_ data: Data) async throws {
        let connection = connection
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            })
        }
    }

    /// 返回下一段字节;对端关闭时返回空 Data。
    func receive() async throws -> Data {
        let connection = connection
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let data, !data.isEmpty {
                    continuation.resume(returning: data)
                } else {
                    continuation.resume(returning: Data())
                }
            }
        }
    }

    func close() async {
        connection.cancel()
    }
}
