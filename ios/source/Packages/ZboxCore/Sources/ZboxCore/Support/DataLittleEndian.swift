import Foundation

/// 小端序读写工具(PTP 与 PTP/IP 全部字段均为小端)。
extension Data {
    mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
        var v = value.littleEndian
        for _ in 0..<MemoryLayout<T>.size {
            append(UInt8(truncatingIfNeeded: v))
            v >>= 8
        }
    }

    /// 以 `offset`(相对于 Data 的逻辑起点)读取小端整数。调用方需保证长度足够。
    func readLE<T: FixedWidthInteger>(_ type: T.Type, at offset: Int) -> T {
        var result: T = 0
        for i in 0..<MemoryLayout<T>.size {
            result |= T(truncatingIfNeeded: self[startIndex + offset + i]) << (8 * i)
        }
        return result
    }

    func slice(at offset: Int, count: Int) -> Data {
        Data(self[(startIndex + offset)..<(startIndex + offset + count)])
    }
}
