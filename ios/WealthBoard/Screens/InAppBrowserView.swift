import SwiftUI
import UIKit
import WebKit

/// A minimal in-app browser, used to read a news article without leaving the
/// app. Ported from `InAppBrowserScreen.kt`.
///
/// `SFSafariViewController` would be less code, but it presents its own chrome
/// and its own navigation bar, so a tap on a headline would visibly leave
/// WealthBoard. `WKWebView` inside the app's own navigation stack keeps the
/// back gesture, the gold bar and the tab bar where the reader left them.
struct InAppBrowserView: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.openURL) private var openURL

    let url: String
    var title: String = "News"

    @State private var progress: Double = 0
    @State private var isLoading = true

    var body: some View {
        ZStack(alignment: .top) {
            if let resolved = URL(string: url) {
                // Bottom safe area respected, not ignored. Drawn under the
                // tab bar, a page's fixed footer — a cookie or consent banner
                // is the usual one — sat behind it with its Accept/Close
                // button out of reach, so the banner could never be dismissed.
                WebViewContainer(url: resolved, progress: $progress, isLoading: $isLoading)
            } else {
                EmptyNote(text: "That link doesn't look like a web address.")
                    .padding(WbDimens.screenPadding)
            }

            if isLoading {
                // A determinate bar rather than a spinner: an article on a slow
                // connection takes long enough that "is this doing anything"
                // becomes a real question.
                ProgressView(value: progress)
                    .progressViewStyle(.linear)
                    .tint(Palette.accent(scheme))
            }
        }
        .wbScreenBackground(scheme)
        // An article gets the whole screen. The floating tab bar covered the
        // bottom of every page, and a reader has the back button to leave.
        .toolbar(.hidden, for: .tabBar)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button {
                        if let resolved = URL(string: url) { openURL(resolved) }
                    } label: {
                        Label("Open in Safari", systemImage: "safari")
                    }
                    if let resolved = URL(string: url) {
                        ShareLink(item: resolved) {
                            Label("Share", systemImage: "square.and.arrow.up")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
    }
}

/// `WKWebView` wrapped for SwiftUI, reporting load progress back up.
private struct WebViewContainer: UIViewRepresentable {
    let url: URL
    @Binding var progress: Double
    @Binding var isLoading: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(progress: $progress, isLoading: $isLoading)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.contentInsetAdjustmentBehavior = .always

        context.coordinator.observe(webView)
        context.coordinator.requestedURL = url
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        // Load only when the CALLER asked for a different page.
        //
        // This used to compare against `webView.url` — where the page ended
        // up, not what was asked for. Any site that redirects (BBC sends
        // bbc.co.uk links to bbc.com, most sites add tracking parameters or
        // rewrite the address as the article loads) never matches the link it
        // was opened with, and updateUIView runs on every progress tick, so
        // each tick started the article over: an endless reload loop.
        guard context.coordinator.requestedURL != url else { return }
        context.coordinator.requestedURL = url
        webView.load(URLRequest(url: url))
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        coordinator.stopObserving()
        webView.stopLoading()
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        @Binding private var progress: Double
        @Binding private var isLoading: Bool
        private var observation: NSKeyValueObservation?
        /// The last URL this view was asked to show — see `updateUIView`.
        var requestedURL: URL?

        init(progress: Binding<Double>, isLoading: Binding<Bool>) {
            _progress = progress
            _isLoading = isLoading
        }

        func observe(_ webView: WKWebView) {
            observation = webView.observe(\.estimatedProgress, options: [.new]) { [weak self] view, _ in
                Task { @MainActor in
                    // Only meaningful changes: every write re-renders the
                    // SwiftUI view around the page.
                    guard let self, abs(self.progress - view.estimatedProgress) >= 0.05
                            || view.estimatedProgress >= 1 else { return }
                    self.progress = view.estimatedProgress
                }
            }
        }

        /// Links that are not web pages (mail, phone, app-store and app deep
        /// links) are handed to the system instead of failing silently inside
        /// the web view.
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            if let target = navigationAction.request.url,
               let scheme = target.scheme?.lowercased(),
               !["http", "https", "about", "data", "blob", "javascript"].contains(scheme) {
                UIApplication.shared.open(target)
                decisionHandler(.cancel)
                return
            }
            // target="_blank" links would otherwise open nowhere: WKWebView
            // has no second window to put them in. Load them here instead.
            if navigationAction.targetFrame == nil {
                webView.load(navigationAction.request)
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }

        func stopObserving() {
            observation?.invalidate()
            observation = nil
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            isLoading = true
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            isLoading = false
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            isLoading = false
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            isLoading = false
        }
    }
}
