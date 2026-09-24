import Foundation

/// Country flag for a ticker/exchange pair, shared by every screen that lists
/// tickers (search results, watchlist rows, holding rows).
///
/// Ported from `ui/format/TickerFlag.kt` — one mapping for the whole app, so a
/// new exchange suffix is added in exactly one place.
enum TickerFlag {

    /// Best-effort flag for `ticker`, preferring an explicit Yahoo-style
    /// `exchange` label when the ticker's own suffix doesn't say enough, then
    /// falling back to `currency`.
    static func forTicker(_ ticker: String, exchange: String? = nil, currency: String? = nil) -> String {
        let t = ticker.trimmingCharacters(in: .whitespaces).uppercased()

        let indexFlag = MarketIndices.flag(for: t)
        if !indexFlag.isEmpty { return indexFlag }

        if t.hasPrefix("^") { return "" }
        if t.contains("-USD") || t.contains("-BTC") || t.contains("-ETH") { return "" }

        if let s = bySuffix(t) { return s }
        if let e = exchange, let f = byExchangeName(e) { return f }
        return byCurrency(currency) ?? "🇺🇸"
    }

    /// Flag for a bare currency code, or "" when it isn't one this knows.
    ///
    /// Unlike `forTicker` there is no 🇺🇸 default here: the callers are currency
    /// section headers, where guessing a flag for an unrecognised code would
    /// label a bucket with the wrong country outright.
    static func forCurrency(_ currency: String?) -> String {
        byCurrency(currency) ?? ""
    }

    private static func bySuffix(_ t: String) -> String? {
        if t.hasSuffix(".TO") || t.hasSuffix(".V") || t.hasSuffix(".NE")
            || t.hasSuffix(".CN") || t.hasSuffix(".TSX") { return "🇨🇦" }
        if t.hasSuffix(".L") { return "🇬🇧" }
        if t.hasSuffix(".DE") || t.hasSuffix(".F") || t.hasSuffix(".BE") { return "🇩🇪" }
        if t.hasSuffix(".PA") { return "🇫🇷" }
        if t.hasSuffix(".AS") { return "🇳🇱" }
        if t.hasSuffix(".SW") { return "🇨🇭" }
        if t.hasSuffix(".MI") { return "🇮🇹" }
        if t.hasSuffix(".MC") { return "🇪🇸" }
        if t.hasSuffix(".ST") { return "🇸🇪" }
        if t.hasSuffix(".OL") { return "🇳🇴" }
        if t.hasSuffix(".CO") { return "🇩🇰" }
        if t.hasSuffix(".HE") { return "🇫🇮" }
        if t.hasSuffix(".IC") { return "🇮🇸" }
        if t.hasSuffix(".VI") { return "🇦🇹" }
        if t.hasSuffix(".BR") { return "🇧🇪" }
        if t.hasSuffix(".LS") { return "🇵🇹" }
        if t.hasSuffix(".IR") { return "🇮🇪" }
        if t.hasSuffix(".WA") { return "🇵🇱" }
        if t.hasSuffix(".T") { return "🇯🇵" }
        if t.hasSuffix(".HK") { return "🇭🇰" }
        if t.hasSuffix(".SS") || t.hasSuffix(".SZ") { return "🇨🇳" }
        if t.hasSuffix(".AX") { return "🇦🇺" }
        if t.hasSuffix(".NZ") { return "🇳🇿" }
        if t.hasSuffix(".NS") || t.hasSuffix(".BO") { return "🇮🇳" }
        if t.hasSuffix(".SA") { return "🇧🇷" }
        if t.hasSuffix(".MX") { return "🇲🇽" }
        if t.hasSuffix(".KS") || t.hasSuffix(".KQ") { return "🇰🇷" }
        if t.hasSuffix(".TW") || t.hasSuffix(".TWO") { return "🇹🇼" }
        if t.hasSuffix(".SI") { return "🇸🇬" }
        if t.hasSuffix(".JK") { return "🇮🇩" }
        if t.hasSuffix(".PS") { return "🇵🇭" }
        if t.hasSuffix(".KL") { return "🇲🇾" }
        if t.hasSuffix(".BK") { return "🇹🇭" }
        if t.hasSuffix(".IS") { return "🇹🇷" }
        if t.hasSuffix(".TA") { return "🇮🇱" }
        if t.hasSuffix(".JO") { return "🇿🇦" }
        return nil
    }

    private static func byExchangeName(_ exchange: String) -> String? {
        let e = exchange.uppercased()
        func has(_ needle: String) -> Bool { e.contains(needle) }

        if has("TORONTO") || has("TSX") || has("VENTURE") || has("NEO") || has("CBOE CA") { return "🇨🇦" }
        if has("LONDON") || e == "LSE" { return "🇬🇧" }
        if has("FRANKFURT") || has("XETRA") { return "🇩🇪" }
        if has("PARIS") || has("EURONEXT") { return "🇫🇷" }
        if has("STOCKHOLM") { return "🇸🇪" }
        if has("OSLO") { return "🇳🇴" }
        if has("COPENHAGEN") { return "🇩🇰" }
        if has("HELSINKI") { return "🇫🇮" }
        if has("TOKYO") || has("OSAKA") { return "🇯🇵" }
        if has("HONG KONG") { return "🇭🇰" }
        if has("SHANGHAI") || has("SHENZHEN") { return "🇨🇳" }
        if has("SYDNEY") || has("ASX") { return "🇦🇺" }
        if has("NSE") || has("BSE") || has("BOMBAY") { return "🇮🇳" }
        if has("SAO PAULO") || has("BOVESPA") || has("B3") { return "🇧🇷" }
        if has("MEXICO") { return "🇲🇽" }
        if has("KOREA") || has("KOSPI") || has("KOSDAQ") { return "🇰🇷" }
        if has("TAIWAN") { return "🇹🇼" }
        if has("SINGAPORE") { return "🇸🇬" }
        if has("JAKARTA") || has("INDONESIA") { return "🇮🇩" }
        if has("PHILIPPINE") || has("MANILA") || e == "PSE" { return "🇵🇭" }
        if has("KUALA LUMPUR") || has("MALAYSIA") { return "🇲🇾" }
        if has("NASDAQ") || has("NYSE") || has("AMEX") || has("NYQ") || has("NMS")
            || has("PCX") || has("BATS") { return "🇺🇸" }
        return nil
    }

    private static func byCurrency(_ currency: String?) -> String? {
        switch currency?.uppercased() {
        case "CAD": return "🇨🇦"
        case "GBP", "GBP.L", "GBX": return "🇬🇧"
        case "EUR": return "🇪🇺"
        case "JPY": return "🇯🇵"
        case "AUD": return "🇦🇺"
        case "HKD": return "🇭🇰"
        case "CHF": return "🇨🇭"
        case "INR": return "🇮🇳"
        case "BRL": return "🇧🇷"
        case "SEK": return "🇸🇪"
        case "NOK": return "🇳🇴"
        case "DKK": return "🇩🇰"
        case "KRW": return "🇰🇷"
        case "TWD": return "🇹🇼"
        case "SGD": return "🇸🇬"
        case "MXN": return "🇲🇽"
        case "CNY": return "🇨🇳"
        case "NZD": return "🇳🇿"
        case "ISK": return "🇮🇸"
        case "PLN": return "🇵🇱"
        case "THB": return "🇹🇭"
        case "TRY": return "🇹🇷"
        case "ILS": return "🇮🇱"
        case "ZAR": return "🇿🇦"
        case "IDR": return "🇮🇩"
        case "PHP": return "🇵🇭"
        case "MYR": return "🇲🇾"
        case "USD": return "🇺🇸"
        default: return nil
        }
    }
}
