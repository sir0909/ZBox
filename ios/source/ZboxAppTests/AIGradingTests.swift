import XCTest
@testable import Zbox

final class AIGradingTests: XCTestCase {
    func testEndpointAndRequestDoNotUseQueryOrHTTP() throws {
        XCTAssertThrowsError(try AIKeyStore.validatedBase("http://example.com/v1"))
        XCTAssertThrowsError(try AIKeyStore.validatedBase("https://example.com/v1?key=secret"))
        XCTAssertThrowsError(try AIKeyStore.validatedBase("https://user:pass@example.com/v1"))
        let base = try AIKeyStore.validatedBase("https://example.com/v1")
        let request = try AIGrading.request(base: base, model: "vision", key: "test-secret", jpeg: Data([1,2,3]), hint: "柔和")
        XCTAssertEqual(request.url?.absoluteString, "https://example.com/v1/chat/completions")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-secret")
        XCTAssertFalse(request.url!.absoluteString.contains("test-secret"))
        let body = try XCTUnwrap(request.httpBody)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(object["model"] as? String, "vision")
        XCTAssertThrowsError(try AIGrading.request(base: base, model: "vision", key: "x", jpeg: Data(), hint: ""))
    }
}
