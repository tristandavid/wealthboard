import Foundation

/// What one alert firing has to say.
///
/// Carries the finished strings rather than the raw numbers, so the notifier
/// is a dumb pipe and the wording lives next to the rule that produced it.
struct AlertFiring {
    let alertId: UUID
    let ticker: String
    let title: String
    let body: String
    let value: Double
}

/// Decides which alerts should fire right now.
///
/// Mirrors `AlertEngine` on Android rule for rule — same latching, same
/// treatment of a failed fetch, same wording — so the two apps cannot quietly
/// disagree about when a threshold was crossed.
///
/// Deliberately knows nothing about UserNotifications: evaluation is the part
/// worth being able to reason about on its own, and it is the part where a
/// mistake is expensive. An alert that fires wrongly teaches people to ignore
/// alerts; one that silently never fires is worse, because nothing about the
/// UI looks broken.
enum AlertEngine {

    /// Every rule family — the default for a caller that wants the lot.
    static let allKinds: Set<AlertKind> = Set(AlertKind.allCases)

    /// Per-ticker price data, however it was obtained — a batch response, a
    /// cached row, or a test.
    struct Priced {
        let price: Double
        let previousClose: Double?
    }

    /// Evaluates every enabled alert and returns the ones that just became
    /// true, along with ONLY the alerts whose latch state actually changed.
    ///
    /// Returning the changed rows rather than the whole list matters: this
    /// function awaits network calls for several seconds, and the user may
    /// add, delete or re-arm an alert on the Alerts screen while it runs.
    /// Writing the whole pre-fetch snapshot back would silently undo that —
    /// in particular it would re-latch a rule the user had just switched back
    /// on, leaving it quiet through its next genuine crossing.
    ///
    /// Returns rather than writes because the store is an actor the caller
    /// already owns; keeping persistence out of here is also what makes the
    /// latch logic testable.
    ///
    /// `kinds` limits the pass to the rule families worth evaluating right now
    /// — see `AlertGate`, which decides it. A kind left out is not evaluated at
    /// all, which means it is neither fired NOR re-armed: skipping is silence
    /// about a rule, never a verdict on it. Defaults to every kind, so a caller
    /// with no opinion (the developer row, a test) behaves as before.
    static func evaluate(
        alerts: [PriceAlert],
        repository: PortfolioRepository,
        now: Date = Date(),
        kinds: Set<AlertKind> = AlertEngine.allKinds
    ) async -> (firings: [AlertFiring], changed: [PriceAlert]) {

        var updated = alerts
        var changedIds = Set<UUID>()
        var firings: [AlertFiring] = []

        let enabled = alerts.enumerated().filter {
            $0.element.enabled && kinds.contains($0.element.kind)
        }
        guard !enabled.isEmpty else { return ([], []) }

        // ── Price-driven rules ────────────────────────────────────────────
        //
        // One batched request for every ticker any price rule mentions,
        // rather than a call per alert: someone watching five thresholds on
        // the same security should cost one request, not five.
        let priceKinds: Set<AlertKind> = [.priceAbove, .priceBelow, .dayMovePercent]
        let priceIndices = enabled.filter { priceKinds.contains($0.element.kind) }

        var priced: [String: Priced] = [:]
        if !priceIndices.isEmpty {
            let tickers = Array(Set(priceIndices.map { $0.element.ticker }))
            priced = await pricedFor(tickers)
        }

        for (index, alert) in priceIndices {
            // A ticker the batch didn't answer for is UNKNOWN, not false. It
            // is left exactly as it was — neither fired nor re-armed —
            // because a failed fetch must never look like "the condition went
            // away".
            guard let quote = priced[alert.ticker] else { continue }
            let outcome = evaluatePrice(alert, quote: quote)
            apply(outcome, to: &updated[index], now: now,
                  firings: &firings, changed: &changedIds)
        }

        // ── Ex-dividend rules ─────────────────────────────────────────────
        //
        // One call per distinct ticker, and only for tickers that actually
        // have such an alert: this endpoint is per-symbol, so the cost is
        // bounded by how many calendar alerts the user set rather than by the
        // size of their portfolio.
        let exDivIndices = enabled.filter { $0.element.kind == .exDividendWithinDays }
        if !exDivIndices.isEmpty {
            var upcoming: [String: Date] = [:]
            for ticker in Set(exDivIndices.map { $0.element.ticker }) {
                let (dividend, _) = await repository.upcomingDividend(ticker: ticker)
                if let date = dividend?.exDividendDate { upcoming[ticker] = date }
            }
            for (index, alert) in exDivIndices {
                guard let exDate = upcoming[alert.ticker] else { continue }
                let outcome = evaluateExDividend(alert, exDate: exDate, now: now)
                apply(outcome, to: &updated[index], now: now,
                      firings: &firings, changed: &changedIds)
            }
        }

        return (firings, updated.filter { changedIds.contains($0.id) })
    }

    /// Prices for `tickers`, from the SAME source the portfolio is priced from.
    ///
    /// This used to call `fetchSparkBatch` directly, which goes straight to the
    /// spark endpoint and skips the Finance Query chain that `fetchQuote` — and
    /// therefore every price the user actually sees — goes through. An alert
    /// could fire on a number visible nowhere in the app, or stay silent while
    /// the portfolio showed a price past the threshold. The rule and the row
    /// now read the same provider.
    ///
    /// Spark remains the fallback for symbols the quote path does not answer
    /// for, so coverage is no worse than before.
    private static func pricedFor(_ tickers: [String]) async -> [String: Priced] {
        var out: [String: Priced] = [:]

        for ticker in tickers {
            guard let quote = try? await QuoteClient.shared.fetchQuote(ticker: ticker) else {
                continue
            }
            out[ticker] = Priced(price: quote.price, previousClose: quote.previousClose)
        }

        let missing = tickers.filter { out[$0] == nil }
        if !missing.isEmpty {
            let batch = await QuoteClient.shared.fetchSparkBatch(tickers: missing)
            // Keyed back to the CALLER's spelling — the spark endpoint answers
            // with symbols spelled its own way, and the alert's own ticker
            // string is what every lookup downstream uses. An unmatched key
            // reads as UNKNOWN, so the rule would never fire and never re-arm.
            for ticker in missing {
                guard let spark = batch[ticker]
                    ?? batch.first(where: { $0.key.caseInsensitiveCompare(ticker) == .orderedSame })?.value
                else { continue }
                out[ticker] = Priced(price: spark.price, previousClose: spark.previousClose)
            }
        }
        return out
    }

    // MARK: - Rules

    /// True when the condition holds, plus the value to report.
    ///
    /// An empty `body` means UNKNOWN rather than false — see `apply`.
    private struct Outcome {
        let isTrue: Bool
        let value: Double
        let body: String
    }

    private static func evaluatePrice(_ alert: PriceAlert, quote: Priced) -> Outcome {
        switch alert.kind {
        case .priceAbove:
            return Outcome(
                isTrue: quote.price >= alert.threshold,
                value: quote.price,
                body: "\(alert.ticker) is at \(PriceAlert.money(quote.price)), at or above your \(PriceAlert.money(alert.threshold)) target."
            )

        case .priceBelow:
            return Outcome(
                isTrue: quote.price <= alert.threshold,
                value: quote.price,
                body: "\(alert.ticker) is at \(PriceAlert.money(quote.price)), at or below your \(PriceAlert.money(alert.threshold)) target."
            )

        case .dayMovePercent:
            // No previous close means no day move to speak of — not a move of
            // zero. Reporting 0% would re-arm an alert that was legitimately
            // latched, so this reads as "condition false, value unknown" and
            // the empty body keeps it from clearing.
            guard let prev = quote.previousClose, prev > 0 else {
                return Outcome(isTrue: false, value: quote.price, body: "")
            }
            let movePercent = (quote.price - prev) / prev * 100
            let direction = movePercent >= 0 ? "up" : "down"
            return Outcome(
                isTrue: abs(movePercent) >= alert.threshold,
                value: movePercent,
                body: "\(alert.ticker) is \(direction) \(String(format: "%.2f", abs(movePercent)))% today, at \(PriceAlert.money(quote.price))."
            )

        case .exDividendWithinDays:
            return Outcome(isTrue: false, value: quote.price, body: "")
        }
    }

    private static func evaluateExDividend(
        _ alert: PriceAlert,
        exDate: Date,
        now: Date
    ) -> Outcome {
        let daysAway = exDate.timeIntervalSince(now) / 86_400
        // Past dates are not "zero days away", they are gone: the window is
        // closed, and re-opening it would notify someone about a date they
        // already missed, on every pass, until the provider posts the next
        // one.
        let withinWindow = daysAway >= 0 && daysAway <= alert.threshold
        let rounded = Int(ceil(daysAway))

        let body: String
        if rounded <= 0 {
            body = "\(alert.ticker) goes ex-dividend today — buying after today misses this payment."
        } else if rounded == 1 {
            body = "\(alert.ticker) goes ex-dividend tomorrow."
        } else {
            body = "\(alert.ticker) goes ex-dividend in \(rounded) days."
        }

        return Outcome(isTrue: withinWindow, value: daysAway, body: body)
    }

    // MARK: - Latching

    private static func apply(
        _ outcome: Outcome,
        to alert: inout PriceAlert,
        now: Date,
        firings: inout [AlertFiring],
        changed: inout Set<UUID>
    ) {
        let alreadyLatched = alert.triggeredAt != nil

        if outcome.isTrue {
            guard !alreadyLatched else { return }
            alert.triggeredAt = now
            alert.lastValue = outcome.value
            changed.insert(alert.id)
            firings.append(
                AlertFiring(
                    alertId: alert.id,
                    ticker: alert.ticker,
                    title: title(for: alert),
                    body: outcome.body,
                    value: outcome.value
                )
            )
        } else if alreadyLatched && !outcome.body.isEmpty {
            // Re-arm. The empty-body check is what keeps an "unknown" result
            // (no previous close, no ex-date) from being mistaken for the
            // condition having genuinely gone away.
            alert.triggeredAt = nil
            alert.lastValue = outcome.value
            changed.insert(alert.id)
        }
    }

    private static func title(for alert: PriceAlert) -> String {
        switch alert.kind {
        case .priceAbove:
            return "\(alert.ticker) hit \(PriceAlert.money(alert.threshold))"
        case .priceBelow:
            return "\(alert.ticker) fell to \(PriceAlert.money(alert.threshold))"
        case .dayMovePercent:
            return "\(alert.ticker) moved \(PriceAlert.trimmed(alert.threshold))%"
        case .exDividendWithinDays:
            return "\(alert.ticker) ex-dividend coming up"
        }
    }
}
