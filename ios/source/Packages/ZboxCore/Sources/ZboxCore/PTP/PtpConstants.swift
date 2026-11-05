import Foundation

/// PTP 容器类型(与安卓 `Ptp.TYPE_*` 数值一致)。
public enum PtpContainerType: UInt16, Sendable {
    case command = 1
    case data = 2
    case response = 3
    case event = 4
}

/// 标准与尼康私有操作码。每个码都需要在真机上独立验证,定义存在不代表机型支持。
public enum PtpOpCode {
    public static let getDeviceInfo: UInt16 = 0x1001
    public static let openSession: UInt16 = 0x1002
    public static let closeSession: UInt16 = 0x1003
    public static let getStorageIDs: UInt16 = 0x1004
    public static let getObjectHandles: UInt16 = 0x1007
    public static let getObjectInfo: UInt16 = 0x1008
    public static let getObject: UInt16 = 0x1009
    public static let getThumb: UInt16 = 0x100A
    public static let initiateCapture: UInt16 = 0x100E
    public static let getDevicePropDesc: UInt16 = 0x1014
    public static let getDevicePropValue: UInt16 = 0x1015
    public static let setDevicePropValue: UInt16 = 0x1016
    public static let nikonObjectSize: UInt16 = 0x9421
    public static let nikonObjectsMetadata: UInt16 = 0x9434
    public static let nikonPartialObjectEx: UInt16 = 0x9431
    public static let nikonAutofocus: UInt16 = 0x90C1
    public static let nikonDeviceReady: UInt16 = 0x90C8
    public static let nikonGetPictureControl: UInt16 = 0x90CC
    static let nikonSetPictureControl: UInt16 = 0x90CD
    public static let nikonStartLiveView: UInt16 = 0x9201
    public static let nikonEndLiveView: UInt16 = 0x9202
    public static let nikonChangeAfArea: UInt16 = 0x9205
    public static let nikonStartMovie: UInt16 = 0x920A
    public static let nikonStopMovie: UInt16 = 0x920B
    public static let nikonCaptureToMedia: UInt16 = 0x9207
    public static let nikonGetLiveViewImage: UInt16 = 0x9203
    public static let nikonGetPairingCode: UInt16 = 0x952B
    public static let nikonConfirmPairing: UInt16 = 0x935A
}

/// 响应码。
public enum PtpResponseCode {
    public static let ok: UInt16 = 0x2001
    public static let generalError: UInt16 = 0x2002
    public static let sessionNotOpen: UInt16 = 0x2003
    public static let operationNotSupported: UInt16 = 0x2005
    public static let deviceBusy: UInt16 = 0x2019
    public static let sessionAlreadyOpen: UInt16 = 0x201E
    public static let transactionCancelled: UInt16 = 0x201F

    public static func name(_ code: UInt16) -> String {
        switch code {
        case ok: return "OK"
        case generalError: return "GeneralError"
        case sessionNotOpen: return "SessionNotOpen"
        case operationNotSupported: return "OperationNotSupported"
        case deviceBusy: return "DeviceBusy"
        case sessionAlreadyOpen: return "SessionAlreadyOpen"
        case transactionCancelled: return "TransactionCancelled"
        default: return String(format: "0x%04X", code)
        }
    }
}

public enum PtpLimits {
    /// 与安卓实现一致:单个容器最大 32 MiB,超出视为协议错误。
    public static let maxContainerLength: UInt32 = 32 * 1024 * 1024
}

/// 协议层错误,保留原始数值以便写入诊断日志。
public enum PtpError: Error, Equatable, Sendable {
    case truncated(expected: Int, actual: Int)
    case invalidLength(UInt32)
    case invalidGuidLength(Int)
    case unexpectedPacket(UInt32)
}
