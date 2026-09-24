import Foundation

// Wire models for the quote layer, ported from `net/YahooQuoteClient.kt`.

struct Quote: Hashable {
    let price: Double
    var name: String?
    var previousClose: Double?
    var currency: String?
    var dayHigh: Double?
    var dayLow: Double?
    var open: Double?
    var volume: Int64?
    var fiftyTwoWeekHigh: Double?
    var fiftyTwoWeekLow: Double?
    var avgVolume3Month: Int64?

    /// When the last regular-session trade printed, from `meta.regularMarketTime`.
    var marketTime: Date?

    /// IANA zone the listing trades in, from `meta.exchangeTimezoneName`
    /// (e.g. "Europe/London" for the FTSE, "America/Toronto" for the TSX).
    ///
    /// Intraday charts have to be labelled in this zone, not the device's: a
    /// London session runs 08:00–16:30 local, and rendering it in the viewer's
    /// own timezone prints the FTSE as opening at 3:00 AM for a reader in
    /// Toronto.
    var exchangeTimezone: String?

    /// What the symbol actually is, as the provider classifies it —
    /// "EQUITY", "ETF", "MUTUALFUND", "CRYPTOCURRENCY", "INDEX", "FUTURE".
    ///
    /// Carried so a holding filed under the wrong type can correct itself on
    /// the next quote refresh instead of staying wrong forever.
    var instrumentType: String?

    /// Company logo URL, when the provider has one. `TickerLogo` falls back to
    /// an initial-letter avatar when this is nil, so callers never special-case it.
    var logoURL: String?

    var change: Double? {
        guard let previousClose else { return nil }
        return price - previousClose
    }

    var changePercent: Double? {
        guard let previousClose, previousClose != 0 else { return nil }
        return (price - previousClose) / previousClose * 100
    }
}

/// A secondary quote shown beneath the main price: either after-hours /
/// pre-market trading for a stock or ETF, or the index future that keeps
/// trading once the cash market closes.
///
/// `label` is what the row prints ("Extended", "E-mini ES"), so the two cases
/// share one rendering path.
struct ExtendedQuote: Hashable {
    let label: String
    let price: Double
    let change: Double
    let changePercent: Double
    let at: Date
    /// The symbol this secondary line is actually quoting — the future's own
    /// ticker ("YM=F") for an index row, or the listing's ticker for an
    /// after-hours print. Carried so `at` can be labelled on the venue's clock.
    var symbol: String?
    /// IANA zone the quote above was struck in, when the provider reported one.
    var zoneId: String?
}

/// A price move over some period, worded for the line it is printed on.
///
/// Every price header in the app shows one of these, and they all used to show
/// the same one — the day change — whatever the range chips underneath were set
/// to. Tapping 1Y moved the chart and left the figure above it talking about
/// this afternoon, with only the word "today" to give it away.
struct RangeMove {
    let absolute: Double
    let percent: Double
    /// "today", "over 1M" — the period, in the form it reads in a sentence.
    let label: String

    /// The move across a series of bars, measured to `livePrice` when there is
    /// one.
    ///
    /// The newest bar can be a whole interval stale, and the price printed
    /// immediately above is live, so measuring to the bar would put two figures
    /// on one card that disagree about the same instant.
    static func over(bars: [HistoryBar], livePrice: Double?, label: String) -> RangeMove? {
        guard let opening = bars.first?.close, opening > 0 else { return nil }
        let closing = livePrice ?? bars[bars.count - 1].close
        let absolute = closing - opening
        return RangeMove(
            absolute: absolute,
            percent: absolute / opening * 100,
            label: "over " + label
        )
    }

    /// Today's move, from the quote's own previous close.
    ///
    /// Preferred over the series whenever the range is 1D: this is the number
    /// the exchange itself would quote, where a series has to infer it from
    /// whichever bar happened to open the session.
    static func today(_ quote: Quote?) -> RangeMove? {
        guard let change = quote?.change, let percent = quote?.changePercent else { return nil }
        return RangeMove(absolute: change, percent: percent, label: "today")
    }
}

/// One bar returned by the history endpoint (close + optional volume).
struct HistoryBar: Hashable {
    let date: Date
    let close: Double
    var volume: Int64?
}

/// A batched intraday trace for one symbol, used to fill the Markets tab's
/// Chart column without one request per row.
struct SparkQuote: Hashable {
    let ticker: String
    let price: Double
    var previousClose: Double?
    var closes: [Double]
    var marketTime: Date?
    var exchangeTimezone: String?
}

struct UpcomingDividend: Hashable {
    var exDividendDate: Date?
    var payDate: Date?
    /// Trailing twelve-month dividend rate, per share.
    var estimatedAnnualRate: Double?
    var yieldPercent: Double?
    /// How many times per year this holding pays (12 = monthly, 4 = quarterly,
    /// 2 = semi-annual, 1 = annual). nil when it could not be determined.
    var paymentFrequencyPerYear: Int?

    /// The per-share cash amount expected on the next payment.
    ///
    /// This is the number the Upcoming Dividends card shows. It is NOT
    /// `estimatedAnnualRate / frequency`: many ETFs (XEQT, VBAL, ZEQT …) pay a
    /// small Q1/Q3 and a large Q2/Q4, so dividing the annual rate evenly
    /// overstates the small quarters by 3x or more.
    var perPaymentAmount: Double?

    /// True when `perPaymentAmount` is the amount the fund actually *declared*
    /// for this payment, not a projection. The UI shows an "Announced" badge
    /// instead of "Estimated" and stops applying its own forecast.
    var isAnnounced: Bool = false

    /// Short human-readable description of how `perPaymentAmount` was derived.
    var basisLabel: String?
}

/// One match from the ticker search-as-you-type used by "Add a quote".
struct SymbolSearchResult: Identifiable, Hashable {
    let symbol: String
    var name: String?
    var exchange: String?
    var quoteType: String?

    var id: String { symbol }
}

/// One row from a market-wide screener (most actives, day gainers, day losers).
/// Feeds the Markets tab's "Hot Stocks" list.
struct MarketMover: Identifiable, Hashable {
    let ticker: String
    var name: String?
    let price: Double
    let previousClose: Double
    /// e.g. 4.72 means +4.72 %.
    let changePct: Double
    /// "NasdaqGS", "NYSE", "Toronto", etc. — used for flag display.
    var exchange: String?
    var volume: Int64?
    var averageVolume: Int64?
    /// When the last trade printed. Without it a Friday close looks identical
    /// to a live quote — the one thing a "what's moving right now" list must
    /// not be ambiguous about.
    var marketTime: Date?
    var exchangeTimezone: String?

    var id: String { ticker }

    /// How much attention this name is getting today.
    ///
    /// Size of the move alone ranks a thinly-traded microcap that jumped 40% on
    /// no volume above Nvidia on an earnings day, which is not what anyone
    /// means by "hot". Multiplying the move by how unusual today's volume is
    /// against the stock's own average fixes that: a name is hot when it moved
    /// AND the market actually showed up to trade it.
    var heat: Double {
        let move = abs(changePct)
        let relVolume: Double
        if let volume, let averageVolume, averageVolume > 0 {
            relVolume = min(max(Double(volume) / Double(averageVolume), 0.25), 12.0)
        } else {
            relVolume = 1.0
        }
        // log damps the volume term, so a 10x volume day counts for more than a
        // 2x day without swamping the price move entirely.
        return move * (1.0 + log(1.0 + relVolume))
    }
}

/// Editorial grouping used by the news feed's section headers.
enum NewsCategory: String, Codable, CaseIterable, Identifiable {
    case top = "Top Stories"
    case markets = "Markets"
    case economy = "Economy & Policy"
    case companies = "Companies"
    case crypto = "Crypto"

    var id: String { rawValue }
    var label: String { rawValue }
}

/// One news headline, shown in the Market News section.
struct NewsItem: Identifiable, Hashable, Codable {
    let title: String
    let publisher: String
    let linkURL: String
    let publishedAt: Date
    var imageURL: String?
    var summary: String?
    var category: NewsCategory = .markets

    var id: String { linkURL }
}

/// Errors the quote layer surfaces. Callers nearly always degrade rather than
/// show these — a stale price beats an error dialog — but the cases are
/// distinguished so a rate limit can back off differently from a bad symbol.
enum QuoteError: Error {
    case notFound
    case rateLimited
    case badResponse
    case network(Error)
}
