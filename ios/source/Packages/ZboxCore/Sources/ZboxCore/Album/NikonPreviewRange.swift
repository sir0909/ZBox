import Foundation

/// Bounded Nikon MakerNote PreviewIFD and JPEG MPF parsing. Every result is untrusted
/// until the caller validates and decodes the fetched JPEG; this never reads file bytes.
public enum NikonPreviewRange {
    private struct Entry {
        let tag: UInt16
        let type: UInt16
        let count: UInt32
        let field: Int
    }

    private struct Reader {
        let bytes: [UInt8]
        let base: Int
        let end: Int // exclusive, bounds all metadata reads
        let little: Bool

        init?(_ bytes: [UInt8], base: Int, end: Int) {
            guard base >= 0, end <= bytes.count, base <= end, end - base >= 8 else { return nil }
            if bytes[base] == 0x49 && bytes[base + 1] == 0x49 { little = true }
            else if bytes[base] == 0x4d && bytes[base + 1] == 0x4d { little = false }
            else { return nil }
            self.bytes = bytes; self.base = base; self.end = end
            guard u16(base + 2) == 42 else { return nil }
        }
        func u16(_ p: Int) -> UInt16? {
            guard p >= base, p <= end - 2 else { return nil }
            let a = UInt16(bytes[p]), b = UInt16(bytes[p + 1])
            return little ? a | (b << 8) : (a << 8) | b
        }
        func u32(_ p: Int) -> UInt32? {
            guard p >= base, p <= end - 4 else { return nil }
            var n: UInt32 = 0
            for i in 0..<4 {
                if little { n |= UInt32(bytes[p + i]) << (i * 8) }
                else { n = (n << 8) | UInt32(bytes[p + i]) }
            }
            return n
        }
        func entries(_ relative: UInt32) -> (entries: [Entry], next: UInt32)? {
            guard relative > 0, UInt64(relative) <= UInt64(end - base) else { return nil }
            let pos = base + Int(relative)
            guard let n = u16(pos), n <= 256 else { return nil }
            let count = Int(n)
            guard pos <= end - 2, count <= (end - pos - 2) / 12,
                  pos + 2 + count * 12 <= end - 4 else { return nil }
            var out: [Entry] = []
            for i in 0..<count {
                let e = pos + 2 + i * 12
                guard let tag = u16(e), let type = u16(e + 2), let count = u32(e + 4) else { return nil }
                out.append(Entry(tag: tag, type: type, count: count, field: e + 8))
            }
            guard let next = u32(pos + 2 + count * 12) else { return nil }
            return (out, next)
        }
        func scalar(_ entry: Entry) -> UInt32? {
            guard entry.count == 1 else { return nil }
            if entry.type == 3 { return u16(entry.field).map(UInt32.init) }
            if entry.type == 4 || entry.type == 13 { return u32(entry.field) }
            return nil
        }
        /// Undefined/byte data, either inline or pointed to by a TIFF-relative offset.
        func bytesStart(_ entry: Entry) -> Int? {
            guard entry.type == 1 || entry.type == 7, entry.count > 0 else { return nil }
            let count = UInt64(entry.count)
            let start: Int
            if count <= 4 { start = entry.field }
            else {
                guard let rel = u32(entry.field), UInt64(rel) <= UInt64(end - base) else { return nil }
                start = base + Int(rel)
            }
            guard start >= base, start <= end, count <= UInt64(end - start) else { return nil }
            return start
        }
    }

    public static func largest(in prefix: Data, fileSize: UInt64) -> Range<UInt64>? {
        guard prefix.count >= 8, prefix.count <= EmbeddedJPEG.maxPrefixBytes else { return nil }
        let b = [UInt8](prefix)
        var ranges: [Range<UInt64>] = []
        func addRange(base: Int, offset: UInt32, length: UInt32) {
            let relative = UInt64(offset), count = UInt64(length), origin = UInt64(base)
            guard offset > 0, (5...TIFFPreviewRange.maxPreviewBytes).contains(count),
                  origin <= fileSize, relative <= fileSize - origin else { return }
            let start = origin + relative
            guard count <= fileSize - start else { return }
            ranges.append(start..<(start + count))
        }
        func scanMaker(_ r: Reader) {
            guard let first = r.u32(r.base + 4) else { return }
            var pending = [first], visited = Set<UInt32>()
            while let offset = pending.popLast(), visited.count < 32 {
                guard offset > 0, visited.insert(offset).inserted, let ifd = r.entries(offset) else { continue }
                var jpegOffset: UInt32?, jpegLength: UInt32?
                for e in ifd.entries {
                    guard let value = r.scalar(e) else { continue }
                    switch e.tag {
                    case 0x0011: pending.append(value) // Nikon PreviewIFD
                    case 0x0201: jpegOffset = value
                    case 0x0202: jpegLength = value
                    default: break
                    }
                }
                if let o = jpegOffset, let l = jpegLength { addRange(base: r.base, offset: o, length: l) }
                if ifd.next > 0 { pending.append(ifd.next) }
            }
        }
        func scanExif(_ r: Reader) {
            guard let first = r.u32(r.base + 4) else { return }
            var pending = [first], visited = Set<UInt32>()
            while let offset = pending.popLast(), visited.count < 32 {
                guard offset > 0, visited.insert(offset).inserted, let ifd = r.entries(offset) else { continue }
                for e in ifd.entries {
                    if e.tag == 0x8769 || e.tag == 0x014a, let child = r.scalar(e) { pending.append(child) }
                    guard e.tag == 0x927c, e.count >= 18, let note = r.bytesStart(e),
                          Array(b[note..<(note + 6)]) == [78, 105, 107, 111, 110, 0] else { continue }
                    let noteEnd = note + Int(e.count)
                    if let maker = Reader(b, base: note + 10, end: noteEnd) { scanMaker(maker) }
                }
                if ifd.next > 0 { pending.append(ifd.next) }
            }
        }
        func scanMPF(_ r: Reader) {
            guard let first = r.u32(r.base + 4), let ifd = r.entries(first) else { return }
            for e in ifd.entries where e.tag == 0xb002 {
                guard e.count >= 32, e.count <= 256, e.count % 16 == 0, let start = r.bytesStart(e) else { continue }
                for i in 0..<Int(e.count / 16) {
                    let pos = start + i * 16
                    guard let length = r.u32(pos + 4), let relative = r.u32(pos + 8) else { continue }
                    addRange(base: r.base, offset: relative, length: length)
                }
            }
        }
        if b[0] != 0xff || b[1] != 0xd8 {
            if let raw = Reader(b, base: 0, end: b.count) { scanExif(raw) }
        } else {
            var p = 2
            for _ in 0..<256 {
                guard p <= b.count - 4, b[p] == 0xff else { break }
                let marker = b[p + 1]
                if marker == 0xd9 || marker == 0xda { break }
                let length = Int(b[p + 2]) * 256 + Int(b[p + 3])
                guard length >= 2 else { break }
                let segmentEnd = min(b.count, p + 2 + length)
                if marker == 0xe1, length >= 8, p + 10 <= segmentEnd,
                   Array(b[(p + 4)..<(p + 10)]) == [69, 120, 105, 102, 0, 0],
                   let exif = Reader(b, base: p + 10, end: segmentEnd) { scanExif(exif) }
                if marker == 0xe2, length >= 6, p + 8 <= segmentEnd,
                   Array(b[(p + 4)..<(p + 8)]) == [77, 80, 70, 0],
                   let mpf = Reader(b, base: p + 8, end: segmentEnd) { scanMPF(mpf) }
                guard length <= b.count - p - 2 else { break }
                p += 2 + length
            }
        }
        return ranges.max { ($0.upperBound - $0.lowerBound) < ($1.upperBound - $1.lowerBound) }
    }
}
