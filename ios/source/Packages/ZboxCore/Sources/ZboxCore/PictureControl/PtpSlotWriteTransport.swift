import Foundation

/// Internal, test-only adapter. No application write entry point uses this type.
/// A production caller must hold a process-wide operation gate for the ENTIRE workflow,
/// and must invalidate shouldContinue permanently upon backgrounding/disconnection.
struct PtpSlotWriteTransport: SlotWriteTransport {
    let session: PtpIpSession
    let shouldContinue: @Sendable () async -> Bool
    enum Failure: Error { case cancelled, badIdentity }

    func cameraID() async throws -> String {
        guard await shouldContinue() else { throw Failure.cancelled }
        guard let info = await session.connectionInfo, info.cameraGuid.count == 16,
              info.cameraGuid.contains(where: { $0 != 0 }) else { throw Failure.badIdentity }
        return info.cameraGuid.map { String(format: "%02x", $0) }.joined()
    }
    func read(slot: Int) async throws -> Data {
        let identity = try await cameraID()
        let data = try await session.fetchPictureControlRaw(index: slot)
        guard try await cameraID() == identity else { throw Failure.badIdentity }
        return data
    }
    func writeOnce(slot: Int, payload: Data, expectedCameraID: String, expectedOriginal: Data) async throws {
        guard try await cameraID() == expectedCameraID,
              let info = await session.connectionInfo else { throw Failure.badIdentity }
        try await session.experimentalSetPictureControlOnce(index: slot, expectedGUID: info.cameraGuid,
            original: expectedOriginal, target: payload, shouldContinue: shouldContinue)
    }
}
