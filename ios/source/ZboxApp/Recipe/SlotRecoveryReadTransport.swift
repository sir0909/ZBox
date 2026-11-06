import Foundation
import ZboxCore

/// App adapter deliberately exposes reads only: recovery cannot send any write command.
struct SlotRecoveryReadTransport: SlotWriteTransport {
    let session: PtpIpSession
    let allowed: @Sendable () async -> Bool
    enum Failure: Error { case cancelled, invalidIdentity, writeDisabled }

    func cameraID() async throws -> String {
        guard await allowed() else { throw Failure.cancelled }
        guard let info = await session.connectionInfo, info.cameraGuid.count == 16,
              info.cameraGuid.contains(where: { $0 != 0 }) else { throw Failure.invalidIdentity }
        guard await allowed() else { throw Failure.cancelled }
        return info.cameraGuid.map { String(format: "%02x", $0) }.joined()
    }
    func read(slot: Int) async throws -> Data {
        let before = try await cameraID()
        let data = try await session.fetchPictureControlRaw(index: slot)
        guard try await cameraID() == before else { throw Failure.invalidIdentity }
        return data
    }
    func writeOnce(slot: Int, payload: Data, expectedCameraID: String, expectedOriginal: Data) async throws {
        throw Failure.writeDisabled
    }
}
