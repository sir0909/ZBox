import Foundation

/// Read-only, conservative interpretation of Z30 custom picture-control records.
public struct PictureControlSlot: Equatable, Sendable, Identifiable {
    public enum State: Equatable, Sendable {
        case loaded(name: String, bytes: Int)
        case empty
        /// Access denied can indicate an empty slot OR insufficient access: do not assume either.
        case unavailable(responseCode: UInt16)
        case unexpectedData(bytes: Int)
    }
    public let index: Int
    public let state: State
    public var id: Int { index }

    public static func decode(index: Int, responseCode: UInt16, data: Data) -> Self {
        guard responseCode == PtpResponseCode.ok else {
            return Self(index: index, state: .unavailable(responseCode: responseCode))
        }
        guard !data.isEmpty else { return Self(index: index, state: .unexpectedData(bytes: 0)) }
        if data.allSatisfy({ $0 == 0 }) { return Self(index: index, state: .empty) }
        guard data.count >= 614 else { return Self(index: index, state: .unexpectedData(bytes: data.count)) }
        let payload = Array(data.suffix(614))
        guard payload[0] == 2, payload[1] == 0 else {
            return Self(index: index, state: .unexpectedData(bytes: data.count))
        }
        let nameBytes = payload[2..<22].prefix { $0 != 0 }.map { (0x20...0x7e).contains($0) ? $0 : UInt8(0x5f) }
        let name = String(decoding: nameBytes, as: UTF8.self).trimmingCharacters(in: .whitespaces)
        return Self(index: index, state: .loaded(name: name.isEmpty ? "C-\(index)" : name, bytes: data.count))
    }
}
