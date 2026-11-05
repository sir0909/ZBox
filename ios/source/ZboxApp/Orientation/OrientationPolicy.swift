import SwiftUI
import UIKit

/// Per-window masks prevent a view in one scene from rotating a different scene.
/// iPad multitasking and iOS policies can override or reject geometry requests.
@MainActor
final class OrientationPolicy {
    static let shared = OrientationPolicy()
    private final class Request: NSObject {
        let id = UUID()
        let mask: UIInterfaceOrientationMask
        init(_ mask: UIInterfaceOrientationMask) { self.mask = mask }
    }
    private let requests = NSMapTable<UIWindow, Request>.weakToStrongObjects()
    private let normalMask: UIInterfaceOrientationMask = .allButUpsideDown

    func supportedOrientations(for window: UIWindow?) -> UIInterfaceOrientationMask {
        guard let window, let request = requests.object(forKey: window) else { return normalMask }
        return request.mask
    }

    /// The mask is honored by UIApplicationDelegate in addition to requesting geometry.
    /// Rejection rolls back only this request, not a newer request for the same window.
    func requestLandscape(in window: UIWindow, onFailure: @escaping (String) -> Void) {
        guard let scene = window.windowScene, scene.activationState == .foregroundActive else {
            onFailure("当前窗口不可旋转；请手动旋转设备")
            return
        }
        let request = Request(.landscape)
        requests.setObject(request, forKey: window)
        window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscape)) { [weak self, weak window] error in
            DispatchQueue.main.async {
                guard let self, let window, self.requests.object(forKey: window)?.id == request.id else { return }
                self.requests.removeObject(forKey: window)
                window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
                onFailure("系统未接受横屏请求：\(error.localizedDescription)")
            }
        }
    }

    /// Restore the default mask; request portrait, but iOS has the final decision.
    func release(in window: UIWindow, requestPortrait: Bool) {
        requests.removeObject(forKey: window)
        window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        guard requestPortrait, let scene = window.windowScene,
              scene.activationState == .foregroundActive else { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait)) { _ in }
    }
}

final class ZboxAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        OrientationPolicy.shared.supportedOrientations(for: window)
    }
}

/// Reads the actual hosting window rather than guessing from all active scenes.
struct OrientationWindowReader: UIViewRepresentable {
    @Binding var window: UIWindow?
    func makeUIView(context: Context) -> WindowObserver {
        let view = WindowObserver()
        view.onWindowChange = { value in
            DispatchQueue.main.async { window = value }
        }
        return view
    }
    func updateUIView(_ uiView: WindowObserver, context: Context) {}
    static func dismantleUIView(_ uiView: WindowObserver, coordinator: ()) {
        uiView.onWindowChange = nil
    }
    final class WindowObserver: UIView {
        var onWindowChange: ((UIWindow?) -> Void)?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            onWindowChange?(window)
        }
    }
}
