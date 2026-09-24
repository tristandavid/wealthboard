# WealthBoard (formerly "Portfolio")

Android app (Kotlin/Jetpack Compose, package `ca.tristan.portfolio` — the
package id is internal and wasn't renamed) for tracking your own investment
holdings. Every holding is entered by hand — there is no institution
sign-in or page scraping of any kind. ETFs and stocks with a ticker are
priced live from Yahoo Finance; everything else (seg funds/variable
annuities, mutual funds, cash) uses the price you enter.

## Layout: 5 tabs

Portfolio, Dividends, Reports, News, Menu. Reports has its own section below.

News replaced Markets in the bar rather than being added as a sixth tab — five
is what a phone's navigation bar fits without the labels wrapping. The Markets
dashboard is still there; it moved under Menu → Markets & Watchlist.

Bottom-nav labels are deliberately one short word each. With five destinations
a phone gives each item roughly 70dp, and a two-word label ("My Portfolio")
wrapped to a second line, which grew the bar and clipped the tabs at both
ends — so the labels are single words at 10sp with `softWrap = false`.

- **Dashboard** — a worldwide market-closures banner (US + Canadian
  exchanges, computed locally — see "Market calendar" below), a Quotes
  section, and a Stock Futures section underneath it (any ticker ending in
  Yahoo's "=F" suffix — ES=F, YM=F, NQ=F, RTY=F are seeded by default).
  Both sections show a Ticker/Last Price/Change header row over each entry
  (name on its own line, then ticker/price/change lined up in three
  columns). Tap + to search by symbol, company, or index name (stocks,
  ETFs, indices, futures, forex, crypto all match); tap a row to open its
  detail screen — price, a chart with 1D/1W/1M/6M/YTD/1Y/5Y/All ranges, key
  stats (open, day/52wk high-low, volume), and a ⋮ menu to rename the
  display name or remove it.

  The Dashboard's sub-tabs are **Markets**, **Hot Stocks**, **My Holdings**
  and **Cryptocurrencies**. Hot Stocks replaced the old Top Gainers / Top
  Losers pair — see "Hot Stocks" below. My Holdings lists the user's own
  positions priced live; note that its branch has to be checked *before* the
  watchlist-empty branch, because the watchlist list is empty by construction
  on that tab and an empty-first ordering silently swallowed every holding.
- **My Portfolio** — total portfolio value and a day-change line in the
  headline card, then a separate **Portfolio value** card carrying the trend
  line with the same 1D/1W/1M/6M/YTD/1Y/5Y/ALL range selector a single stock
  gets. The chart used to be an unlabelled sparkline inside the headline card,
  where it could not be read against any particular period. Those eight chips
  each take an equal share of the row (`weight(1f)`) rather than sizing to
  their own text — intrinsically-sized chips needed more width than the card
  has, so the row scrolled and 5Y/ALL sat outside its right edge, reading as a
  layout break rather than as something to swipe. Below that: an
  allocation breakdown and the holdings list. The tab used to also carry its
  own monthly/yearly income chart, but that duplicated the Dividends tab's
  own (and more complete) income view, so it now lives only there. Tap + to
  add a holding or create a new account; the Add/Edit Holding screen's Ticker
  field suggests matches as you type (same search as the Dashboard, with the
  same logo/flag treatment described under "Ticker search and logos" below),
  and can autofill the name and holding type from what you pick.
- **Dividends** — upcoming dividends (estimated ex-dividend date, pay date,
  and yield from Yahoo) for every owned stock/ETF that has a ticker, plus
  the dividend-income tracker: record a payment against any holding
  (amount, optional per-unit, date, note), with running totals. The goal,
  received/monthly/yearly totals and the income chart all sum every holding
  across every currency, converted into the reporting currency (see "Money,
  currency and FX" below); individual Upcoming Dividends and Dividends
  Received rows stay in the holding's own currency, with the converted
  equivalent shown underneath.

## Hot Stocks

The Dashboard's Hot Stocks tab answers "what is the market actually trading
today" in one list, replacing the Top Gainers and Top Losers tabs — two tabs
that each told half the story, and neither of which was the question.

`YahooQuoteClient.fetchHotStocks()` merges three predefined screeners
(`most_actives`, `day_gainers`, `day_losers`), de-duplicates by symbol keeping
whichever copy carries volume data, and ranks the union by a heat score:

    heat = |change%| × (1 + ln(1 + relativeVolume))

where `relativeVolume` is today's volume over the stock's own 3-month average,
clamped to 0.25–12. Size of the move alone ranks a thinly-traded microcap that
jumped 40% on no volume above Nvidia on an earnings day, which is not what
anyone means by "hot"; weighting by how unusual the volume is fixes that. The
log damps the volume term so a 10x day counts for more than a 2x day without
swamping the price move entirely, and the clamp stops one absurd volume print
from carrying a 1% move to the top.

Losers are included on purpose: a stock down 20% on ten times its usual volume
is one of the hottest things on the tape, and a list that is only ever green is
a worse answer than an honest one.

A junk floor (price ≥ $1, volume ≥ 200k when known) runs before the ranking.
The gainers screen surfaces sub-dollar microcaps that print 40% on a few
thousand shares; the heat score demotes them, but a large enough move still
carried one into the top ten, and filtering is more reliable than tuning the
formula to bury them.

## Appearance (light / dark / system)

Settings → Appearance picks Light, Dark or System default (the default, which
follows the device). The choice is stored in `wealthboard_prefs` under
`theme_mode` and exposed as `PortfolioViewModel.themeMode`; `MainActivity`
collects that flow inside `setContent`, so switching repaints immediately
rather than on next launch.

The app was light-only before this because the dark scheme defined only about
a dozen roles and everything else fell through to Material's baseline dark
palette — a cool purple-grey that clashed with the navy. Card containers were
the worst of it: `CardDefaults` resolves to `surfaceContainerHighest`, so
without the `surfaceContainer` ladder every card in the app came out grey.
Both schemes now define every role the app paints with, plus those the Card
and Surface defaults pull in implicitly.

Screens needed no changes: the hardcoded `Color.White` text throughout sits on
dark gradients (the value card, the dividend goal card, the lock screen) that
are dark in either theme, and nothing paints a literal light background —
table headers and dividers already went through `surfaceVariant` and
`outlineVariant`.

## Charts and the portfolio value series

**Axis labels.** X-axis label positions are measured and collision-checked
rather than drawn blind at five fixed steps. A format like "10:00 AM" is wider
than the gap between steps on a phone-width card, so the opening pair printed
on top of each other ("9:3010:00 AM"), and the closing label — nudged left to
stay inside the plot — ran into its neighbour at the other end. The first and
last are always kept because they carry the range; the ones between are dropped
whenever they would touch, and on a very narrow chart it degrades to just the
two ends.

Y-axis numbers drop cents once the values pass 1000 and gain a thousands
separator ("89,461" rather than "89460.87"). Cents are signal on a $61 share
and noise on an $89,000 portfolio, where they only widened the label enough to
crowd the gutter.

**The plot sizes itself to what it draws.** The right gutter is measured from
the widest y-label actually being rendered (clamped 24–72dp) rather than fixed
at 56dp, which had been sized for the widest thing it might ever need to hold
and left a strip of dead card on every chart that didn't. The left inset is 6dp
— just enough that the stroke and the opening label aren't clipped. Together
those hand about 24dp back to the plot on a phone. The volume band is likewise
reserved only when some bar actually carries volume: the portfolio chart has
none, and holding 15% of the height open for it left an empty strip under the
dates that read as broken layout.

Plot geometry is computed **once**, above the Canvas, and shared by the drawing
pass and by scrub hit-testing. They used to be derived independently — the draw
from fixed dp insets, `indexForX` from guesses of 2% and 16% of the width — so
the crosshair reported the wrong bar, by up to two positions near the edges.

The Portfolio tab's chart is the same `StockAreaChart` the quote detail screen
draws, fed the value series as `HistoryBar`s. It was a bare sparkline before,
deliberately axis-free — which meant no value scale down the side and no dates
along the bottom, so the line showed shape and nothing else. Reusing the real
chart also makes a holding's chart and the whole portfolio's read as one
control, which the shared range chips already implied.

`portfolioValueSeries(range, interval)` fetches each holding's bars and
evaluates them on a shared timeline: at every timestamp any holding has a bar
for, each position contributes its most recent price at or before that instant,
forward-filled. The series begins only once every holding has a price, so the
chart does not step up as each one's history starts.

The obvious shortcut — bucket bars by calendar day and sum what lands in each
bucket — is wrong for any intraday interval, and was. On 1D/30m every bar falls
in the same day, collapsing the series to one point ("Not enough price history
for this range yet"); on 1W/1h it summed roughly seven hourly prices per
position per day and reported the portfolio at several times its real value.
Bucketing only looked correct at daily intervals, where one bar per day made
the sum a no-op — which is why it survived until the range selector was
exposed on the Portfolio tab.

**Intraday axis timezone.** `Quote.exchangeTimezone` carries
`meta.exchangeTimezoneName` from the chart endpoint, and 1D/1W axis labels are
formatted in it rather than the device zone. A London session runs 08:00–16:30
local; drawn in the viewer's own timezone the FTSE read as trading 3:00 AM to
11:30 AM for someone in Toronto. Exchange-local is what every finance app plots
and what the day high/low figures under the chart already refer to. When the
exchange zone differs from the device's, the chart says so underneath. Ranges
of a day or longer stay in device time, where shifting a calendar date only
invites off-by-one dates at the boundaries. An unrecognised zone id falls back
to the device zone rather than silently resolving to GMT.

## Market news

News is regional plus US. `fetchMarketNews(homeCountry)` always includes the US
core feeds — a Canadian or British investor still needs to know what the S&P
did — and layers local outlets on top based on the home market resolved from
the device region (CA, GB, AU, IN, DE, SG, PH, NZ, IE, ZA have their own
feeds; anything else gets BBC Business for non-US context).

**Thumbnails.** Feeds that ship a `<media:content>` / `<media:thumbnail>` /
`<enclosure>` element are read from it; the rest embed the image as an `<img>`
inside the item's HTML, and `content:encoded` is collected for the same reason.
That fallback used to be handed the *stripped* description — every tag already
removed — so it could never match, and every Financial Post, CBC or other
WordPress-based story rendered with no picture. The raw HTML is now kept
alongside the stripped copy for extraction. Image URLs are also upgraded to
https: the app forbids cleartext traffic, so an `http://` or protocol-relative
thumbnail was refused by the platform and left a blank space with no error.

**Text is accumulated, not first-chunk-wins.** `XmlPullParser` returns long or
entity-containing text as several consecutive TEXT events. The parser used to
keep only the first non-empty chunk per element, which silently truncated
values at the first `&amp;` or buffer boundary: a title like "Rogers &amp; Shaw
close deal" became "Rogers", and an `<img>` sitting past the first chunk of a
long description was thrown away — which is why *some* stories showed a picture
and others did not, with no obvious pattern. Every chunk is now appended and
the result trimmed once at `</item>`.

Articles behind an account wall are dropped at the feed level by
`isFreelyReadable()`, matched on the article's host. Tapping one used to open
"Create a FREE account or log in to continue reading" inside the in-app
browser, which is a dead end — the reader cannot usefully sign in there and has
no way to the story. A headline that cannot be read is worse than no headline.
The investing.com feed was removed outright for this reason; the rest are
filtered by host so the same rule applies to anything a feed syndicates.

**The display caps mattered more than the sources did.** Each section used
to render its lead story plus `take(6)`, so a category holding forty
headlines showed seven and dropped the rest — which meant widening the
source list upstream changed nothing on screen. A `LazyColumn` only composes
what is visible, so the sections now hand it everything they have (with a
40-item ceiling per section purely to stop one runaway feed dominating the
page), and Dividend News' "Your Holdings" section is uncapped.

Both screens also `join()` the refresh they kick off. `refreshMarketNews()`
returns a `Job` the instant it is called, so `refreshing` flipped back to
false before a single feed had answered and the empty state read "No
headlines right now — pull refresh to try again" during the seconds the
fetch was actually running.

**Fetched in parallel, not one feed at a time.** Both feeds run every RSS
source (and Yahoo's JSON news search, below) concurrently via `runParallel()`
rather than sequentially. Sequentially, a single slow or dead host held up
every source queued behind it — with a ~10s default timeout and up to a dozen
sources, a couple of ailing feeds were enough to make the tab read as "almost
nothing" even though most sources were fine; they just never got a turn
before the caller gave up waiting on the whole batch. A feed that fails or
times out now just contributes nothing, without holding anyone else back.

**Yahoo's JSON news search is a first-class source, not just a fallback.**
`fetchYahooNewsSearch()` hits the same endpoint Yahoo Finance's own clients
use, for a free-text or per-symbol query. Market News blends it in alongside
the RSS feeds; Dividend News uses it for dividend/income queries and for
each of the user's own holdings. Both used to depend on the classic
`feeds.finance.yahoo.com/rss/2.0/headline` feed for that per-symbol
coverage — Yahoo retired it years ago, and it now answers every request with
an empty (but HTTP-200) channel, a failure indistinguishable from "no news
today". That silent failure, combined with Dividend News's keyword filter
having little else to match against, is why Dividend News in particular used
to read so thin.

That keyword filter now has a floor. It matches headlines only, and plenty
of genuine income coverage never puts "dividend" in its title ("Realty
Income raises guidance"), so on a slow news day it could cut dozens of
fetched stories down to two. Below a dozen survivors the feed falls back to
everything its sources returned — those sources are already dividend- and
holdings-targeted queries, so showing them beats showing almost nothing.

## Reports (tab 4)

A **Reports** tab builds a full performance report from the transaction ledger
and exports it as a multi-page A4 PDF.

Pick a period (1M through 5Y, YTD, or since inception), optionally narrow to a
single holding, choose a benchmark (SPY/QQQ/VTI/DIA or none), and toggle which
sections the PDF should carry. Scope is by holding rather than by account —
"how has this position done" is the question people actually ask of a report,
and the account split is already in the allocation section. "Generate report" computes the numbers and shows
a summary on screen; "Export as PDF" writes the document and opens the system
share sheet.

What it computes:

- **Time-weighted return** — daily-linked, with external flows removed, so it
  is comparable to a benchmark. Flows are treated as arriving at the END of the
  day: a buy is recorded at that day's own price, so the new money did not
  participate in the move from the previous close and must not be credited with
  it. Crediting it (start-of-day) understates return on days money came in.
- **Money-weighted return (XIRR)** — solved by bisection rather than
  Newton-Raphson, because irregular hand-entered flows can flatten the
  derivative and send Newton off to infinity; printing no number beats printing
  a wrong one.
- **Risk** — annualized volatility, Sharpe, maximum drawdown (measured on the
  return index rather than raw value, so a deposit cannot mask a real decline),
  beta, correlation, best/worst month.
- **Benchmark** — excess return and Jensen's alpha against the chosen index.
- **Per-holding** — cost basis, market value, unrealized/realized/income split,
  weight, and each position's share of the total gain.
- **Realized gains** — average-cost method, dated, for tax season.
- **Income** — dividends by month and by holding, yield on cost, DRIP vs cash.
- **Allocation** — by type, account and position, with a concentration flag.

How the portfolio is valued: market value of every position **plus** dividend
cash not yet reinvested. Holding uninvested distributions inside the portfolio
is what makes this a total-return measurement — otherwise a cash dividend reads
as the portfolio shrinking, and a DRIP reads as an outside deposit. External
flows are therefore buys and sells only; DRIPs are internal transfers. The
opening balance excludes the first day's own buys, which are contributions
rather than starting capital.

Untickered holdings (seg funds, mutual funds, cash) are held flat at their
entered price and are named in the PDF's notes, so a reader knows which part of
the curve is estimated rather than priced.

**Holdings with no transaction history.** The holding row is the position of
record; the transaction table is an optional audit trail of how it got there. A
position typed into the Add/Edit Holding screen has units and a cost basis and
no transactions at all, so rebuilding positions purely from the ledger left
every such holding at zero units and produced a report of zeros throughout —
value, return, risk, allocation. The calculator now reconciles the two: where
the replayed ledger falls short of the holding's actual units, the difference
is seeded as an opening balance from the entered units and cost basis.

That opening is dated one day *before* the window, not at `createdAtMillis`.
The real purchase date is unknown, and `createdAtMillis` only records when the
row was typed in; dating the position inside the window would book the whole
thing as a fresh contribution, flatten the return to nothing and draw a chart
that sits at zero and then jumps. Treating it as capital already in place
measures what the reader wants — how the things they hold have performed — at
the cost of assuming the quantity was held throughout, which the report states
in its notes. For the same reason inception is taken from recorded trades only:
flooring the window at `createdAtMillis` would hand someone who set the app up
this morning a one-day report.

Reconstructed openings carry a negative transaction id so they stay out of the
Activity tallies — the user did not place those trades.

The PDF is drawn directly with `android.graphics.pdf.PdfDocument` and Canvas —
no third-party PDF dependency (iText is AGPL, which does not suit a
closed-source app), works with no network, and matches the app's navy/gold
identity. It is shared through a `FileProvider`
(`${applicationId}.fileprovider`, see `res/xml/file_paths.xml`) writing into
`cacheDir/reports/`.

Code lives in `app/src/main/java/ca/tristan/portfolio/report/`:
`ReportModels.kt` (shapes), `PerformanceCalculator.kt` (the math) and
`PdfReportGenerator.kt` (the document), with the UI in
`ui/screens/ReportsScreen.kt`. No database migration was needed — everything is
derived from the existing `transactions`, `holdings` and `dividend_payments`
tables.

## Market calendar

The Dashboard's closures banner is **not** fetched live — there's no free,
reliable "is the market open" API this app calls. Instead
`data/MarketCalendar.kt` computes the well-known, uncontroversial holidays
for US (NYSE/NASDAQ) and Canadian (TSX) markets algorithmically (nth-
weekday-of-month rules, and the Anonymous Gregorian algorithm for Easter/
Good Friday), so it stays correct for any year rather than going stale.
Half-day early closes aren't modeled, only full-day closures.

## Manual data entry, top to bottom

Earlier builds could sign in to Sun Life and Equitable Life and scrape
holdings from their pages. That entire subsystem — the in-app sign-in
WebView, the JS extractors, and the scrape-result parsing — has been
removed. Every account and holding is now created and updated by hand from
the My Portfolio tab, the same way ETFs/stocks always could be.

## Rebrand: WealthBoard

- App name changed from "Portfolio" to "WealthBoard" everywhere it's shown
  (launcher label, top bars) — the Kotlin package (`ca.tristan.portfolio`)
  and `applicationId` were left alone since renaming those is a much
  bigger, riskier change than a display rename and wasn't asked for.
- Launcher icon: a navy field with a gold/ivory rising bar chart mark
  (`app/src/main/res/drawable/ic_launcher_{background,foreground}.xml`,
  wired through the adaptive icon in `mipmap-anydpi-v26/`).
- Material3 theme (`ui/theme/Theme.kt`) — a navy/gold finance palette
  instead of Compose's stock purple defaults, applied via a shared
  `WealthBoardTopBar` across every screen, card elevation/rounding, and
  green/red color coding for gains and losses.
- Holding type labels are human-readable instead of raw enum constants:
  ETF stays fully capitalized ("ETF"), and seg funds read "Seg
  Funds/Variable Annuities" (`HoldingType.label()` in `Entities.kt`).

## Money, currency and FX (v1.3)

Every amount in the app goes through one formatter, `ui/format/Money.kt`,
and the symbol always names the currency: **CA$** for Canadian dollars,
**US$** for American, **A$** for Australian. A bare `$` is never printed —
a portfolio holding both XEQT.TO and AAPL has no way to read it.

Two rules about which currency a figure is in:

- A **per-holding** figure is in the holding's own currency. That is the
  number on the statement, so a US position is never restated as Canadian.
- A **cross-holding total** is converted into the reporting currency
  (Settings → Base currency) before anything is added up. Adding US dollars
  to Canadian ones at face value produces a number that is money in neither.
  This covers portfolio value, the whole Reports pack — which was summing
  unconverted, making its totals, allocation weights, returns, risk and
  benchmark comparison all wrong for a multi-currency portfolio — and every
  aggregate on the Dividends tab (Passive Income Goal, Received/Monthly/
  Yearly Income, Historical Income): each sums every holding regardless of
  what it trades in. The performance report applies today's rate to
  historical values too, and says so in its warnings: it measures investment
  return, not currency movement.

The Add Transaction form labels its price field with the currency the
listing actually trades in — resolved from the quote, or from the exchange
suffix before the quote lands — and shows the reporting-currency equivalent
underneath, with the rate used. `currencyForTicker()` carries the suffix
table behind that; it knows the Nordics (`.ST` → SEK, `.OL` → NOK,
`.CO` → DKK), Mexico, Taiwan, Singapore, Thailand, Türkiye, Israel, Poland
and South Africa as well as the majors, because a suffix it did not
recognise fell through to the CAD default and every figure computed from
that holding then counted kronor as dollars.

The holding detail screen shows figures in the reporting currency by
default, with a toggle next to the header (visible only when the holding
trades in a different currency) to switch to viewing it in its own trading
currency instead. Three rules hold there: the toggle restates the WHOLE
page — per-unit amounts, the payout schedule and both charts included, not
just the headline price; figures are never labelled in a currency they have
not actually been converted into, so a missing rate falls back to showing
(and labelling) the native currency rather than stamping "CA$" on kronor;
and anything that compares a holding against the portfolio — portfolio
weight above all — converts both sides into the reporting currency first.
That last one is why a Stockholm position in a Canadian portfolio used to
report a weight around seven times too large.

`FxRates.rateOrNull()` triangulates through any currency both sides are
already quoted against, so a pair where neither side is the reporting
currency (a US-dollar dividend logged against a Swedish listing) still
resolves, using rates already in the cache and no extra request. On the Dividends tab, individual
Upcoming Dividends and Dividends Received line items are the exception to
the cross-holding-total rule: each stays in its own original currency (it is
a specific, dated cash amount, not a total), with the reporting-currency
equivalent shown underneath it.

## Dividend projection

`data/DividendForecast.kt` is the single forward projection, shared by the
holding detail chart and the Dividends tab's FWD view (`projectedIncomeByMonthInBase`
converts each holding's projection into the reporting currency before
summing). Previously each screen had its own copy and they disagreed.

It projects from the fund's own record rather than repeating one number:
each payment in the last twelve months is assumed to repeat on its own
anniversary, grown by an assumed 3 %/yr. That matters because real
distributions are seasonal — XEQT pays a token Q1 and a large Q4 — and
dividing an annual rate into four equal parts overstates the small quarters
threefold. Payments also step in whole calendar months, not `365 /
frequency` days, which drifts payments out of their months over a ten-year
horizon. A security with less than a full cycle on record falls back to an
even cadence from its next announced date.

Dividend **history** is fetched at Yahoo's `max` range, not 5y. Five years
started Apple's record in 2021 (it has paid since 1987) and left the
five-year growth figure with no earlier window to compare against, so
"Div Growth, 5 Years" read "—" for every holding in the app.

## Charts

`ui/components/DividendBarChart.kt` is the shared income chart: a heading
carrying the currency, a MONTH/YEAR selector, received income stacked under
forecast income under the DRIP uplift, the value printed above each bar, a
labelled y-axis and a legend. It is used on the Dividends tab (summed across
every holding, in the reporting currency) and the holding detail screen (a
single holding, in whichever currency that screen is currently showing) so
the two read as one app. It used to also appear on the Portfolio tab, but
that was a duplicate of the Dividends tab's own chart and has been removed
from there.

Its MONTH view spans six months back to six months forward. It used to be
the twelve months *ending today*, which made a section titled "Recent and
Upcoming Dividends" structurally incapable of showing anything upcoming.

**Value labels sit above the bars, and the headroom for them is reserved at
the top.** Every bar chart here reserves a strip for the figure printed over
each bar. That strip used to be subtracted from the BOTTOM of the canvas
(`plotH = height - labelRoom`, bars standing on `plotH`), which left dead
space under the axis and none above it: a bar at the axis maximum ran to the
very top of the canvas, its label was pushed to a negative y, and the
`coerceAtLeast(0f)` guarding that stamped the text at y=0 — on top of the
bar. On AAPL's dividend history that drew the 2025 figure as dark text on a
dark bar, invisible, with 2024's clipped against its own bar top.

**The Dividends tab's income charts are stacked per holding.** Monthly Income
(TTM and FWD) and Historical Income (Monthly and Yearly) split each bar into
one segment per holding, with a legend underneath and a per-holding
breakdown when a bar is tapped. Colours come from `SeriesPalette`
(`ui/components/IncomeSeries.kt`), assigned by position in the sorted list
of every holding in the portfolio — not by hash, which would let two
holdings collide on one colour, the single failure a legend cannot explain,
and not from the holdings present in one chart's own window, which would let
a holding be blue on the TTM chart and violet on the yearly one because a
third holding happened to pay nothing last year.

## Ticker search and logos

A small shared vocabulary makes ticker search, the quote detail screen, and
the holding detail screen read as one product instead of three:

- **`TickerLogo`** (`ui/components/WealthBoardUi.kt`) renders a company logo
  in a circular frame, falling back to a coloured initial (hashed from the
  ticker, so a given symbol always gets the same colour) while the logo
  loads or if none is available. It appears on the quote detail header, the
  holding detail header, and every search result row (Add Transaction, the
  Dashboard's search/add-quote dialogs).
- **`TickerFlag`** (`ui/format/TickerFlag.kt`) maps a ticker/exchange/currency
  to a flag emoji: index tickers use `MarketIndices`' own mapping, everything
  else falls back through the exchange suffix (`.TO`, `.L`, `.DE`, `.PA`,
  `.T`, `.HK`, `.AX`, `.NS`, and about three dozen more), then the exchange's
  display name, then the trading currency, defaulting to 🇺🇸. Every screen
  goes through it. The holding detail, Markets and quote detail screens each
  used to keep a private copy of this list — each shorter than the last, each
  defaulting to 🇺🇸 — so a Stockholm listing came back Swedish in search
  results and American in the header of its own detail page.
- Logos come from Finnhub's `/stock/profile2` endpoint (`Quote.logoUrl`,
  cached in memory), which mainly covers US/major-exchange listings; a
  standalone `fetchLogo(ticker)` path serves search-result rows that never
  fetch a full quote.

**International tickers used to fail to search, price or flag correctly.**
Finnhub's `/search` endpoint (used first, previously) matches against
ISIN/CUSIP as well as ticker, and for a security not primary-listed in the
US it could return the ISIN string itself as the result's `symbol` — with no
`exchange` — instead of a tradable ticker. An ISIN cannot be quoted or
charted, and with no exchange there is nothing to key a flag off either.
Search is now Yahoo-first (`searchSymbols()`), which reliably returns a real
tradable symbol plus a display exchange name; Finnhub search is kept only as
a fallback, and hardened to prefer its `displaySymbol` field over `symbol`
when both are present.

## Building

### Toolchain — build on JDK 21

**This project must be built on JDK 17–23.** Not "whatever is newest". It
asks for 21, because that is the one Android Studio already has.

Kotlin 1.9.24 bundles a copy of IntelliJ's platform utilities whose
Java-version parser predates JDK 24. On a newer JDK the build fails inside
KSP with:

```
e: java.io.IOException: java.lang.IllegalArgumentException: 25.0.3
   ... at JavaVersion.parse(JavaVersion.java:305)
   ... at IncrementalContextBase.updateCachesAndOutputs
Execution failed for task ':app:kspDebugKotlin'
> Internal compiler error.
```

That number is the JDK's own version string, not anything in this project —
the compiler crashes while closing KSP's incremental caches because it cannot
parse the version of the JVM it is running on. No amount of editing the
Kotlin will fix it.

`app/build.gradle.kts` declares `kotlin { jvmToolchain(21) }`, so Gradle
launches the Kotlin compile daemon on JDK 21 no matter which JDK started the
build. That makes the requirement part of the project rather than part of
someone's IDE settings. Only the JDK that *runs* the compiler changes — the
bytecode target stays 17, set by `compileOptions` and `kotlinOptions
.jvmTarget`, so nothing about the shipped app moves.

**Why 21 rather than 17.** The usable window is AGP 8.9's floor of 17 up to
the 24 where Kotlin's parser gives out, so either works; the tiebreaker is
which one is already on disk. Android Studio installs a JetBrains Runtime 21
into `~/.jdks` as a matter of course and Gradle auto-detects that directory,
while a 17 generally has to be fetched deliberately. Asking for 17 on a
machine that had a perfectly good 21 sitting in `~/.jdks` failed with
`Cannot find a Java installation ... matching {languageVersion=17}` — and the
standard remedy for that, the Foojay toolchain-download resolver, is itself
broken on Gradle 9 (`JvmVendorSpec does not have member field IBM_SEMERU`,
a constant Gradle 9 removed). Asking for the JDK that is already there avoids
both problems rather than trading one for the other.

Two things that bite even after the toolchain is in place:

1. **Stop the daemons.** A Gradle/Kotlin daemon already running under the old
   JDK keeps serving builds and will keep crashing. From the project root run
   `gradlew --stop` (or Android Studio's elephant icon → **Stop Gradle
   Daemons**) before rebuilding. This is the usual reason "I changed the JDK
   and it still fails".
2. **Clear the poisoned KSP cache.** The failed runs left half-written
   incremental caches. **Build → Clean Project**, or delete `app/build/`, then
   **File → Invalidate Caches / Restart**. This matters more than it sounds:
   the crash happens *while closing* those cache files, so a run that dies
   there leaves them in a state the next run can trip over even after the JDK
   underneath is correct — which reads as "I fixed the JDK and nothing
   changed".

`gradle.properties` also sets `ksp.incremental=false` and
`kotlin.incremental=false`, which stop KSP from memory-mapping those caches at
all and so make the crashing code path unreachable regardless of which JDK
wins. That is a belt to the toolchain's braces, not a replacement for it —
both lines carry a comment saying to turn them back on once the project builds
cleanly, since non-incremental rebuilds are slower.

If Gradle reports that it cannot find a Java installation matching
`{languageVersion=21}`, there is no JDK 21 on the machine: **Settings →
Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK → Download
JDK… → 21**. Gradle auto-detects the JDKs the IDE downloads, so that one step
satisfies the toolchain as well. Do not reach for the Foojay download
resolver to fix it — on Gradle 9 that plugin fails on a removed API before it
can help.

Note that this error and the KSP crash above are the same root cause wearing
two faces: the toolchain exists to keep the compiler off a too-new JDK, and
this is what it says when the JDK it wants isn't there to move to.

Gradle should be on the 8.x line too (8.11.1 or later). AGP 8.9 was tested
against Gradle 8.x; Gradle 9.x is a major version with its own breaking
changes.

The alternative, if you would rather stay on a current JDK, is upgrading to
Kotlin 2.x — which also means adopting the separate Compose compiler Gradle
plugin and a matching KSP version. That is a real migration, not a version
bump, so it is not done here.

### Building

This environment couldn't reach Google's Maven repo, so the APK can't be
built here. `.github/workflows/build-apk.yml` builds it in GitHub Actions
with the toolchain pinned to JDK 21 and Gradle 8.11.1: push this to a repo
(or use `workflow_dispatch`) and download the `wealthboard-debug-apk`
artifact from the workflow run. That workflow only builds the debug APK —
see the next section for the release `.aab`.

### Play Store / release signing and the `.aab`

**Yes, this project can produce a Play-Store-ready `.aab`** — `bundleRelease`
is a stock Android Gradle Plugin task, nothing project-specific has to be
added to get it, and it picks up the same `jvmToolchain(21)` fix as every
other build variant since that's declared once at the top of
`app/build.gradle.kts`, not scoped to `assembleDebug`. `compileSdk`/
`targetSdk = 36` already satisfy Play's current requirement, and the release
build type already turns on `isMinifyEnabled`/`isShrinkResources`. The one
thing that was actually missing was a signing configuration — an unsigned
bundle can't be uploaded to Play — which is what this section adds.

**Generate a keystore (one time only):**

```
keytool -genkeypair -v -keystore wealthboard-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias wealthboard
```

Put the resulting `.jks` file *outside* the repo (or anywhere the `.gitignore`
below covers), and keep it and its passwords somewhere durable — password
manager, not just the machine that generated it. If you lose it and haven't
enrolled in Play App Signing, you cannot ship an update to the same app
listing ever again; Google cannot recover or reset it for you.

**Wire it up:** copy `keystore.properties.example` (repo root) to
`keystore.properties` and fill in `storeFile`/`storePassword`/`keyAlias`/
`keyPassword`. That file is gitignored — it and the `.jks` itself must never
be committed. `app/build.gradle.kts` reads it at configure time and only
attaches a `signingConfigs["release"]` when the file exists, so a checkout
without it (a fresh clone, CI) still builds — `assembleDebug` is completely
unaffected either way, and even `bundleRelease` still succeeds, just
producing an unsigned bundle.

**Build it:**

```
./gradlew bundleRelease
```

Output lands at `app/build/outputs/bundle/release/app-release.aab`. Upload
that to Play Console. (Android Studio's **Build → Generate Signed App
Bundle** wizard is an alternative that doesn't need `keystore.properties` at
all — it prompts for the keystore interactively each time — but the
command-line path above is what the CI-workflow route would need if you ever
add a signing job there, which this repo does not do yet since it would mean
putting the keystore into GitHub Secrets, a step only you should decide to
take.)

**Two things Play checks that are about the *listing*, not the code, and
so aren't fixable in this repo:** a privacy policy URL and the Play Console
"Data safety" form (relevant here because Firebase Auth/Firestore and AdMob
are both integrated) are filled in on Play Console itself, not in Gradle.
`versionCode`/`versionName` are already bumped for this change (7 / "1.6")
and just need to keep strictly increasing on every future upload, which is
the existing pattern in this file.

## Sign in with Apple

Apple ships no Android SDK. On Android, "Sign in with Apple" is Apple's own web
page in a Custom Tab, driven by Firebase's `OAuthProvider` — which is why
`FirebaseManager.signInWithApple` takes an Activity and the Google one does not.

Two things have to exist on Apple's side before the page will load at all, and
neither is the iOS App ID:

1. **A Services ID** in the Apple Developer account (Certificates, Identifiers
   & Profiles ▸ Identifiers ▸ Services IDs), with Sign in with Apple enabled
   and its return URL set to the
   `https://{your-project}.firebaseapp.com/__/auth/handler` address the
   Firebase console shows you.
2. **A Sign in with Apple key** (Keys ▸ + ▸ Sign in with Apple). Its key ID,
   your team ID, the Services ID and the downloaded `.p8` all go into Firebase
   console ▸ Authentication ▸ Sign-in method ▸ Apple.

Get either wrong and Apple serves an `invalid_client` page rather than
returning an error to the app, so that page is the symptom to recognise.

The same Apple account signing in here and on the iOS build lands on the same
Firebase user.

### Diagnosing a failure

The three that actually happen, and how each announces itself:

| What you see | What it means |
| --- | --- |
| Apple's own **`invalid_client`** page in the browser tab | Services ID or return URL wrong. No error reaches the app — it just sees the user come back empty-handed. |
| "Apple sign-in isn't switched on for this project yet" | Apple is not enabled under Authentication ▸ Sign-in method. Firebase reports this as `CONFIGURATION_NOT_FOUND`, which reads like a problem with the app; `FirebaseManager.appleError` translates it. |
| "You already have an account with that email address" | The same address was used with Google or a password first. Firebase keeps one account per email unless you link the providers. |

Anything else comes through verbatim rather than being swallowed.

### The button logos

Both marks are the official assets, not redrawings — each company's branding
guidelines require that, and a hand-traced logo looks wrong to everyone who
knows the real one.

- **Google** — `com.google.android.gms.common.SignInButton`, Google's own
  widget out of `play-services-auth`, which is already a dependency. It brings
  the multi-colour G, the wordmark, the paddings and the pressed states.
  Nothing to download. Its text and metrics are fixed; `setColorScheme` is
  driven off the actual background luminance, because the app has its own
  light/dark/system setting and `isSystemInDarkTheme()` gives the wrong answer
  whenever the user has overridden it.
- **Apple** — download the mark from Apple's "Sign in with Apple" resources
  page and drop it in as **`res/drawable/ic_apple_logo.xml`** (or `.png`).
  `LoginScreen` resolves it by NAME at runtime, so the project compiles
  without it and picks it up the moment it is there — no code change. Until
  then the black button carries its label alone.

## Backup & Restore

Menu ▸ Backup & Restore is visible **whether or not you are signed in**, and
the file half of it is deliberately not behind the rewarded-ad gate that guards
cloud sync. Everything in this app is hand-entered; losing it means retyping
every holding, transaction and dividend, so the safety net is not a premium
feature.

- **Export to a file** / **Restore from a file** — `LocalBackup` +
  the Storage Access Framework. No storage permission, no fixed folder: the
  file goes wherever the user keeps things. The rows are the same field-named
  maps `CloudBackup` already writes, so there is one serialiser to keep correct
  and a file exported today still restores after a column is added.
- **Cloud backup & restore** — unchanged, signed-in only, still gated.

Both restore paths refuse to apply a backup with no holdings and no watchlist,
because applying an empty one over a real portfolio would delete everything and
report success.

Note: the file format is Android-shaped and does not restore into the iOS
build, and the two platforms' Firestore layouts differ as well. Cross-platform
restore would need one agreed format on both sides.

## Project layout

- `app/src/main/java/ca/tristan/portfolio/data/` — Room entities/DAOs,
  `PortfolioRepository`, `MarketCalendar`
- `app/src/main/java/ca/tristan/portfolio/net/` — `YahooQuoteClient` (live
  quotes, price history, upcoming-dividend lookups)
- `app/src/main/java/ca/tristan/portfolio/ui/` — Compose screens
  (Dashboard/My Portfolio/Dividends tabs, account/holding detail, settings),
  bottom navigation, view model
- `app/src/main/java/ca/tristan/portfolio/security/` — app lock
  (biometric/PIN)
- `app/src/main/java/ca/tristan/portfolio/work/` — background quote refresh

## Where the data comes from

Three public sources, in this order, with the original direct calls kept
underneath as fallbacks rather than deleted:

1. **Finance Query** (`FinanceQueryClient`) — quotes, batch quotes, charts,
   symbol lookup and screeners. It is a server wrapping the same public data
   with a Redis cache in front, so the throttling lands on it rather than on the
   handset. That coupling is what used to take the news feed down whenever a
   price refresh got a 429.
2. **dividendhistory.org** — distribution dates and amounts, on its own
   transport with no shared rate limiter. It is the primary dividend source
   because it publishes real PAY dates (the chart endpoint has ex-dates only,
   and the gap runs from two days to six weeks) and marks declared-but-unpaid
   rows "unconfirmed/estimated", which is what keeps an announced distribution
   out of the received record. Coverage is NYSE, NASDAQ, TSX, TSX-V and NEO.
   Paths are case-sensitive: `/payout/tsx/XEQT/` resolves, `/payout/tsx/xeqt/`
   does not.
3. **Publisher RSS and the provider's news search** (`NewsClient`) — headlines,
   also on its own transport.

None of these needs an API key.

### Self-hosting Finance Query

The public instance is free but carries no SLA and no documented rate limit.
The project is MIT licensed and ships a Docker Compose file (server, Nginx,
Redis). Point the app at your own deployment without a rebuild:

    FinanceQueryClient.baseUrlOverride = "https://finance.example.com"

On iOS the equivalent is the `financeQueryBaseURL` user default.

### If a source disappears

Every call falls through. Finance Query unreachable means the direct chart and
search endpoints answer instead; dividendhistory.org unreachable means the
chart's `events.dividends` block answers, with pay dates left unknown rather
than invented. A dead server marks itself unavailable for two minutes so a
thirty-symbol watchlist does not pay the timeout thirty times over.

## Caching

Three layers, each matched to how fast its data actually changes.

**Persisted, in Room** — accounts, holdings, transactions,
dividends, watchlist, plus each holding's `lastKnownPrice`, `previousClose` and
the app's own `priceSnapshots`. This is what lets totals, the day change and a
seg fund's chart paint before any network call. FX rates persist separately so
a cold start never sums the portfolio unconverted.

**Persisted, in a separate cache directory** — news headlines and scraped
dividend payout calendars. Deliberately NOT in Room: adding tables to Room means a schema migration, for data that can be thrown
away at any moment. These live where the OS is free to delete them under
storage pressure, which is the right outcome for anything re-fetchable.

- News: one day. Past that a feed is history, not news, and an empty tab that
  fills in beats yesterday's headlines presented as today's.
- Payout calendars: twelve hours. Scraping them is the slowest thing the app
  does — up to four candidate URLs per ticker — and a fund declares a
  distribution a few times a year.

**In memory** — chart bars, keyed on ticker + range + interval. The chip row
invites exactly the pattern a cache is for: 1M, 1Y, back to 1M. TTL is 60s for
an intraday range, 5 minutes for a week, six hours for daily bars and longer.
Bounded at 120 entries.

### Two rules everything here follows

An empty result is never cached. Caching a failure would hold it in place for
the whole TTL, turning one bad minute into six hours of an empty chart.

A stale entry is a floor, not a liability. When a fetch fails, whatever is on
file is returned however old it is — a published pay date does not stop being
the pay date because the scrape failed this morning.

### Forcing a refresh

The refresh action in the top bar invalidates the relevant cache first. Without that a twelve-hour TTL would make
the gesture look like it had done nothing.

## Dividend dates — 18 September 2026

`resolveUpcoming` used to pick the longer of Yahoo's events block and the
dividendhistory.org page and take that source's dates with it. Yahoo carries
**ex-dates only**; the org page carries **pay dates**. For a fund like XEQT the
two records are the same length, so the tie-break was silently choosing the date
*type* — and because the iOS tie-break runs the other way, the two builds put
XEQT's year-end distribution in different months: December on Android, January
on iOS.

Pay dates are the right ones. Everything downstream of this list is about money
arriving — the Monthly Income bars, the trailing twelve-month total, the forward
projection — and money arrives on the pay date.

`payDatedHistory` now keeps Yahoo's reach (back to the security's first ever
distribution, where the org page only returns a recent slice) and re-dates it
from the org feed: an exact ex-date match within ±3 days supplies a real pay
date, and anything older than the org page falls back to the fund's own median
ex→pay gap. With no pay date anywhere it returns ex-dates unchanged rather than
inventing an offset. Overlapping rows are deduplicated within the same ±3 days,
so a distribution both feeds know about cannot be billed twice.

Fixing it here fixes every consumer: `events` on the holding screen is the same
list.

Still open, pending a decision: the distribution growth model (the Upcoming card
measures growth from the fund's record, the forward chart assumes a flat 3%), and
the "Estimated"/"Projected" labelling.
