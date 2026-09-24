import Foundation

/// Rate-limit backoff for the quote endpoints.
///
/// The finance API this app reads is undocumented and unsupported, which makes
/// the provider's tolerance a single point of failure for the whole app:
/// quotes, history, dividends and news all come from it. Without backoff, a
/// throttle turns into a stampede — every screen keeps retrying at full rate,
/// and a temporary 429 escalates into a longer block.
///
/// Held centrally, on the client, rather than wrapped around each call: there
/// are around a dozen separate request sites and any one of them missed would
/// keep hammering on its own.
///
/// Two behaviours matter:
///
/// **Fail fast while cooling down.** Once rate-limited, requests are rejected
/// locally without touching the network. Letting them through would be the
/// stampede this exists to prevent.
///
/// **Escalate, then recover.** Each consecutive rejection roughly doubles the
/// cooldown up to a ceiling; one success clears it. Jitter is added so a device
/// that hit the limit alongside others doesn't retry in lockstep with them.
actor RateLimitBackoff {

    /// First cooldown after a rejection. Doubles from here.
    private let baseCooldown: TimeInterval = 30
    /// Ceiling, so a long outage can still recover within a session.
    private let maxCooldown: TimeInterval = 10 * 60

    private var cooldownUntil: Date = .distantPast
    private var consecutiveRejections = 0

    /// True while requests are being refused locally.
    var isCoolingDown: Bool { Date() < cooldownUntil }

    /// Seconds until requests resume, or 0 when not cooling down.
    var cooldownRemaining: TimeInterval {
        max(cooldownUntil.timeIntervalSinceNow, 0)
    }

    /// Call before making a request. Throws `.rateLimited` while cooling down.
    func checkBeforeRequest() throws {
        if Date() < cooldownUntil { throw QuoteError.rateLimited }
    }

    /// Call with the status code of every completed response.
    func recordResponse(statusCode: Int) {
        if statusCode == 429 || statusCode == 999 {
            consecutiveRejections += 1
            let escalated = baseCooldown * pow(2, Double(consecutiveRejections - 1))
            // Jitter so devices throttled together don't retry in lockstep.
            let jitter = Double.random(in: 0...(baseCooldown / 2))
            cooldownUntil = Date().addingTimeInterval(min(escalated, maxCooldown) + jitter)
        } else if (200..<300).contains(statusCode) {
            consecutiveRejections = 0
            cooldownUntil = .distantPast
        }
    }
}
