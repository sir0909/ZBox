import UIKit
import XCTest
@testable import Zbox

@MainActor
final class OrientationPolicyTests: XCTestCase {
    func testUnattachedWindowIsRejectedWithoutChangingDefaultMask() {
        let window = UIWindow(frame: .zero)
        let policy = OrientationPolicy.shared
        var error: String?
        policy.requestLandscape(in: window) { error = $0 }
        XCTAssertNotNil(error)
        XCTAssertEqual(policy.supportedOrientations(for: window), .allButUpsideDown)
        policy.release(in: window, requestPortrait: false)
        XCTAssertEqual(policy.supportedOrientations(for: window), .allButUpsideDown)
    }
}
