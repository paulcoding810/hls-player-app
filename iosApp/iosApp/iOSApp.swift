import UIKit
import ComposeApp

/**
 * The Compose view controller is the window's root, with no SwiftUI around it:
 * Compose attaches iOS's swipe-from-the-edge back gesture to the view under the
 * window, and a SwiftUI host in between kept the gesture from ever firing.
 */
@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        configurationForConnecting session: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: session.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

class SceneDelegate: UIResponder, UIWindowSceneDelegate, UIGestureRecognizerDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = MainViewControllerKt.MainViewController()
        // Dark only: the window behind Compose is the surface color, and the status bar text is light.
        window.backgroundColor = UIColor(red: 0x0B / 255, green: 0x0B / 255, blue: 0x0C / 255, alpha: 1)
        window.overrideUserInterfaceStyle = .dark
        self.window = window
        window.makeKeyAndVisible()
        attachBackSwipe(to: window)

        if let link = options.urlContexts.first?.url { open(link) }
        #if DEBUG
        // Lets a simulator run be driven without tapping iOS's "Open in…?" prompt:
        // SIMCTL_CHILD_HLS_OPEN_URL=<manifest> xcrun simctl launch … — the same path a link takes.
        if let target = ProcessInfo.processInfo.environment["HLS_OPEN_URL"] {
            MainViewControllerKt.openLink(url: target)
        }
        #endif
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        if let link = URLContexts.first?.url { open(link) }
    }

    /// How far in from the left edge a back swipe may start.
    private let edgeWidth: CGFloat = 24

    /// iOS has no back button: a swipe in from the left edge is back, as in every iOS app.
    /// A plain pan limited to the edge strip, not UIScreenEdgePanGestureRecognizer: that one
    /// depends on bezel information only real touches carry, so no test can exercise it.
    private func attachBackSwipe(to window: UIWindow) {
        let swipe = UIPanGestureRecognizer(target: self, action: #selector(backSwipe(_:)))
        swipe.delegate = self
        window.rootViewController?.view.addGestureRecognizer(swipe)
    }

    @objc private func backSwipe(_ recognizer: UIPanGestureRecognizer) {
        guard recognizer.state == .ended, let view = recognizer.view else { return }
        let distance = recognizer.translation(in: view).x
        let speed = recognizer.velocity(in: view).x
        // Far enough, or flicked: roughly a navigation controller's thresholds.
        if distance > view.bounds.width * 0.3 || speed > 500 {
            _ = Platform_iosKt.goBack()
        }
    }

    /// Only a rightward drag that starts at the edge is a back swipe; anything else is the content's.
    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let pan = gestureRecognizer as? UIPanGestureRecognizer, let view = pan.view else { return false }
        let start = pan.location(in: view).x - pan.translation(in: view).x
        let velocity = pan.velocity(in: view)
        return start <= edgeWidth && velocity.x > abs(velocity.y)
    }

    /// hlsplayer://play?url=<manifest>: plays it outside the library, as Android's VIEW intent does.
    private func open(_ link: URL) {
        guard let target = URLComponents(url: link, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "url" })?.value else { return }
        MainViewControllerKt.openLink(url: target)
    }
}
