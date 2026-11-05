import Foundation

/// Bounded TIFF IFD0/IFD1/Exif-IFD preview lookup. Does not parse Nikon MakerNote or MPF.
/// A result is only an untrusted byte range; callers must still validate/decode the bytes.
public enum TIFFPreviewRange {
    public static let maxPreviewBytes: UInt64 = 400 * 1024

    public static func largest(in prefix: Data, fileSize: UInt64) -> Range<UInt64>? {
        let b = [UInt8](prefix)
        guard b.count >= 8, b.count <= EmbeddedJPEG.maxPrefixBytes else { return nil }
        func byte(_ i: Int) -> UInt8? { i >= 0 && i < b.count ? b[i] : nil }
        var base = 0
        if b[0] == 0xff && b[1] == 0xd8 {
            var p = 2; var found: Int?
            for _ in 0..<256 {
                guard p + 4 <= b.count, b[p] == 0xff else { break }
                let marker = b[p + 1]
                if marker == 0xd9 || marker == 0xda { break }
                let length = Int(b[p + 2]) * 256 + Int(b[p + 3])
                guard length >= 2 else { break }
                if marker == 0xe1, length >= 8, p + 10 <= b.count,
                   Array(b[(p + 4)..<(p + 10)]) == [69, 120, 105, 102, 0, 0] {
                    found = p + 10; break
                }
                guard p <= b.count - 2, length <= b.count - p - 2 else { break }
                p += 2 + length
            }
            guard let found else { return nil }; base = found
        }
        guard base + 8 <= b.count else { return nil }
        let little: Bool
        if b[base] == 0x49 && b[base + 1] == 0x49 { little = true }
        else if b[base] == 0x4d && b[base + 1] == 0x4d { little = false }
        else { return nil }
        func u16(_ p: Int) -> UInt16? {
            guard let a = byte(p), let c = byte(p + 1) else { return nil }
            return little ? UInt16(a) | (UInt16(c) << 8) : (UInt16(a) << 8) | UInt16(c)
        }
        func u32(_ p: Int) -> UInt32? {
            guard p >= 0, p <= b.count - 4 else { return nil }
            let x = b[p..<(p + 4)]
            if little { return x.enumerated().reduce(UInt32(0)) { $0 | (UInt32($1.element) << (8 * $1.offset)) } }
            return x.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        }
        guard u16(base + 2) == 42, let first = u32(base + 4), first > 0 else { return nil }
        var todo = [first], visited = Set<UInt32>(), ranges: [Range<UInt64>] = []
        while let offset = todo.popLast(), visited.count < 32 {
            guard offset > 0, visited.insert(offset).inserted else { continue }
            let pos = base + Int(offset)
            guard pos >= base, let n = u16(pos), n <= 256 else { continue }
            let count = Int(n)
            guard pos <= b.count - 2, count <= (b.count - pos - 2) / 12,
                  pos + 2 + count * 12 <= b.count - 4 else { continue }
            var jpegOffset: UInt64?, jpegLength: UInt64?, compression: UInt64?, stripOffset: UInt64?, stripLength: UInt64?
            for i in 0..<count {
                let e = pos + 2 + i * 12
                guard let tag = u16(e), let type = u16(e + 2), u32(e + 4) == 1 else { continue }
                let val: UInt64?
                if type == 3 { val = u16(e + 8).map(UInt64.init) }
                else if type == 4 || type == 13 { val = u32(e + 8).map(UInt64.init) }
                else { val = nil }
                guard let val else { continue }
                switch tag {
                case 0x0201: jpegOffset = val
                case 0x0202: jpegLength = val
                case 0x0103: compression = val
                case 0x0111: stripOffset = val
                case 0x0117: stripLength = val
                case 0x8769, 0x014a: if val <= UInt64(UInt32.max) { todo.append(UInt32(val)) }
                default: break
                }
            }
            func add(_ offset: UInt64?, _ length: UInt64?) {
                guard let offset, let length, offset > 0, (5...maxPreviewBytes).contains(length),
                      UInt64(base) <= fileSize, offset <= fileSize - UInt64(base) else { return }
                let start = UInt64(base) + offset
                guard length <= fileSize - start else { return }
                ranges.append(start..<(start + length))
            }
            add(jpegOffset, jpegLength)
            if compression == 6 { add(stripOffset, stripLength) }
            if let next = u32(pos + 2 + count * 12), next > 0 { todo.append(next) }
        }
        return ranges.max { ($0.upperBound - $0.lowerBound) < ($1.upperBound - $1.lowerBound) }
    }
}
