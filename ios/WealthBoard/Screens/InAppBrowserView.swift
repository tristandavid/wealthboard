import SwiftUI
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
                WebViewContainer(url: resolved, progress: $progress, isLoading: $isLoading)
                    .ignoresSafeArea(edges: .bottom)
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
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        // Reload only when the URL actually changed — updateUIView runs on
        // every state change, and reloading on each one would restart the
        // article every time the progress bar moved.
        guard webView.url != url else { return }
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

        init(progress: Binding<Double>, isLoading: Binding<Bool>) {
            _progress = progress
            _isLoading = isLoading
        }

        func observe(_ webView: WKWebView) {
            observation = webView.observe(\.estimatedProgress, options: [.new]) { [weak self] view, _ in
                Task { @MainActor in
                    self?.progress = view.estimatedProgress
                }
            }
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
