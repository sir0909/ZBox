import Foundation
import CryptoKit

/// Fixed Android catalog metadata, not the photographs themselves. Hashes are Git blob SHA-1,
/// useful for detecting mismatches against the bundled catalog, not for proving photo rights.
public struct PoseCatalog: Decodable, Sendable {
    public struct Item: Decodable, Equatable, Identifiable, Sendable {
        public let name: String
        public let code: String
        public let sha: String
        public let bytes: Int
        public var id: String { name }
        public init(name: String, code: String, sha: String, bytes: Int) {
            self.name = name; self.code = code; self.sha = sha; self.bytes = bytes
        }
    }
    public let commit: String
    public let definitions: [String: [String: String]]
    public let items: [Item]
    public static let pinnedCommit = "827ce3e424d0598bd7238ecc0c0a43ca362c7927"
    public static let catalogSHA256 = "15d760d48c8ec7e330779d46e6d3722b3aaee14ada863c2ea995490d6e478fda"
    public static let fields = ["shot_size", "composition", "angle", "pose", "action", "emotion",
                                "clothing", "hair", "color", "season", "scene", "style"]
    public static let fieldLabels = ["景别", "构图", "角度", "姿态", "动作", "情绪", "服装", "发型", "颜色", "季节", "场景", "风格"]
    public enum Failure: Error { case invalidCatalog }
    public static func load() throws -> PoseCatalog {
        guard let url = Bundle.module.url(forResource: "pose-catalog", withExtension: "json") else { throw Failure.invalidCatalog }
        let data = try Data(contentsOf: url)
        guard data.count <= 1_000_000,
              SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == catalogSHA256 else { throw Failure.invalidCatalog }
        let result = try JSONDecoder().decode(PoseCatalog.self, from: data)
        guard result.commit == pinnedCommit, result.items.count == 4155,
              Set(result.definitions.keys) == Set(fields), Set(result.items.map(\.name)).count == result.items.count else { throw Failure.invalidCatalog }
        for field in fields {
            guard let values = result.definitions[field], !values.isEmpty, values.count <= 30,
                  values.allSatisfy({ $0.key.utf8.count == 1 && (97...122).contains($0.key.utf8.first!) && !$0.value.isEmpty && $0.value.utf8.count <= 128 }) else { throw Failure.invalidCatalog }
        }
        for item in result.items {
            guard Self.isSafeItem(item),
                  zip(fields, item.code.map(String.init)).allSatisfy({ result.definitions[$0.0]?[$0.1] != nil }) else { throw Failure.invalidCatalog }
        }
        guard result.items.reduce(Int64(0), { $0 + Int64($1.bytes) }) <= 140 * 1024 * 1024 else { throw Failure.invalidCatalog }
        return result
    }
    public static func isSafeItem(_ item: Item) -> Bool {
        let name = Array(item.name.utf8), code = Array(item.code.utf8), sha = Array(item.sha.utf8)
        guard name.count == 21, code.count == 12, sha.count == 40, (1...8_388_608).contains(item.bytes),
              name[0..<4].allSatisfy({ (48...57).contains($0) }), name[4] == 45,
              Array(name[5..<17]) == code, Array(name[17..<21]) == Array(".jpg".utf8),
              code.allSatisfy({ (97...122).contains($0) }),
              sha.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else { return false }
        return true
    }
    public static func matchesBlob(_ item: Item, data: Data) -> Bool {
        guard isSafeItem(item), data.count == item.bytes else { return false }
        var digest = Insecure.SHA1()
        digest.update(data: Data("blob \(item.bytes)\0".utf8)); digest.update(data: data)
        return digest.finalize().map { String(format: "%02x", $0) }.joined() == item.sha
    }
    public func filtered(_ filters: [String: String]) -> [Item] {
        guard filters.allSatisfy({ definitions[$0.key]?[$0.value] != nil }) else { return [] }
        if filters.isEmpty { return items }
        return items.filter { item in
            let chars = Array(item.code).map(String.init)
            return filters.allSatisfy { key, value in
                guard let i = Self.fields.firstIndex(of: key) else { return false }
                return chars[i] == value
            }
        }
    }
}
