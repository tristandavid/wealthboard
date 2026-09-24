package ca.tristan.portfolio.ui.format

import ca.tristan.portfolio.data.MarketIndices

/**
 * Country flag for a ticker/exchange pair, shared by every screen that lists
 * tickers (search results, watchlist rows, holding rows).
 *
 * [MarketScreen] and [QuoteDetailScreen] each grew their own private copy of
 * this before this file existed; those aren't touched here since they aren't
 * the ones with a reported bug, but any *new* row this round (search results,
 * the holding-detail header) reads from here so there's exactly one mapping
 * to keep current when a new exchange suffix shows up.
 */
object TickerFlag {

    /**
     * Best-effort flag for [ticker], preferring an explicit Yahoo-style
     * [exchange] label (from search results — "NASDAQ", "Toronto", "London",
     * an ISO exchange code, etc.) when the ticker's own suffix doesn't say
     * enough, then falling back to [currency].
     */
    fun forTicker(ticker: String, exchange: String? = null, currency: String? = null): String {
        val t = ticker.trim().uppercase()
        MarketIndices.flag(t).takeIf { it.isNotEmpty() }?.let { return it }
        if (t.startsWith("^")) return ""
        if (t.contains("-USD") || t.contains("-BTC") || t.contains("-ETH")) return ""

        bySuffix(t)?.let { return it }
        exchange?.let { byExchangeName(it) }?.let { return it }
        return byCurrency(currency) ?: "🇺🇸"
    }

    /**
     * Flag for a bare currency code, or "" when it isn't one this knows.
     *
     * Unlike [forTicker] there is no 🇺🇸 default here: the callers are
     * currency section headers, where guessing a flag for an unrecognised code
     * would label a bucket with the wrong country outright.
     */
    fun forCurrency(currency: String?): String = byCurrency(currency) ?: ""

    private fun bySuffix(t: String): String? = when {
        t.endsWith(".TO") || t.endsWith(".V") || t.endsWith(".NE") ||
            t.endsWith(".CN") || t.endsWith(".TSX")                -> "🇨🇦"
        t.endsWith(".L")                                           -> "🇬🇧"
        t.endsWith(".DE") || t.endsWith(".F") || t.endsWith(".BE") -> "🇩🇪"
        t.endsWith(".PA")                                          -> "🇫🇷"
        t.endsWith(".AS")                                          -> "🇳🇱"
        t.endsWith(".SW")                                          -> "🇨🇭"
        t.endsWith(".MI")                                          -> "🇮🇹"
        t.endsWith(".MC")                                          -> "🇪🇸"
        t.endsWith(".ST")                                          -> "🇸🇪"
        t.endsWith(".OL")                                          -> "🇳🇴"
        t.endsWith(".CO")                                          -> "🇩🇰"
        t.endsWith(".HE")                                          -> "🇫🇮"
        t.endsWith(".IC")                                          -> "🇮🇸"
        t.endsWith(".VI")                                          -> "🇦🇹"
        t.endsWith(".BR")                                          -> "🇧🇪"
        t.endsWith(".LS")                                          -> "🇵🇹"
        t.endsWith(".IR")                                          -> "🇮🇪"
        t.endsWith(".WA")                                          -> "🇵🇱"
        t.endsWith(".T")                                           -> "🇯🇵"
        t.endsWith(".HK")                                          -> "🇭🇰"
        t.endsWith(".SS") || t.endsWith(".SZ")                     -> "🇨🇳"
        t.endsWith(".AX")                                          -> "🇦🇺"
        t.endsWith(".NZ")                                          -> "🇳🇿"
        t.endsWith(".NS") || t.endsWith(".BO")                     -> "🇮🇳"
        t.endsWith(".SA")                                          -> "🇧🇷"
        t.endsWith(".MX")                                          -> "🇲🇽"
        t.endsWith(".KS") || t.endsWith(".KQ")                     -> "🇰🇷"
        t.endsWith(".TW") || t.endsWith(".TWO")                    -> "🇹🇼"
        t.endsWith(".SI")                                          -> "🇸🇬"
        t.endsWith(".JK")                                          -> "🇮🇩"
        t.endsWith(".PS")                                          -> "🇵🇭"
        t.endsWith(".KL")                                          -> "🇲🇾"
        t.endsWith(".BK")                                          -> "🇹🇭"
        t.endsWith(".IS")                                          -> "🇹🇷"
        t.endsWith(".TA")                                          -> "🇮🇱"
        t.endsWith(".JO")                                          -> "🇿🇦"
        else -> null
    }

    private fun byExchangeName(exchange: String): String? {
        val e = exchange.uppercase()
        return when {
            "TORONTO" in e || "TSX" in e || "VENTURE" in e || "NEO" in e || "CBOE CA" in e -> "🇨🇦"
            "LONDON" in e || e == "LSE"                                                     -> "🇬🇧"
            "FRANKFURT" in e || "XETRA" in e                                                -> "🇩🇪"
            "PARIS" in e || "EURONEXT" in e                                                 -> "🇫🇷"
            "STOCKHOLM" in e                                                                -> "🇸🇪"
            "OSLO" in e                                                                     -> "🇳🇴"
            "COPENHAGEN" in e                                                               -> "🇩🇰"
            "HELSINKI" in e                                                                 -> "🇫🇮"
            "TOKYO" in e || "OSAKA" in e                                                    -> "🇯🇵"
            "HONG KONG" in e                                                                -> "🇭🇰"
            "SHANGHAI" in e || "SHENZHEN" in e                                              -> "🇨🇳"
            "SYDNEY" in e || "ASX" in e                                                     -> "🇦🇺"
            "NSE" in e || "BSE" in e || "BOMBAY" in e                                       -> "🇮🇳"
            "SAO PAULO" in e || "BOVESPA" in e || "B3" in e                                 -> "🇧🇷"
            "MEXICO" in e                                                                   -> "🇲🇽"
            "KOREA" in e || "KOSPI" in e || "KOSDAQ" in e                                   -> "🇰🇷"
            "TAIWAN" in e                                                                   -> "🇹🇼"
            "SINGAPORE" in e                                                                -> "🇸🇬"
            "JAKARTA" in e || "INDONESIA" in e                                              -> "🇮🇩"
            "PHILIPPINE" in e || "MANILA" in e || e == "PSE"                                -> "🇵🇭"
            "KUALA LUMPUR" in e || "MALAYSIA" in e                                          -> "🇲🇾"
            "NASDAQ" in e || "NYSE" in e || "AMEX" in e || "NYQ" in e || "NMS" in e ||
                "PCX" in e || "BATS" in e                                                    -> "🇺🇸"
            else -> null
        }
    }

    private fun byCurrency(currency: String?): String? = when (currency?.uppercase()) {
        "CAD" -> "🇨🇦"; "GBP", "GBP.L", "GBX" -> "🇬🇧"; "EUR" -> "🇪🇺"
        "JPY" -> "🇯🇵"; "AUD" -> "🇦🇺"; "HKD" -> "🇭🇰"
        "CHF" -> "🇨🇭"; "INR" -> "🇮🇳"; "BRL" -> "🇧🇷"
        "SEK" -> "🇸🇪"; "NOK" -> "🇳🇴"; "DKK" -> "🇩🇰"
        "KRW" -> "🇰🇷"; "TWD" -> "🇹🇼"; "SGD" -> "🇸🇬"
        "MXN" -> "🇲🇽"; "CNY" -> "🇨🇳"; "NZD" -> "🇳🇿"
        "ISK" -> "🇮🇸"; "PLN" -> "🇵🇱"; "THB" -> "🇹🇭"
        "TRY" -> "🇹🇷"; "ILS" -> "🇮🇱"; "ZAR" -> "🇿🇦"
        "IDR" -> "🇮🇩"; "PHP" -> "🇵🇭"; "MYR" -> "🇲🇾"
        "USD" -> "🇺🇸"
        else -> null
    }
}
