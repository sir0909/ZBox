import XCTest
@testable import ZboxCore

final class CameraOperationPolicyTests: XCTestCase {
    func testDisconnectedAndEveryBusyOperationBlockEveryRequest() {
        let disconnected = CameraOperationPolicy(connected: false, pairingPrepared: false, busy: [], liveView: .idle)
        for request in CameraOperationPolicy.Request.allCases { XCTAssertFalse(disconnected.permits(request)) }
        for busy in CameraOperationPolicy.Operation.allCases {
            let state = CameraOperationPolicy(connected: true, pairingPrepared: false, busy: [busy], liveView: .idle)
            for request in CameraOperationPolicy.Request.allCases {
                XCTAssertFalse(state.permits(request), "busy=\(busy) request=\(request)")
            }
        }
    }
    func testPreparedRegistrationBlocksEverythingExceptConfirmation() {
        let state = CameraOperationPolicy(connected: true, pairingPrepared: true, busy: [], liveView: .idle)
        for request in CameraOperationPolicy.Request.allCases {
            XCTAssertEqual(state.permits(request), request == .confirmRegistration)
        }
        let normal = CameraOperationPolicy(connected: true, pairingPrepared: false, busy: [], liveView: .idle)
        XCTAssertFalse(normal.permits(.confirmRegistration))
        XCTAssertTrue(normal.permits(.prepareRegistration))
    }
    func testLiveViewOnlyPermitsCaptureWhileStreaming() {
        let streaming = CameraOperationPolicy(connected: true, pairingPrepared: false, busy: [], liveView: .streaming)
        let transition = CameraOperationPolicy(connected: true, pairingPrepared: false, busy: [], liveView: .transitioning)
        for request in CameraOperationPolicy.Request.allCases {
            XCTAssertEqual(streaming.permits(request), request == .capture)
            XCTAssertFalse(transition.permits(request))
        }
        let pairing = CameraOperationPolicy(connected: true, pairingPrepared: true, busy: [], liveView: .streaming)
        XCTAssertFalse(pairing.permits(.capture))
    }
}
