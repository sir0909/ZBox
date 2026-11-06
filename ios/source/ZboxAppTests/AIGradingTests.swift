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
    private func response(_ status: Int, headers: [String: String] = [:]) -> HTTPURLResponse {
        HTTPURLResponse(url: URL(string: "https://example.com/v1/chat/completions")!, statusCode: status,
                        httpVersion: "HTTP/1.1", headerFields: headers)!
    }
    private func stream(_ data: Data) -> AsyncStream<UInt8> {
        AsyncStream { continuation in
            for byte in data { continuation.yield(byte) }
            continuation.finish()
        }
    }
    func testOfflineStreamingEnvelopeAndInvalidResponse() async throws {
        let result = """
        {"preset_name":"AI_CURVE","tone_curve_points":[{"x":0,"y":0},{"x":255,"y":255}],"style_analysis":"ok","preview_adjustments":{"brightness":1,"contrast":0,"saturation":-1,"hue":2}}
        """
        let envelope = try JSONSerialization.data(withJSONObject: ["choices": [["message": ["content": result]]]])
        let recipe = try await AIGrading.collect(stream(envelope), response: response(200))
        XCTAssertEqual(recipe.name, "AI_CURVE")
        XCTAssertEqual(recipe.previewSuggestion?.hue, 2)
        do {
            _ = try await AIGrading.collect(stream(Data()), response: response(401))
            XCTFail("expected HTTP failure")
        } catch { XCTAssertEqual(error as? AIGrading.Failure, .http(401)) }
        do {
            _ = try await AIGrading.collect(stream(Data()), response: response(200, headers: ["Content-Length": "262145"]))
            XCTFail("expected size failure")
        } catch { XCTAssertEqual(error as? AIGrading.Failure, .responseTooLarge) }
        do {
            _ = try await AIGrading.collect(stream(Data(repeating: 65, count: 262_145)), response: response(200))
            XCTFail("expected streamed size failure")
        } catch { XCTAssertEqual(error as? AIGrading.Failure, .responseTooLarge) }
        let emptyChoices = Data("{\"choices\":[]}".utf8)
        do {
            _ = try await AIGrading.collect(stream(emptyChoices), response: response(200))
            XCTFail("expected missing content")
        } catch { XCTAssertEqual(error as? AIGrading.Failure, .missingContent) }
    }
    func testUnexpectedHTMLAndSafeProviderErrors() async throws {
        let html = Data("<html><title>verification</title></html>".utf8)
        do {
            _ = try await AIGrading.collect(stream(html), response: response(200, headers: ["Content-Type": "text/html; charset=utf-8"]))
            XCTFail("expected HTML rejection")
        } catch {
            XCTAssertEqual(error as? AIGrading.Failure, .unexpectedHTML)
            XCTAssertTrue(AIGrading.failureMessage(error).contains("安全验证"))
            XCTAssertFalse(AIGrading.failureMessage(error).contains("verification"))
        }
        XCTAssertTrue(AIGrading.failureMessage(AIGrading.Failure.http(401)).contains("401"))
        XCTAssertTrue(AIGrading.failureMessage(AIGrading.Failure.http(429)).contains("429"))
        XCTAssertTrue(AIGrading.failureMessage(URLError(.timedOut)).contains("可能已收到图片"))
    }

    func testRedirectDelegateRefusesForwarding() {
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let original = URL(string: "https://example.com/v1/chat/completions")!
        let task = session.dataTask(with: original)
        let destination = URLRequest(url: URL(string: "https://elsewhere.example/collect")!)
        let redirect = response(302, headers: ["Location": "https://elsewhere.example/collect"])
        var called = false
        NoRedirects().urlSession(session, task: task, willPerformHTTPRedirection: redirect, newRequest: destination) { next in
            called = true
            XCTAssertNil(next)
        }
        XCTAssertTrue(called)
    }
    func testRequestRejectsUnsafeBaseAndHeaderValues() throws {
        let image = Data([1])
        XCTAssertThrowsError(try AIGrading.request(base: URL(string: "https://user:pass@example.com/v1")!, model: "v", key: "k", jpeg: image, hint: ""))
        XCTAssertThrowsError(try AIGrading.request(base: URL(string: "https://example.com/v1?x=1")!, model: "v", key: "k", jpeg: image, hint: ""))
        XCTAssertThrowsError(try AIGrading.request(base: URL(string: "https://example.com/v1")!, model: "v", key: "secret\nother", jpeg: image, hint: ""))
    }

    func testCompleteOfflineRequestAndValidation() async throws {
        let base = URL(string: "https://camera-ai.invalid/success")!
        let result = try await AIGrading.analyzeForTesting(base: base, model: "offline-model", key: "offline-test-key",
                                                            jpeg: Data([1, 2, 3]), hint: "test", protocolClass: OfflineAIProtocol.self)
        XCTAssertEqual(result.name, "MOCK_AI")
        XCTAssertEqual(result.previewSuggestion?.saturation, 1)
        do {
            _ = try await AIGrading.analyzeForTesting(base: URL(string: "https://camera-ai.invalid/unauthorized")!,
                                                       model: "offline-model", key: "offline-test-key", jpeg: Data([1]), hint: "",
                                                       protocolClass: OfflineAIProtocol.self)
            XCTFail("expected unauthorized")
        } catch { XCTAssertEqual(error as? AIGrading.Failure, .http(401)) }
        do {
            _ = try await AIGrading.analyzeForTesting(base: URL(string: "https://camera-ai.invalid/bad")!,
                                                       model: "offline-model", key: "offline-test-key", jpeg: Data([1]), hint: "",
                                                       protocolClass: OfflineAIProtocol.self)
            XCTFail("expected untrusted output rejection")
        } catch { XCTAssertFalse(error is AIGrading.Failure) }
    }

}


/// A deterministic in-process provider. The .invalid domain cannot reach a real provider.
private final class OfflineAIProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "camera-ai.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url, request.httpMethod == "POST",
              request.value(forHTTPHeaderField: "Authorization") == "Bearer offline-test-key" else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return
        }
        let status = url.path.hasPrefix("/unauthorized/") ? 401 : 200
        let brightness = url.path.hasPrefix("/bad/") ? 9 : 1
        let content = "{\"preset_name\":\"MOCK_AI\",\"tone_curve_points\":[{\"x\":0,\"y\":0},{\"x\":255,\"y\":255}],\"style_analysis\":\"offline\",\"preview_adjustments\":{\"brightness\":\(brightness),\"contrast\":0,\"saturation\":1,\"hue\":0}}"
        guard let body = try? JSONSerialization.data(withJSONObject: ["choices": [["message": ["content": content]]]]),
              let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"]) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
