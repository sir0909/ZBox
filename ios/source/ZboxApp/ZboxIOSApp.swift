import SwiftUI

@main
struct ZboxIOSApp: App {
    @UIApplicationDelegateAdaptor(ZboxAppDelegate.self) private var appDelegate
    var body: some Scene {
        WindowGroup {
            AppRootView()
        }
    }
}
