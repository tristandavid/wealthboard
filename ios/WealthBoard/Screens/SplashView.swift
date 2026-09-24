import SwiftUI

/// Launch splash: the three-bar "rising trend" mark animates in — bars grow
/// up from the baseline, the trend line draws itself across them, the
/// arrowhead pops in — then the wordmark fades up underneath. Same 108x108
/// coordinate space as Android's ic_launcher_foreground.xml, so both
/// platforms animate the identical mark.
///
/// Shown once per launch, on top of everything else in WealthBoardApp's
/// ZStack, then dismissed via `onFinished` to reveal the real content
/// beneath it (see the `showSplash` gate there).
struct SplashView: View {
    var onFinished: () -> Void

    @State private var bar1: CGFloat = 0
    @State private var bar2: CGFloat = 0
    @State private var bar3: CGFloat = 0
    @State private var lineProgress: CGFloat = 0
    @State private var arrowOpacity: Double = 0
    @State private var textOpacity: Double = 0
    @State private var textOffset: CGFloat = 18

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Brand.navy, Brand.navyDark],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 22) {
                BrandMark(
                    bar1: bar1,
                    bar2: bar2,
                    bar3: bar3,
                    lineProgress: lineProgress,
                    arrowOpacity: arrowOpacity
                )
                .frame(width: 132, height: 132)

                VStack(spacing: 4) {
                    Text("WealthBoard")
                        .font(.system(size: 27, weight: .semibold))
                        .foregroundStyle(Brand.ivory)
                    Text("Dividend Tracker")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Brand.goldLight)
                }
                .opacity(textOpacity)
                .offset(y: textOffset)
            }
        }
        .onAppear(perform: animate)
    }

    private func animate() {
        let barSpring = Animation.interpolatingSpring(stiffness: 170, damping: 14)

        withAnimation(barSpring) { bar1 = 1 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.09) {
            withAnimation(barSpring) { bar2 = 1 }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) {
            withAnimation(barSpring) { bar3 = 1 }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.44) {
            withAnimation(.easeOut(duration: 0.45)) { lineProgress = 1 }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.86) {
            withAnimation(.easeOut(duration: 0.2)) { arrowOpacity = 1 }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.98) {
            withAnimation(.easeOut(duration: 0.4)) {
                textOpacity = 1
                textOffset = 0
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.7) {
            onFinished()
        }
    }
}

/// The three ascending bars + rising trend line + arrowhead, drawn as
/// scaled paths (not the rasterized app icon) so each piece can animate
/// independently. Coordinates are lifted 1:1 from the Android launcher
/// foreground's 108x108 viewport.
private struct BrandMark: View {
    let bar1: CGFloat
    let bar2: CGFloat
    let bar3: CGFloat
    let lineProgress: CGFloat
    let arrowOpacity: Double

    private struct BarSpec {
        let x0: CGFloat
        let x1: CGFloat
        let top: CGFloat
        let bottom: CGFloat = 77.71
        let color: Color
    }

    private let bars: [BarSpec] = [
        BarSpec(x0: 34.22, x1: 45.68, top: 64.61, color: Brand.goldLight),
        BarSpec(x0: 48.14, x1: 59.61, top: 53.14, color: Brand.ivory),
        BarSpec(x0: 62.06, x1: 73.53, top: 40.04, color: Brand.goldLight)
    ]

    private let linePoints: [CGPoint] = [
        CGPoint(x: 31.76, y: 49.86),
        CGPoint(x: 49.78, y: 38.4),
        CGPoint(x: 59.61, y: 44.95),
        CGPoint(x: 75.99, y: 30.21)
    ]

    private let arrowPoints: [CGPoint] = [
        CGPoint(x: 75.99, y: 30.21),
        CGPoint(x: 67.8, y: 31.44),
        CGPoint(x: 74.51, y: 37.58)
    ]

    var body: some View {
        GeometryReader { geo in
            let scale = geo.size.width / 108

            ZStack(alignment: .topLeading) {
                bar(bars[0], progress: bar1, scale: scale)
                bar(bars[1], progress: bar2, scale: scale)
                bar(bars[2], progress: bar3, scale: scale)

                trendLine(scale: scale)
                    .trim(from: 0, to: lineProgress)
                    .stroke(Brand.ivory, style: StrokeStyle(lineWidth: 2.78 * scale, lineCap: .round, lineJoin: .round))

                arrowHead(scale: scale)
                    .fill(Brand.ivory)
                    .opacity(arrowOpacity)
            }
        }
    }

    private func bar(_ spec: BarSpec, progress: CGFloat, scale: CGFloat) -> some View {
        let width = (spec.x1 - spec.x0) * scale
        let fullHeight = (spec.bottom - spec.top) * scale
        let height = max(fullHeight * progress, 0)

        return Rectangle()
            .fill(spec.color)
            .frame(width: width, height: height)
            .position(
                x: (spec.x0 + spec.x1) / 2 * scale,
                y: spec.bottom * scale - height / 2
            )
    }

    private func trendLine(scale: CGFloat) -> Path {
        Path { path in
            let scaled = linePoints.map { CGPoint(x: $0.x * scale, y: $0.y * scale) }
            path.move(to: scaled[0])
            for point in scaled.dropFirst() {
                path.addLine(to: point)
            }
        }
    }

    private func arrowHead(scale: CGFloat) -> Path {
        Path { path in
            let scaled = arrowPoints.map { CGPoint(x: $0.x * scale, y: $0.y * scale) }
            path.move(to: scaled[0])
            path.addLine(to: scaled[1])
            path.addLine(to: scaled[2])
            path.closeSubpath()
        }
    }
}
