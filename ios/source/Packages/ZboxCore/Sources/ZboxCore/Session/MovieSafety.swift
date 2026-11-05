import Foundation

/// Persist the camera GUID before a start packet can be sent. Never infer stopped from
/// disconnection, app suspension or a start/stop acknowledgement alone.
public enum MoviePhase: String, Sendable {
    case idle, preparing, startMayHaveBeenSent, stopMayHaveBeenSent, awaitingCameraCheck
}
public enum MovieSafetyError: Error, Equatable, Sendable {
    case unsupported, cancelled, cameraNotReady, cameraModeUnknownOrNotMovie, prohibitedOrUnknown
}

public enum MoviePrerequisites {
    /// No automatic mode switching: both mode bytes and the prohibit mask must be read.
    public static func validate(application: Data, selector: Data, prohibit: Data) throws {
        guard application.count == 1, selector.count == 1, application.first == 1, selector.first == 1 else {
            throw MovieSafetyError.cameraModeUnknownOrNotMovie
        }
        guard prohibit.count == 4, prohibit.readLE(UInt32.self, at: 0) == 0 else {
            throw MovieSafetyError.prohibitedOrUnknown
        }
    }
}
