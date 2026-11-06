import Foundation
import Security
import ZboxCore

/// Credentials are bound to the entire configured base URL, never shared with another provider.
enum AIKeyStore {
    enum Failure: Error { case keychain(OSStatus), invalid }
    static func validatedBase(_ value: String) throws -> URL {
        guard value.utf8.count <= 512, let components = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme?.lowercased() == "https", let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil, components.query == nil, components.fragment == nil,
              let url = components.url else { throw Failure.invalid }
        return url
    }
    private static func query(_ base: URL) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "com.zbox.ai.grading",
         kSecAttrAccount as String: base.absoluteString]
    }
    static func save(_ key: String, base: URL) throws {
        let key = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty, key.utf8.count <= 4096, !key.contains("\n"), !key.contains("\r") else { throw Failure.invalid }
        let data = Data(key.utf8), q = query(base)
        let status = SecItemUpdate(q as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var add = q; add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            let added = SecItemAdd(add as CFDictionary, nil)
            guard added == errSecSuccess else { throw Failure.keychain(added) }
        } else if status != errSecSuccess { throw Failure.keychain(status) }
    }
    static func read(base: URL) throws -> String {
        var q = query(base); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var output: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &output)
        guard status == errSecSuccess, let data = output as? Data, let value = String(data: data, encoding: .utf8), !value.isEmpty else { throw Failure.keychain(status) }
        return value
    }
    static func delete(base: URL) throws {
        let status = SecItemDelete(query(base) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw Failure.keychain(status) }
    }
}

/// Redirects are forbidden, so Authorization and image cannot be forwarded to another endpoint.
final class NoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

enum AIGrading {
    enum Failure: Error, Equatable { case invalid, http(Int), responseTooLarge, missingContent }
    private static let prompt = """
    Analyze the reference image's tonal character. Return ONLY one JSON object, no markdown:
    {"preset_name":"AI_CURVE","tone_curve_points":[{"x":0,"y":0},{"x":128,"y":128},{"x":255,"y":255}],"preview_adjustments":{"brightness":0,"contrast":0,"saturation":0,"hue":0},"style_analysis":"brief Chinese description"}.
    Name: 1-19 printable ASCII characters. 2-8 points, x strictly increasing from 0 to 255, y non-decreasing, both 0-255. Optional preview_adjustments: brightness -2 to 2; contrast, saturation and hue each -3 to 3. These values are rough phone-preview suggestions only, never camera settings. Do not claim exact reproduction or control of a camera.
    """
    static func request(base: URL, model: String, key: String, jpeg: Data, hint: String) throws -> URLRequest {
        guard (try? AIKeyStore.validatedBase(base.absoluteString)) != nil,
              !model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, model.utf8.count <= 160,
              !key.isEmpty, key.utf8.count <= 4096, !key.contains("\n"), !key.contains("\r"),
              jpeg.count <= 2_000_000, !jpeg.isEmpty, hint.utf8.count <= 1000 else { throw Failure.invalid }
        let url = base.appendingPathComponent("chat").appendingPathComponent("completions")
        let user: [[String: Any]] = [
            ["type": "text", "text": "参考要求：\(hint)"],
            ["type": "image_url", "image_url": ["url": "data:image/jpeg;base64,\(jpeg.base64EncodedString())", "detail": "low"]]
        ]
        let body: [String: Any] = ["model": model, "messages": [
            ["role": "system", "content": prompt], ["role": "user", "content": user]
        ], "max_tokens": 800, "stream": false]
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 90)
        request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return request
    }
    private struct Envelope: Decodable {
        struct Choice: Decodable { struct Message: Decodable { let content: String }; let message: Message }
        let choices: [Choice]
    }
    static func analyze(base: URL, model: String, key: String, jpeg: Data, hint: String) async throws -> AIRecipe {
        let req = try request(base: base, model: model, key: key, jpeg: jpeg, hint: hint)
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil; config.urlCredentialStorage = nil; config.httpCookieStorage = nil
        config.timeoutIntervalForRequest = 90; config.timeoutIntervalForResource = 120
        let session = URLSession(configuration: config, delegate: NoRedirects(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (stream, response) = try await session.bytes(for: req)
        return try await collect(stream, response: response)
    }

    /// Bounds streaming before decoding; accepts any async byte sequence for offline tests.
    static func collect<Bytes: AsyncSequence>(_ stream: Bytes, response: URLResponse) async throws -> AIRecipe where Bytes.Element == UInt8 {
        guard let http = response as? HTTPURLResponse else { throw Failure.invalid }
        guard (200...299).contains(http.statusCode) else { throw Failure.http(http.statusCode) }
        if http.expectedContentLength > 262_144 { throw Failure.responseTooLarge }
        var data = Data(); data.reserveCapacity(8192)
        for try await byte in stream {
            try Task.checkCancellation()
            guard data.count < 262_144 else { throw Failure.responseTooLarge }
            data.append(byte)
        }
        let responseBody = try JSONDecoder().decode(Envelope.self, from: data)
        guard let content = responseBody.choices.first?.message.content, let result = content.data(using: .utf8) else { throw Failure.missingContent }
        return try AIRecipe.parse(result)
    }
}
