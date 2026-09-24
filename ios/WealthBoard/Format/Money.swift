import Foundation

/// One money formatter for the whole app.
///
/// Ported from `ui/format/Money.kt`. The rule is unchanged: the symbol always
/// names the currency unambiguously, so CAD is `CA$`, USD is `US$`, AUD is `A$`.
/// A bare `$` is never printed, because a portfolio that holds both has no way
/// to read it.
///
/// Separators are pinned to the US convention rather than the device locale for
/// the same reason they are on Android: a price list where some rows read
/// "1,234.56" and others "1.234,56" is unreadable, and these strings sit beside
/// tickers and ratios that are always formatted this way.
enum Money {

    // MARK: - Symbols

    /// Unambiguous symbol for an ISO 4217 code.
    static func symbol(_ code: String?) -> String {
        guard let raw = code?.trimmingCharacters(in: .whitespaces).uppercased(), !raw.isEmpty else {
            return "$"
        }
        switch raw {
        case "CAD": return "CA$"
        case "USD": return "US$"
        case "AUD": return "A$"
        case "NZD": return "NZ$"
        case "HKD": return "HK$"
        case "SGD": return "S$"
        case "TWD": return "NT$"
        case "MXN": return "MX$"
        case "BRL": return "R$"
        case "EUR": return "€"
        case "GBP": return "£"
        case "JPY": return "¥"
        case "CNY", "CNH": return "CN¥"
        case "INR": return "₹"
        case "KRW": return "₩"
        case "CHF": return "CHF "
        case "SEK": return "SEK "
        case "NOK": return "NOK "
        case "DKK": return "DKK "
        case "ZAR": return "R"
        default: return raw + " "
        }
    }

    /// Currencies quoted without decimal places.
    static func defaultDecimals(_ code: String?) -> Int {
        switch code?.trimmingCharacters(in: .whitespaces).uppercased() {
        case "JPY", "KRW": return 0
        default: return 2
        }
    }

    // MARK: - Grouping

    private static let usLocale = Locale(identifier: "en_US_POSIX")

    private static func grouped(_ value: Double, decimals: Int) -> String {
        let f = NumberFormatter()
        f.locale = usLocale
        f.numberStyle = .decimal
        f.groupingSeparator = ","
        f.decimalSeparator = "."
        f.usesGroupingSeparator = true
        f.minimumFractionDigits = max(0, decimals)
        f.maximumFractionDigits = max(0, decimals)
        return f.string(from: NSNumber(value: value)) ?? String(format: "%.\(max(0, decimals))f", value)
    }

    // MARK: - Public formatting

    /// "CA$1,234.56", or "-CA$12.50" for a negative.
    ///
    /// The minus sign leads the symbol rather than sitting between symbol and
    /// digits, which is how every finance app prints a loss.
    static func format(_ amount: Double, _ code: String?, decimals: Int? = nil) -> String {
        let d = decimals ?? defaultDecimals(code)
        let sign = amount < 0 ? "-" : ""
        return sign + symbol(code) + grouped(abs(amount), decimals: d)
    }

    /// Same, but always carrying an explicit + or - — for changes and returns.
    static func signed(_ amount: Double, _ code: String?, decimals: Int? = nil) -> String {
        let d = decimals ?? defaultDecimals(code)
        let sign = amount > 0 ? "+" : (amount < 0 ? "-" : "")
        return sign + symbol(code) + grouped(abs(amount), decimals: d)
    }

    /// Per-unit distributions, which are small enough that two decimals rounds
    /// a real payment to "CA$0.00". Trailing zeros are trimmed so a clean
    /// quarterly amount doesn't read as false precision — but never past two.
    static func perUnit(_ amount: Double, _ code: String?) -> String {
        let v = abs(amount)
        let digits = v < 10.0 ? 4 : 2
        var body = grouped(v, decimals: digits)
        if body.contains(".") {
            let parts = body.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false)
            let whole = String(parts[0])
            var frac = parts.count > 1 ? String(parts[1]) : ""
            while frac.count > 2, frac.hasSuffix("0") { frac.removeLast() }
            while frac.count < 2 { frac.append("0") }
            body = whole + "." + frac
        }
        return (amount < 0 ? "-" : "") + symbol(code) + body
    }

    /// Axis- and bar-label form: "1.88k", "535", "54.5".
    ///
    /// Chart labels sit in a narrow slot, so a full "CA$1,884.20" cannot fit and
    /// the currency is named once in the legend instead.
    static func compact(_ amount: Double) -> String {
        let v = abs(amount)
        let sign = amount < 0 ? "-" : ""
        let body: String
        if v >= 1_000_000 {
            body = trim(String(format: "%.2f", v / 1_000_000)) + "M"
        } else if v >= 1_000 {
            body = trim(String(format: "%.2f", v / 1_000)) + "k"
        } else if v >= 100 {
            body = String(format: "%.0f", v)
        } else if v >= 10 {
            body = trim(String(format: "%.1f", v))
        } else if v >= 1 {
            body = trim(String(format: "%.2f", v))
        } else if v > 0 {
            body = trim(String(format: "%.4f", v))
        } else {
            body = "0"
        }
        return sign + body
    }

    private static func trim(_ s: String) -> String {
        guard s.contains(".") else { return s }
        var out = s
        while out.hasSuffix("0") { out.removeLast() }
        if out.hasSuffix(".") { out.removeLast() }
        return out
    }

    /// Plain number with thousands separators — for share counts and volumes.
    static func units(_ value: Double) -> String {
        let s = grouped(value, decimals: 4)
        return trim(s)
    }

    /// Plain price with thousands separators and two decimals, no symbol.
    static func plain(_ value: Double, decimals: Int = 2) -> String {
        grouped(value, decimals: decimals)
    }

    /// "12.34%" — the app's single percent format.
    static func percent(_ value: Double?, decimals: Int = 2) -> String {
        guard let value else { return "—" }
        return String(format: "%.\(decimals)f%%", value)
    }

    /// "+12.34%" / "-1.10%".
    static func signedPercent(_ value: Double?, decimals: Int = 2) -> String {
        guard let value else { return "—" }
        let sign = value >= 0 ? "+" : ""
        return sign + String(format: "%.\(decimals)f%%", value)
    }

    /// Compact volume: "1.2M", "845k", "912".
    static func volume(_ value: Int64?) -> String {
        guard let value, value > 0 else { return "—" }
        return compact(Double(value))
    }
}
