import Foundation

/// 一条 PTP/IP TCP 字节通道(命令通道或事件通道)。
/// 真实实现在 App 层(Network.framework);测试中使用脚本化的假相机。
public protocol PtpIpByteChannel: Sendable {
    /// 发送全部字节。
    func send(_ data: Data) async throws
    /// 接收下一段字节(至少 1 字节);对端关闭时返回空 Data。
    func receive() async throws -> Data
    /// 关闭通道(幂等)。
    func close() async
}

/// 把字节通道切分为完整的 PTP/IP 包。每条通道一个实例,读取在同一时间只由一个任务进行。
public actor PtpIpPacketStream {
    private let channel: PtpIpByteChannel
    private var reader = PtpIpFrameReader()

    public init(channel: PtpIpByteChannel) {
        self.channel = channel
    }

    public func send(_ bytes: Data) async throws {
        try await channel.send(bytes)
    }

    public func nextPacket() async throws -> PtpIpPacket {
        while true {
            if let packet = try reader.nextPacket() {
                return packet
            }
            let chunk = try await channel.receive()
            if chunk.isEmpty {
                throw PtpIpSessionError.closed
            }
            reader.append(chunk)
        }
    }

    public func close() async {
        await channel.close()
    }
}
