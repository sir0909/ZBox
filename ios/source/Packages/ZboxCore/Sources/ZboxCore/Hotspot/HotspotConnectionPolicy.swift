import Foundation

/// 热点扫描的结果(一台候选相机)。
public struct HotspotCameraEndpoint: Equatable, Hashable, Sendable {
    public let cameraAddress: String
    public let phoneAddress: String
    public let interfaceName: String

    public init(cameraAddress: String, phoneAddress: String, interfaceName: String) {
        self.cameraAddress = cameraAddress
        self.phoneAddress = phoneAddress
        self.interfaceName = interfaceName
    }
}

/// 扫描完成后再决定:0 台不连,1 台自动连,多台由用户选择(与安卓一致,不取第一台)。
public enum HotspotConnectionPolicy {
    public enum Action: Equatable, Sendable { case none, autoConnect, choose }

    public struct Decision: Equatable, Sendable {
        public let action: Action
        public let candidates: [HotspotCameraEndpoint]
    }

    public static func decide(found: [HotspotCameraEndpoint]) -> Decision {
        var seen = Set<String>()
        let unique = found.filter { seen.insert($0.cameraAddress).inserted }
        let action: Action
        switch unique.count {
        case 0: action = .none
        case 1: action = .autoConnect
        default: action = .choose
        }
        return Decision(action: action, candidates: unique)
    }
}

/// IPv4 子网规划(纯函数,便于单测)。App 层负责真正的 TCP 探测。
public struct IPv4Subnet: Equatable, Sendable {
    public let interfaceName: String
    public let localAddress: UInt32
    public let prefix: Int
}

public enum HotspotAddressPlanner {
    /// 子网主机数超过此值时跳过扫描(安卓 MAX_PREFIX_HOSTS)。
    public static let maxScannedHosts = 1022

    public static func parseIPv4(_ text: String) -> UInt32? {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        var value: UInt32 = 0
        for part in parts {
            guard let octet = UInt32(part), octet <= 255 else { return nil }
            value = (value << 8) | octet
        }
        return value
    }

    public static func format(_ address: UInt32) -> String {
        [24, 16, 8, 0].map { String((address >> UInt32($0)) & 0xff) }.joined(separator: ".")
    }

    /// 私有地址:10/8、172.16/12、192.168/16。
    public static func isPrivate(_ address: UInt32) -> Bool {
        let a = address >> 24
        let b = (address >> 16) & 0xff
        return a == 10 || (a == 172 && (16...31).contains(b)) || (a == 192 && b == 168)
    }

    /// 蜂窝、VPN、隧道与 AWDL 等接口不参与热点扫描。
    public static func isCellularOrTunnel(_ name: String) -> Bool {
        ["pdp_ip", "utun", "ipsec", "tun", "awdl", "llw", "lo", "gif", "stf", "anpi"]
            .contains { name.hasPrefix($0) }
    }

    /// 返回可扫描的子网;前缀不在 16...30、非私有地址或接口被排除时返回 nil。
    public static func subnet(interfaceName: String, address: UInt32, prefix: Int) -> IPv4Subnet? {
        guard !isCellularOrTunnel(interfaceName), (16...30).contains(prefix), isPrivate(address) else {
            return nil
        }
        return IPv4Subnet(interfaceName: interfaceName, localAddress: address, prefix: prefix)
    }

    /// 子网内待探测的主机地址(排除网络号、广播地址与本机)。主机数超过上限时返回空数组。
    public static func candidates(for subnet: IPv4Subnet) -> [UInt32] {
        let hostCount = (UInt64(1) << UInt64(32 - subnet.prefix)) - 2
        guard hostCount >= 1, hostCount <= UInt64(maxScannedHosts) else { return [] }
        let hostBlock = UInt32(1) << UInt32(32 - subnet.prefix)
        let mask = ~(hostBlock - 1)
        let network = subnet.localAddress & mask
        let broadcast = network | ~mask
        return ((network + 1)..<broadcast).filter { $0 != subnet.localAddress }
    }
}
