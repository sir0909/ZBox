import Foundation
import Network
import ZboxCore

/// 热点相机发现:枚举手机私有 IPv4 子网(bridge*/en*),对候选地址做短超时的 TCP 15740 探测。
/// 发现结果只表示端口可达,不代表已通过 PTP 握手。
enum HotspotCameraScanner {
    static let probeTimeoutSeconds: Double = 0.25
    static let maxConcurrentProbes = 32

    static func localSubnets() -> [IPv4Subnet] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }

        var result: [IPv4Subnet] = []
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(entry.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0,
                  let address = entry.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET),
                  let netmask = entry.pointee.ifa_netmask else { continue }
            let name = String(cString: entry.pointee.ifa_name)
            let ip = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                UInt32(bigEndian: $0.pointee.sin_addr.s_addr)
            }
            let mask = netmask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                UInt32(bigEndian: $0.pointee.sin_addr.s_addr)
            }
            if let subnet = HotspotAddressPlanner.subnet(interfaceName: name, address: ip, prefix: mask.nonzeroBitCount) {
                result.append(subnet)
            }
        }
        return result
    }

    static func scan() async -> [HotspotCameraEndpoint] {
        var targets: [(address: UInt32, subnet: IPv4Subnet)] = []
        for subnet in localSubnets() {
            for candidate in HotspotAddressPlanner.candidates(for: subnet) {
                targets.append((candidate, subnet))
            }
        }
        return await withTaskGroup(of: HotspotCameraEndpoint?.self) { group in
            var iterator = targets.makeIterator()
            for _ in 0..<maxConcurrentProbes {
                guard let target = iterator.next() else { break }
                group.addTask { await probe(target.address, subnet: target.subnet) }
            }
            var found: [HotspotCameraEndpoint] = []
            while let result = await group.next() {
                if let endpoint = result { found.append(endpoint) }
                if let target = iterator.next() {
                    group.addTask { await probe(target.address, subnet: target.subnet) }
                }
            }
            return HotspotConnectionPolicy.decide(found: found).candidates
        }
    }

    private static func probe(_ address: UInt32, subnet: IPv4Subnet) async -> HotspotCameraEndpoint? {
        let host = HotspotAddressPlanner.format(address)
        let local = HotspotAddressPlanner.format(subnet.localAddress)
        guard await portOpen(host: host, localAddress: local) else { return nil }
        return HotspotCameraEndpoint(cameraAddress: host, phoneAddress: local, interfaceName: subnet.interfaceName)
    }

    private static func portOpen(host: String, localAddress: String) async -> Bool {
        let parameters = NWParameters.tcp
        if let local = IPv4Address(localAddress) {
            parameters.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(local), port: 0)
        }
        let connection = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: PtpIpCodec.defaultPort)!,
            using: parameters
        )
        let queue = DispatchQueue.global(qos: .utility)
        return await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            let gate = OnceGate()
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if gate.take() {
                        connection.cancel()
                        continuation.resume(returning: true)
                    }
                case .failed, .waiting:
                    if gate.take() {
                        connection.cancel()
                        continuation.resume(returning: false)
                    }
                default:
                    break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + probeTimeoutSeconds) {
                if gate.take() {
                    connection.cancel()
                    continuation.resume(returning: false)
                }
            }
        }
    }
}
