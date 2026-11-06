import Foundation

/// Local file editing only. Camera writes require a separate backed-up and validated workflow.
public enum RecipePayload {
    public enum Error: Swift.Error, Equatable { case invalidName, invalidLUT, invalidFile }
    public struct Imported: Sendable {
        public let payload: Data
        public let description: String
        public let approximate: Bool
        /// Only the old 84-byte layout has a recognized family byte. Metadata for local use only.
        public let legacyFamily: Int?
    }

    public static func linearTemplate() -> Data {
        var bytes = [UInt8](repeating: 0, count: PictureControl614.size)
        bytes[0] = 2; bytes[0x19] = 8; bytes[0x1a] = 4; bytes[0x23] = 1
        bytes[0x24] = 0x49; bytes[0x25] = 0x30; bytes[0x27] = 0xff; bytes[0x29] = 0xff
        bytes[0x2a] = 1; bytes[0x2b] = 15; bytes[0x2c] = 5
        return try! edit(template: Data(bytes), name: "ZBOX", lut: (0...255).map(UInt8.init))
    }
    public static func lut8(_ data: Data) throws -> [UInt8] {
        let parsed = try PictureControl614.parse(data)
        return parsed.curve.map { UInt8((UInt32($0) * 255 + 16383) / 32767) }
    }
    /// A nil or equal LUT preserves every original 15-bit value and every original control point.
    public static func edit(template: Data, name: String, lut: [UInt8]? = nil) throws -> Data {
        let old = try PictureControl614.parse(template)
        let nameBytes = Array(name.utf8)
        guard !nameBytes.isEmpty, nameBytes.count <= 19, nameBytes.allSatisfy({ (0x20...0x7e).contains($0) }),
              !name.trimmingCharacters(in: .whitespaces).isEmpty else { throw Error.invalidName }
        if let lut, lut.count != 256 { throw Error.invalidLUT }
        var b = [UInt8](template)
        for i in 2..<22 { b[i] = 0 }
        b.replaceSubrange(2..<(2 + nameBytes.count), with: nameBytes)
        if let lut {
            let before = old.curve.map { UInt8((UInt32($0) * 255 + 16383) / 32767) }
            if before != lut {
                for i in 0..<256 {
                    let value = (UInt32(lut[i]) * 32767 + 127) / 255
                    b[0x66 + i * 2] = UInt8(value >> 8); b[0x67 + i * 2] = UInt8(value & 255)
                }
                for i in 0..<4 {
                    let x = i * 255 / 3
                    b[0x2f + i * 2] = UInt8(x); b[0x30 + i * 2] = lut[x]
                }
            }
        }
        return Data(b)
    }
    /// Direct 15-bit editing; nil or an identical table preserves the original control points and LUT bytes.
    /// Other, unknown fields always remain those of the template.
    public static func edit15(template: Data, name: String, lut: [UInt16]? = nil) throws -> Data {
        let old = try PictureControl614.parse(template)
        let nameBytes = Array(name.utf8)
        guard !nameBytes.isEmpty, nameBytes.count <= 19, nameBytes.allSatisfy({ (0x20...0x7e).contains($0) }),
              !name.trimmingCharacters(in: .whitespaces).isEmpty else { throw Error.invalidName }
        if let lut, lut.count != 256 || lut.contains(where: { $0 > 32767 }) { throw Error.invalidLUT }
        var b = [UInt8](template)
        for i in 2..<22 { b[i] = 0 }
        b.replaceSubrange(2..<(2 + nameBytes.count), with: nameBytes)
        if let lut, lut != old.curve {
            for i in 0..<256 {
                b[0x66 + i * 2] = UInt8(lut[i] >> 8); b[0x67 + i * 2] = UInt8(lut[i] & 255)
            }
            for i in 0..<4 {
                let x = i * 255 / 3
                b[0x2f + i * 2] = UInt8(x)
                b[0x30 + i * 2] = UInt8((UInt32(lut[x]) * 255 + 16383) / 32767)
            }
        }
        return Data(b)
    }
    public static func exportNCP(_ payload: Data) throws -> Data {
        _ = try PictureControl614.parse(payload)
        return Data([0x4e, 0x43, 0x50, 0]) + payload
    }
    /// Only explicitly understood layouts; no heuristic scanning of arbitrary input.
    public static func importFile(_ data: Data) throws -> Imported {
        if data.count == 614 {
            _ = try PictureControl614.parse(data)
            return Imported(payload: data, description: "614 字节原始载荷", approximate: false, legacyFamily: nil)
        }
        if data.count == 618 && data.prefix(4) == Data([0x4e, 0x43, 0x50, 0]) {
            let payload = Data(data.dropFirst(4)); _ = try PictureControl614.parse(payload)
            return Imported(payload: payload, description: "NCP 头 + 614 字节载荷", approximate: false, legacyFamily: nil)
        }
        if data.count == 84 {
            let b = [UInt8](data), raw = Array(b[0..<20].prefix { $0 != 0 })
            guard !raw.isEmpty, raw.allSatisfy({ (0x20...0x7e).contains($0) }), [0,1,2,8].contains(Int(b[0x18])) else { throw Error.invalidFile }
            let name = String(decoding: raw.prefix(19), as: UTF8.self)
            var points: [CurveLUT.Point] = []
            for i in 0..<18 where b[0x1a + i] != 0xff {
                points.append(.init(x: Float(i) * 255 / 17, y: Float(b[0x1a + i])))
            }
            guard points.count >= 2 else { throw Error.invalidFile }
            let lut = try CurveLUT.make(points)
            let payload = try edit(template: linearTemplate(), name: name, lut: lut)
            return Imported(payload: payload, description: "旧 84 字节结构，已近似转换；类型仅作本地元数据，其他曲线未保留", approximate: true, legacyFamily: Int(b[0x18]))
        }
        throw Error.invalidFile
    }
}
