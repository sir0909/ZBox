import Foundation
import Security

/// PTP/IP 客户端 GUID 持久化(16 字节,存于 Keychain)。
/// 格式与安卓版一致(32 位十六进制),便于双机互通时沿用同一身份。
enum ClientIdentityStore {
    enum StoreError: Error {
        case randomFailed
        case keychain(OSStatus)
    }

    private static let service = "com.zbox.ai.identity"
    private static let account = "ptpip-client-guid"

    static func loadOrCreateGuid() throws -> Data {
        if let existing = try readGuid(), existing.count == 16 {
            return existing
        }
        var bytes = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw StoreError.randomFailed
        }
        let guid = Data(bytes)
        try writeGuid(guid)
        return guid
    }

    static func hex(_ data: Data) -> String {
        data.map { String(format: "%02X", $0) }.joined()
    }

    private static func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private static func readGuid() throws -> Data? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw StoreError.keychain(status) }
        return item as? Data
    }

    private static func writeGuid(_ guid: Data) throws {
        var query = baseQuery()
        query[kSecValueData as String] = guid
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else {
            throw StoreError.keychain(status)
        }
    }
}
