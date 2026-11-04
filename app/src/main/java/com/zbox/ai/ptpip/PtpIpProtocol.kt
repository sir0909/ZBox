// Adapted from Fluoro-Antimonic-Acid/Shotdeck (GPL-3.0-or-later), Copyright (C) 2026 Shotdeck contributors.
// Adapted and integrated with the Zbox PTP/IP transport.
package com.zbox.ai.ptpip

/**
 * Wi-Fi PTP/IP protocol constants.
 *
 * Sources: the PTP/IP packet layout used by ZENCHE's Android implementation
 * (commit a99a0d2, `native/android/app/src/main/java/com/tauber/nikonlink/PtpIpCamera.java`)
 * and libgphoto2's Nikon operation set. Each operation still needs independent
 * on-camera validation; a defined operation code does not imply device support.
 */
internal object PtpIp {

    // ---- packet types (uint32, little-endian, second word of every packet) ----
    const val PACKET_INIT_COMMAND_REQUEST = 1
    const val PACKET_INIT_COMMAND_ACK = 2
    const val PACKET_INIT_EVENT_REQUEST = 3
    const val PACKET_INIT_EVENT_ACK = 4
    const val PACKET_INIT_FAIL = 5
    const val PACKET_OPERATION_REQUEST = 6
    const val PACKET_OPERATION_RESPONSE = 7
    const val PACKET_EVENT = 8
    const val PACKET_START_DATA = 9
    const val PACKET_DATA = 10
    const val PACKET_CANCEL = 11
    const val PACKET_END_DATA = 12
    const val PACKET_PROBE_REQUEST = 13
    const val PACKET_PROBE_RESPONSE = 14

    /** PTP/IP protocol version announced in the init command request. */
    const val PROTOCOL_VERSION = 0x0001_0000L

    // PTP/IP InitFail reason values: responder rejects initiator, busy, unspecified.
    const val INIT_FAIL_REJECTED = 0x00000001
    const val INIT_FAIL_BUSY = 0x00000002
    const val INIT_FAIL_UNSPECIFIED = 0x00000003

    /** Default TCP port for both the command and the event channel. */
    const val DEFAULT_PORT = 15740

    const val HEADER_BYTES = 8

    /** Data phase markers in an operation request/response. */
    const val DATA_PHASE_NONE = 0x0000_0001
    // PTP/IP uses 1 for both no-data and data-in requests; 2 means data-out.
    const val DATA_PHASE_IN = 0x0000_0001
    const val DATA_PHASE_OUT = 0x0000_0002

    /** GetObjectHandles: "all formats", "all handles". */
    const val FORMAT_ALL = 0x0000_0000
    const val HANDLE_ALL = 0xFFFF_FFFFL
    const val STORAGE_ALL = 0xFFFF_FFFFL

    // ---- operation codes ----
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_STORAGE_INFO = 0x1005
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_GET_OBJECT = 0x1009
    const val OP_GET_THUMB = 0x100A
    const val OP_INITIATE_CAPTURE = 0x100E

    // Nikon private operations used for direct SD-card reads in Wi-Fi/STA mode.
    const val OP_NIKON_GET_OBJECT_SIZE = 0x9421
    const val OP_NIKON_GET_PARTIAL_OBJECT_EX = 0x9431
    const val OP_NIKON_GET_OBJECTS_METADATA = 0x9434
    const val OP_NIKON_GET_PARTIAL_IMAGE = 0x9530
    const val OP_GET_DEVICE_PROP_DESC = 0x1014
    const val OP_GET_DEVICE_PROP_VALUE = 0x1015
    const val OP_SET_DEVICE_PROP_VALUE = 0x1016

    // Nikon Z PTP/IP host-pairing handshake (only use when advertised by DeviceInfo).
    const val OP_NIKON_GET_PAIRING_CODE = 0x952B
    const val OP_NIKON_CONFIRM_PAIRING = 0x935A

    // Nikon vendor extensions (libgphoto2 nikon.c)
    const val OP_NIKON_GET_EVENT = 0x90C7
    const val OP_NIKON_DEVICE_READY = 0x90C8
    const val OP_NIKON_START_LIVE_VIEW = 0x9201
    const val OP_NIKON_END_LIVE_VIEW = 0x9202
    const val OP_NIKON_GET_LIVE_VIEW_IMAGE = 0x9203
    const val OP_NIKON_CHANGE_AF_AREA = 0x9205

    // ---- object formats ----
    const val FORMAT_ASSOCIATION = 0x3001
    const val FORMAT_NEF = 0x3800
    const val FORMAT_JPEG = 0x3801
    const val FORMAT_MOV = 0x3802

    // ---- response codes ----
    const val RC_OK = 0x2001
    const val RC_GENERAL_ERROR = 0x2002
    const val RC_SESSION_NOT_OPEN = 0x2003
    const val RC_INVALID_TRANSACTION_ID = 0x2004
    const val RC_OPERATION_NOT_SUPPORTED = 0x2005
    const val RC_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RC_INCOMPLETE_TRANSFER = 0x2007
    const val RC_INVALID_STORAGE_ID = 0x2008
    const val RC_INVALID_OBJECT_HANDLE = 0x2009
    const val RC_DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val RC_INVALID_DEVICE_PROP_VALUE = 0x200B
    const val RC_INVALID_PARAMETER = 0x200C
    const val RC_DEVICE_BUSY = 0x2019
    const val RC_SESSION_ALREADY_OPEN = 0x201E
    const val RC_TRANSACTION_CANCELLED = 0x201F

    // Nikon-specific device-ready results (libgphoto2 nikon_wait_busy)
    const val RC_NIKON_BULB_RELEASE_BUSY = 0xA200
    const val RC_NIKON_SILENT_RELEASE_BUSY = 0xA201
    const val RC_NIKON_NOT_LIVE_VIEW = 0xA00B

    // ---- device property codes ----
    const val PROP_BATTERY_LEVEL = 0x5001
    const val PROP_F_NUMBER = 0x5007
    const val PROP_EXPOSURE_INDEX = 0x500F
    const val PROP_EXPOSURE_BIAS = 0x5010
    const val PROP_EXPOSURE_TIME = 0x500D
    const val PROP_EXPOSURE_PROGRAM_MODE = 0x500E
    const val PROP_NIKON_CAMERA_ORIENTATION = 0xD10E
    const val PROP_NIKON_ISO_EX = 0xD0B4
    const val PROP_NIKON_EXPOSURE_TIME = 0xD100

    const val EVENT_DEVICE_PROP_CHANGED = 0x4006

    /** Human-readable names for logs. An unknown code is reported as its hex value. */
    fun operationName(op: Int): String = when (op) {
        OP_GET_DEVICE_INFO -> "GetDeviceInfo"
        OP_OPEN_SESSION -> "OpenSession"
        OP_CLOSE_SESSION -> "CloseSession"
        OP_GET_STORAGE_IDS -> "GetStorageIDs"
        OP_GET_STORAGE_INFO -> "GetStorageInfo"
        OP_GET_OBJECT_HANDLES -> "GetObjectHandles"
        OP_GET_OBJECT_INFO -> "GetObjectInfo"
        OP_GET_OBJECT -> "GetObject"
        OP_GET_THUMB -> "GetThumb"
        OP_INITIATE_CAPTURE -> "InitiateCapture"
        OP_NIKON_GET_OBJECT_SIZE -> "NikonGetObjectSize"
        OP_NIKON_GET_PARTIAL_OBJECT_EX -> "NikonGetPartialObjectEx"
        OP_NIKON_GET_OBJECTS_METADATA -> "NikonGetObjectsMetaData"
        OP_NIKON_GET_PARTIAL_IMAGE -> "NikonGetPartialImage"
        OP_GET_DEVICE_PROP_DESC -> "GetDevicePropDesc"
        OP_GET_DEVICE_PROP_VALUE -> "GetDevicePropValue"
        OP_SET_DEVICE_PROP_VALUE -> "SetDevicePropValue"
        OP_NIKON_GET_EVENT -> "NikonGetEvent"
        OP_NIKON_DEVICE_READY -> "NikonDeviceReady"
        OP_NIKON_START_LIVE_VIEW -> "NikonStartLiveView"
        OP_NIKON_END_LIVE_VIEW -> "NikonEndLiveView"
        OP_NIKON_GET_LIVE_VIEW_IMAGE -> "NikonGetLiveViewImage"
        OP_NIKON_CHANGE_AF_AREA -> "NikonChangeAfArea"
        OP_NIKON_GET_PAIRING_CODE -> "NikonGetPairingCode"
        OP_NIKON_CONFIRM_PAIRING -> "NikonConfirmPairing"
        else -> "0x%04X".format(op)
    }

    fun responseName(code: Int): String = when (code) {
        RC_OK -> "OK"
        RC_GENERAL_ERROR -> "GeneralError"
        RC_SESSION_NOT_OPEN -> "SessionNotOpen"
        RC_INVALID_TRANSACTION_ID -> "InvalidTransactionID"
        RC_OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        RC_PARAMETER_NOT_SUPPORTED -> "ParameterNotSupported"
        RC_INCOMPLETE_TRANSFER -> "IncompleteTransfer"
        RC_INVALID_STORAGE_ID -> "InvalidStorageID"
        RC_INVALID_OBJECT_HANDLE -> "InvalidObjectHandle"
        RC_DEVICE_PROP_NOT_SUPPORTED -> "DevicePropNotSupported"
        RC_INVALID_DEVICE_PROP_VALUE -> "InvalidDevicePropValue"
        RC_INVALID_PARAMETER -> "InvalidParameter"
        RC_DEVICE_BUSY -> "DeviceBusy"
        RC_SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
        RC_TRANSACTION_CANCELLED -> "TransactionCancelled"
        RC_NIKON_BULB_RELEASE_BUSY -> "NikonBulbBusy"
        RC_NIKON_SILENT_RELEASE_BUSY -> "NikonSilentReleaseBusy"
        RC_NIKON_NOT_LIVE_VIEW -> "NikonNotLiveView"
        else -> "0x%04X".format(code)
    }
}

/** Every failure carries the raw code so reports keep the original evidence. */
class PtpIpException(
    val stage: String,
    val code: Int? = null,
    val opName: String? = null,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Single line suitable for the exported diagnostic log. */
    fun report(): String = buildString {
        append(stage)
        opName?.let { append(" / ").append(it) }
        code?.let { append(" / 0x%04X ").append(PtpIp.responseName(it)) }
        append(" / ").append(message)
    }.replace("  ", " ")
}
