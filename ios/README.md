# WealthBoard for iOS

A native SwiftUI port of the WealthBoard Android app (`ca.tristan.portfolio`,
v1.6.2). Same product, same palette, same numbers — rewritten in Swift rather
than wrapped or cross-compiled.

The app tracks a hand-entered portfolio: what you hold, what it's worth, what it
pays in dividends, and what tax is quietly coming off it.

---

## Opening it

```
open WealthBoard.xcodeproj
```

Requires **Xcode 16 or later** (the project uses file-system-synchronized
groups, so new `.swift` files are picked up automatically without editing the
project file). Deployment target is **iOS 16.0**.

Before running on a device, set your own team and bundle identifier:

- Target → Signing & Capabilities → Team
- `PRODUCT_BUNDLE_IDENTIFIER` is currently `ca.tristan.wealthboard`

There are **no external package dependencies**. A fresh clone builds and runs.

If you'd rather regenerate the project than trust a hand-written `pbxproj`,
`project.yml` is an [XcodeGen](https://github.com/yonaskolb/XcodeGen) spec for
the same target: `xcodegen generate`.

---

## What's here

| Android | iOS |
| --- | --- |
| Jetpack Compose | SwiftUI |
| Room + DAOs | `PortfolioStore` — one atomic JSON document |
| `PortfolioViewModel` (StateFlow) | `PortfolioViewModel` (`ObservableObject`) |
| OkHttp + coroutines | `URLSession` + async/await |
| `Canvas` charts | `Path` / `Canvas` charts |
| `colorScheme.primary` (navy / gold) | `Palette.primary` — same inversion |
| `PdfDocument` | `UIGraphicsPDFRenderer` |
| SharedPreferences | `UserDefaults` via `Prefs` |
| Firebase / AdMob | protocols in `Services.swift`, unimplemented |
| `AppLock` (PBKDF2 + biometric) | `AppLock` (PBKDF2 over CryptoKit + `LAContext`) |
| `WebView` in-app browser | `WKWebView` in `InAppBrowserView` |

### Source layout

```
WealthBoard/
  App/         WealthBoardApp, RootTabView (the five tabs)
  Design/      Theme (palette, typography), Components, Charts
  Format/      Money, TickerFlag, TradeTime
  Model/       Account, Holding, DividendPayment, PortfolioTransaction, …
  Data/        PortfolioStore, PortfolioRepository, FxRates,
               ExchangeZones, MarketIndices, MarketCalendar, DividendForecast
  Tax/         TaxRules — residency, treaty withholding, account treatment
  Net/         QuoteClient, RateLimitBackoff, RSSParser, LogoResolver
  Services/    Auth / CloudSync / Ads protocols and no-op implementations
  ViewModel/   PortfolioViewModel
  Report/      ReportModels, PerformanceCalculator, PDFReportGenerator
  Security/    AppLock — biometric / PIN gate, keychain-backed
  Screens/     One file per screen
  Resources/   Info.plist, Assets.xcassets
```

---

## What works

**News** — a tab of its own, with market headlines and dividend headlines
behind a segmented control. Sources are open feeds only (Nasdaq's category
feeds, CNBC, Yahoo Finance, NPR, plus a regional outlet); account-walled
publishers are dropped before the list is drawn, so a headline that appears can
be opened. It has its own HTTP transport, off the quote endpoint's rate limiter.

**Markets** (under Menu → Markets & Watchlist) — index rows with exchange-local
timestamps, intraday sparklines, index futures under the cash close, Hot Stocks
ranked by a volume-weighted "heat" score, My Holdings, crypto, ticker search
(including the symbol probe that finds listings the search index doesn't carry)
and market-holiday banners.

**Portfolio** — total value with day change and FX-conversion warnings, a value
chart over eight ranges, allocation by security with a donut drill-down, the
portfolio-wide tax-drag warning, and holdings grouped by currency with the
per-account split folded inside each row.

**Dividends** — passive-income goal (falling back to projected income when
nothing has been received yet), MTD/YTD received, the stacked monthly income
chart in trailing and forward modes, historical income by month or year, the
swipeable upcoming-payments deck, and the received-vs-projected chart with
optional DRIP compounding.

**Reports** — time-weighted and money-weighted (XIRR) returns, benchmark
comparison with beta and alpha, volatility, Sharpe, max drawdown, per-holding
contribution, realized gains, income and allocation — rendered on screen and
exported as a multi-page PDF.

**Taxes** — residency, per-account treatment, and the withholding notes. The
asymmetry the Android app was built around is intact: a Canadian's US dividends
are exempt inside an RRSP and withheld at 15% inside a TFSA with nothing to
claim them back against, while a US resident's Canadian dividends need a filed
Letter of Exemption that most people never file.

**Holdings** — price header with a reporting/native currency toggle, chart over
eight ranges, position stats (average cost, portfolio weight, contributions,
price return and total return), the tax card with the withholding estimate and
its dollar cost, dividend metrics (TTM yield, yield on cost, 1- and 5-year
distribution growth, most-recent and year-over-year change, frequency, annual
per unit), the fund's own per-unit distribution history by year, a trailing
yield line against its five-year average, the received-vs-projected income chart
with optional DRIP compounding, the payout schedule, and the transaction
history. Buy/sell/DRIP entry creates the holding on the fly for a ticker that
isn't in the portfolio yet.

**Accounts** — an account screen showing what is held in it, its value and its
tax treatment, reachable from the Portfolio tab. Add and edit a holding by hand
for funds with no ticker, or a position whose history was never recorded.

**Security** — an app lock using Face ID, Touch ID or the device passcode, or a
separate six-digit PIN hashed with PBKDF2-SHA256 and held in the keychain, with
escalating lockout after repeated failures. With the lock on, the app switcher
shows a branded cover instead of balances unless screenshots are allowed.

**Support** — a bug report form that posts to the report service when one is
configured and falls back to the mail composer otherwise, plus an admin console
for the account that owns them.

---

## Sign in and cloud backup — the Xcode side

`Services/FirebaseAuthService.swift` and `Services/FirestoreSyncService.swift`
are both wrapped in `#if canImport(...)`. They **compile to nothing** without
the Firebase package and become the real implementations the moment it is
added — `Services.bootstrap()` installs whichever is available at launch, so no
screen tests for the SDK itself. The app builds and runs either way; without
Firebase it is local-only, with the file backup under Menu ▸ Backup & Restore
as the safety net.

Five steps, once:

1. **Add the packages.** Xcode ▸ File ▸ Add Package Dependencies…
   - `https://github.com/firebase/firebase-ios-sdk` → **FirebaseAuth** and
     **FirebaseFirestore**
   - `https://github.com/google/GoogleSignIn-iOS` → **GoogleSignIn** and
     **GoogleSignInSwift** (only if you want Google sign-in; see below)

   Both are already declared in `project.yml` for anyone regenerating with
   XcodeGen.
2. **Check the plist.** `Resources/GoogleService-Info.plist` is in the repo —
   confirm it is a member of the app target under Build Phases ▸ Copy Bundle
   Resources. A plist that is present but not bundled fails at runtime, not at
   build time.
3. **Add the capability** — Signing & Capabilities ▸ **+ Capability** ▸ Sign in
   with Apple. Use that button; do **not** point `CODE_SIGN_ENTITLEMENTS` at
   `WealthBoard/WealthBoard.entitlements` yourself. The entitlement has to be
   registered against your App ID as well, and declaring it in the project
   without that fails signing before the app ever runs:

   > Cannot create a iOS App Development provisioning profile for
   > "ca.tristan.wealthboard"

   The + Capability button does both halves and writes its own entitlements
   file. The one in the repo is a reference for what that file should contain,
   which is why nothing points at it.

   This step needs a **paid** Apple Developer Program membership — a free
   personal team cannot use Sign in with Apple at all.
4. **Enable Apple in Firebase.** Firebase console ▸ Authentication ▸ Sign-in
   method ▸ Apple ▸ enable. Nothing else is needed for iOS; the Services ID and
   key are only required for the Android side.
5. **Flip the switch.** `AppleSignIn.isEnabled` → `true`.

   It ships `false`, so the Apple button is hidden until steps 3 and 4 are
   done. There is no runtime check that could replace it: without the
   entitlement the authorization sheet fails when tapped rather than reporting
   itself unavailable beforehand, so the only honest options are a button that
   works or no button.
6. **Run on a device.** The Apple sheet does not appear in the Simulator unless
   the Simulator is signed into an Apple Account.

Email/password sign-in, Firestore backup and everything else work after steps
1 and 2 alone — Apple is the only part that needs a provisioning capability.

### Google sign-in

Much less involved than Apple's, because nothing about it touches provisioning:
no capability, no App ID change, no paid membership. It needs the SDK and a URL
scheme, and both are already in place.

- **The package** — `GoogleSignIn` plus `GoogleSignInSwift` (step 1 above).
  Everything in `Services/GoogleSignIn.swift` is wrapped in
  `canImport(GoogleSignIn)`, so the project builds without it and
  `isGoogleAvailable` reports false, which hides the button.
- **The URL scheme** — already declared in `Resources/Info.plist` under
  `CFBundleURLTypes`, and it matches `REVERSED_CLIENT_ID` in
  `GoogleService-Info.plist`. `WealthBoardApp` hands the redirect back to the
  SDK with `.onOpenURL`. Miss either half and the sheet opens, the user signs
  in, and nothing happens — it reads as a hang, not a misconfiguration, so
  those two are the first things to check if that is the symptom.
- **Enable it in Firebase** — Authentication ▸ Sign-in method ▸ Google. The
  plist in the repo already carries `CLIENT_ID`, which means it is on; if a
  re-downloaded plist ever arrives without that key, Google has been turned off
  for the iOS app and `isGoogleAvailable` will go false on its own.

The button is Google's own `GoogleSignInButton`, with the real multi-colour G.
Their branding guidelines call for the supplied asset rather than a redrawing,
and a hand-traced G is recognisably wrong anyway. If the package is added
without `GoogleSignInSwift`, `LoginView` falls back to a plain text button
rather than drawing one.

Apple's mark needs nothing equivalent — `SignInWithAppleButton` draws it
natively.

### Backup & Restore

Menu ▸ Backup & Restore (`Screens/BackupView.swift`) is visible **whether or
not you are signed in**. The file export needs no account, no network and no
subscription, and it writes the same `PortfolioDocument` the cloud sync
uploads, so the two are interchangeable. Restore states the counts in the file
and replaces everything — it asks first.

Note: the file format is iOS-shaped and does not restore into the Android
build, and the two platforms' Firestore layouts differ as well. Cross-platform
restore is not wired up; it would need one agreed format on both sides.

## What's still stubbed

Two of the four third-party services are still **protocols with no-op
implementations** in `Services/Services.swift`:

- `AdService` — the rewarded-ad gate on Reports. `isAvailable` is false, so
  `ReportsView` skips the gate entirely rather than blocking a feature behind a
  video that can never play.
- `BugReportService` — submission and the admin console. Not configured, so
  `BugReportView` hands the same report to the mail composer instead, and
  `AdminReportsView` says there is nothing to list.

Every screen those services drive is fully built. Wiring either one up means
writing a type that conforms to the protocol and assigning it in `Services` at
launch. No screen needs to change.

The one thing not carried over from the Android build is background sync via
WorkManager: prices refresh when a screen appears or on pull-to-refresh.

## App icon

`Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png` is the same mark as
the Android launcher icon — navy ground, three ascending gold/ivory bars, rising
trend line — regenerated for iOS rather than exported from the APK.

The Android version is an adaptive icon on a 108dp canvas of which only the
centre ~72dp is ever visible once a launcher applies its mask. iOS icons are
full-bleed squares with the squircle applied by the system, so `make_icon.py`
re-fits the mark to the square instead of copying it at its Android
coordinates, which would have left it floating small in the middle.

To change it, edit the geometry constants at the top of `make_icon.py` and run
`python3 make_icon.py` (needs `cairosvg` and `Pillow`), then drop the new
`AppIcon.png` back into the appiconset. The file is deliberately opaque RGB with
no alpha channel — App Store Connect rejects an icon that has one.

## Verification status

This port was written without access to a Swift toolchain, so it has **not been
compiled**. What has been checked:

- Balanced delimiters, string literals and comments across every file
  (`check_swift.py`)
- Every `repository.x` call site resolves to a declaration on
  `PortfolioRepository`, and no key paths to tuple elements
  (`check_swift_extras.py`). Both added after real misses: the repository was
  missing **fifteen** members the view model called — the whole Transactions,
  Watchlist and network-pass-through section — and `map(\.1)` on a
  `[(Date, Double)]` is not valid Swift, which surfaces as "Cannot infer key
  path type from context" rather than as anything about tuples.
- No attribute applied twice to one declaration (`check_attrs.py`).
  Added after a real miss: a stale `@discardableResult` was left above a
  rewritten doc comment on `PortfolioRepository.refreshAllQuotes`, which put
  two of them on the same function with the comment in between. Swift allows
  comments there, so the pair does not look adjacent in an editor, and the
  error — "Duplicate attribute" — names the file rather than the line. A
  delimiter check cannot see it, hence a second script.
- Every SwiftUI container checked against ViewBuilder's ten-child limit
- Every type referenced in code resolves to a declaration in the module or a
  known SDK symbol
- Call sites checked against declarations for argument labels and order
- Known Swift pitfalls swept for deliberately: `try?` flattening, `await` inside
  `??` autoclosures, tuple destructuring in `ForEach`, `Angle` arithmetic,
  optional inference in bare ternaries

Expect a first build to surface a handful of small fixes — a missing import, a
type-checker timeout on a long expression, an argument label. The logic is the
part that was ported carefully; the mechanical errors are the ones a single
`⌘B` will name precisely.

`SWIFT_VERSION` is 5.0 and strict concurrency checking is left at the default
(minimal), which is what the actor/`@MainActor` boundaries here assume.

---

## Notes on the port

A few places where the Swift version deliberately differs:

**Persistence.** Room was replaced with a single atomic JSON document rather
than SwiftData or Core Data. The whole portfolio is a few kilobytes of
hand-entered data that every screen reads at once, so there is no schema to
migrate and no partially-loaded state to reconcile. `PortfolioStore` is an actor,
so two screens editing at once can't interleave a read-modify-write.

**Charts.** Hand-drawn with `Path` and `Canvas` rather than Swift Charts, for
the same reason the Android build used `Canvas`: the exact geometry — bar
headroom, label placement, axis rounding — is part of the design.

The palette is the Android one hex for hex: the brand navy/gold, the gain and
loss greens and reds, the teal/grey/blue dividend series, the twelve-colour
income palette, the seven-colour allocation ramp and the Dividends-tab indigos
are all identical values. So are the type scale and the spacing constants. The
`primary` role inverts with the scheme exactly as Material's does — navy on the
light scheme, pale gold on the dark — and drives the navigation bar, the
portfolio value line and the holding-detail dividend charts.

**Dividend estimates.** `QuoteClient.fetchUpcomingDividend` derives the next
payment from the fund's own record, growth-adjusted against the same slot last
year, because dividing an annual rate by four overstates a seasonal payer's
small quarters threefold. Nothing here is declared data, and the card says
"Estimated" with its basis rather than implying otherwise.

**Time zones.** Quote timestamps render on the exchange's clock and name it, so
the FTSE's 4:30pm London close doesn't read as 11:30am in Toronto.
`ExchangeZones` derives the venue from the ticker when the provider omits it.

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

    UserDefaults.standard.set("https://finance.example.com", forKey: "financeQueryBaseURL")

On Android the equivalent is `FinanceQueryClient.baseUrlOverride`, set once at
startup.

### If a source disappears

Every call falls through. Finance Query unreachable means the direct chart and
search endpoints answer instead; dividendhistory.org unreachable means the
chart's `events.dividends` block answers, with pay dates left unknown rather
than invented. A dead server marks itself unavailable for two minutes so a
thirty-symbol watchlist does not pay the timeout thirty times over.

## Caching

Three layers, each matched to how fast its data actually changes.

**Persisted, in the portfolio document** — accounts, holdings, transactions,
dividends, watchlist, plus each holding's `lastKnownPrice`, `previousClose` and
the app's own `priceSnapshots`. This is what lets totals, the day change and a
seg fund's chart paint before any network call. FX rates persist separately so
a cold start never sums the portfolio unconverted.

**Persisted, in a separate cache directory** — news headlines and scraped
dividend payout calendars. Deliberately NOT in the portfolio document: that
file is a few kilobytes, is rewritten on every edit, and holds data the user
would be upset to lose. These live where the OS is free to delete them under
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

**Dividend calendars** are now trusted for ten minutes, not twelve hours, and
the news feeds are fetched with `.reloadIgnoringLocalCacheData`. See the
changelog below for why.

### Two rules everything here follows

An empty result is never cached. Caching a failure would hold it in place for
the whole TTL, turning one bad minute into six hours of an empty chart.

A stale entry is a floor, not a liability. When a fetch fails, whatever is on
file is returned however old it is — a published pay date does not stop being
the pay date because the scrape failed this morning.

### Forcing a refresh

Pull-to-refresh on iOS, and the refresh action in the top bar on Android,
invalidate the relevant cache first. Without that a twelve-hour TTL would make
the gesture look like it had done nothing.

---

## Changelog — 17 September 2026

Second round of fixes, from screenshots of the running app.

### The portfolio chart was measuring the wrong thing

The card read **+55.40% 1M** on a month the market moved about two, because the
percentage was end-of-line over start-of-line and the line had climbed by the
size of a deposit. `portfolioValueSeries` now returns a `PortfolioSeries` —
points, plus the money paid in or taken out inside the window — and the
percentage is `(end − start − net contributions) / (start + net contributions)`.
A deposit is no longer a gain. The card says underneath how much was added, so
the honest number does not itself look like the broken one.

The flows are counted strictly *after* the opening point (its value already
prices in everything held at that moment) and up to *now* rather than up to the
last bar, because the closing point is the live total and already contains a buy
entered this evening.

### The dates under the chart

Three things were wrong at once, and they are separate fixes:

- The labels were three strings in an `HStack` with spacers, so the middle one
  sat at the middle of the *card* and named whichever bar happened to be at
  index `count / 2`. Each label is now placed at its own bar's x.
- The format was "MMM d" for everything but an intraday range, so a 5Y chart
  printed "Sep 17" five times with no year. The format now comes from the range,
  as `StockAreaChart.kt` does it: `h:mm a`, `EEE`, `MMM d`, `MMM`, `MMM yy`.
- The timeline is the union of every security's bars, and the two chart
  providers stamp a daily bar at different times of day — midnight UTC against
  the opening bell — so two holdings on different exchanges could put two points
  on one Tuesday. Daily-or-coarser series are now collapsed to one point per
  calendar day, and a weekend point that merely repeats Friday's value (a
  provider forward-fill) is dropped.

Up to five labels are placed and collision-checked; the opening and closing ones
win any argument.

### The y axis on a price chart

Fixed at zero decimals, every gridline on a $45 share printed "45" — four
identical labels, which reads as a broken axis. Cents below $1,000, whole
dollars above, matching the Android chart.

### Previous close

`AreaChart` takes an optional `baseline`, drawn as a dashed rule and included in
the y extent so a gap up or down cannot push it off the plot. Wired into the
quote and holding charts on the 1D range only. The up/down colour is measured
against it when it is present, so a session that opened below yesterday's close
and rose all afternoon is correctly a down day.

### Range chips moved above the chart

Portfolio, quote and holding screens, matching Android. The control that decides
what the chart shows now sits above the thing it changes.

### The top figure follows the range

Tapping 1W moved the chart and left the headline talking about this afternoon.
The Total value card now shows the selected range's return, labelled "over 1W",
"over 1M" — or "since Sep 8" when the portfolio is younger than the range, which
is also what reconciles the chip with the dates on the axis.

### Top contributors

Grouped per security rather than per stored holding, so one fund held in three
accounts is one row with its combined contribution instead of three partial ones
competing for a place in the top six. The account count rides along on the row.

### Tax cards

Three accounts meant three full-height cards repeating the same paragraph and
the same disclaimer. They are now a horizontal carousel with the shared
boilerplate said once underneath.

### Account chips

Laid out on an equal three-column grid (`AccountChipGrid`) so every line fills
the card. Wrapping at natural widths left the last line ragged against the left
edge.

### Sign-in

- Sign in with Apple removed from **both** builds: the button, the credential
  exchange, `AppleSignIn.swift`, the protocol members, the Android web flow and
  its error translation, and the `com.apple.developer.applesignin` entitlement
  (an entitlement without the matching App ID capability fails signing outright).
  **Before submitting to the App Store:** review guideline 4.8 requires an app
  offering any third-party sign-in to offer Apple's as well, so this build is
  exposed for as long as the Google button is visible.
- The iOS sign-in screen is rebuilt element for element against
  `LoginScreen.kt`: branded title, Google button above the form, "or" rule, two
  outlined fields with leading icons, gold primary action, register and reset
  links.

### "Already signed in, still says Sign In / Register"

`Services.auth` is a protocol object in a static, so reading `currentUser` in a
`body` gave the answer at the moment that body ran and nothing ever invalidated
it. New `AuthSession` publishes it; the Menu row, the profile screen and the
backup screen observe it, and anything that ends a sign-in or sign-out calls
`refresh()`. `BackupView`'s private counter, which solved this for one screen,
is gone.

### My Account

Cloud backup and the bug-report console both removed — they are Menu ▸ Backup &
Restore and Menu ▸ Bug Reports, and the backup copy was the one a signed-out
user could never reach. The screen is now identity and sign-out.

### News and dividends were frozen

- The news tab refreshed only when the list was **empty**, so once the disk
  cache had anything in it the only way to see a new headline was to pull down.
  The cache now paints the first frame and the network always follows.
- `NewsClient` used `.useProtocolCachePolicy`, and several publishers send a long
  `Cache-Control`, so even a pull re-read a copy from disk. Now
  `.reloadIgnoringLocalCacheData` with no `URLCache`.
- The Dividends tab fetched upcoming payments once per launch. It now refreshes
  on every appearance, and the scraped-calendar TTL is ten minutes rather than
  twelve hours — a distribution declared this morning no longer waits until
  tomorrow. The on-disk copy keeps its other job: something to draw when the
  scrape fails or the phone is offline.

### News images

Three causes, all fixed:

- `AsyncImage` goes through `URLSession.shared` with URLSession's own user agent
  and no referer, which a newspaper's image CDN treats as a hotlink and answers
  403. New `RemoteImage` / `ImageLoader` requests as a browser, sends the
  article's host as the referer, caches decoded images in memory and on disk,
  coalesces duplicate in-flight requests, and rejects anything under 32px as a
  tracking pixel.
- `media:content` carries whatever the item has, including an `.mp4` on a video
  story, and that was being taken as the thumbnail. The type, medium and file
  extension are checked now.
- Thumbnails from the provider's news search were used as given: an `http://`
  URL is refused outright by App Transport Security, which on screen is
  indistinguishable from a story with no picture. They are forced to https and
  taken at the largest resolution offered rather than the first.

### Verification

`check_swift.py`, `check_swift_extras.py` and `check_attrs.py` all clean. The
new x-axis label placement was simulated against seven range shapes and plot
widths — no overlaps, every label inside the plot. Still not compiled: there is
no Swift toolchain in this sandbox. Expect a first-build fix or two.

### First-build fix (21:20)

`topContributors()` hit "the compiler is unable to type-check this expression in
reasonable time". The cause was a `[String: (gain: Double, accounts: Set<String>)]`
dictionary feeding a `.map`/`.sorted`/`.prefix`/`.map` chain: Swift has to weigh
every overload of each link against an inferred tuple element type, and the
search explodes. Rewritten with a named `ContributorTotal` struct and a loop.

Three more expressions of the same shape, all added in this round, were rewritten
before they could fail the same way: the x-tick geometry in `AreaChart` (an
Int→Double→CGFloat conversion chain feeding a range built from arithmetic), the
holding-currency lookup in `portfolioValueSeries`
(`Dictionary(_:uniquingKeysWith:)` over a tuple-producing `map`), and the
thumbnail-resolution comparison in `NewsClient.search` (a double-coalesced
`as?` cast inside a `max(by:)` closure). The rest of the file's long chains are
pre-existing and already compile.

---

## Changelog — 18 September 2026

### The portfolio gain was still wrong, for a different reason

The previous round netted contributions out of the percentage using the
transaction log. It still read **+71.60% over 1M**, because most of this
portfolio was never entered as transactions: a holding typed straight in — name,
ticker, unit count — has no log entries at all. Its unit schedule still steps
from zero to full size on the day it was added, so the line jumps by the whole
position with nothing to explain it, and the jump was counted as growth.

Contributions now come off the **unit schedule**, which is what the line is
actually drawn from: every step in it moves the line, whether a transaction
accounts for it or not. Units gained × the price at that moment is the money
that arrived. Verified against five shapes — a typed-in holding added
mid-window, a position held throughout, one doubled halfway, one partly sold,
and a pure deposit into a flat market. The last reads exactly 0.00% where it
used to read +100%.

The "CA$58,465.01 added in this period" line is gone from the card. It existed
because the figure above it could not be trusted alone.

Ported to Android as well, along with the per-calendar-day timeline collapse and
the weekend forward-fill filter from the iOS round.

### Price headers follow the range

Both apps. A stock, index or holding screen showed the day change whatever the
chips said. New `RangeMove` measures across the bars actually drawn, with 1D
still using the quote's own previous close — the exchange's number — and a
label that says which period it is ("today", "over 1M").

### Previous-close line on every range, both apps

Was 1D only. Now: yesterday's close on 1D, and the period's own opening close on
every longer range, where "previous close" means nothing but "where this range
started" means a great deal. Android's `StockAreaChart` gained the same
`baseline` parameter, drawn dashed under the price line with the y extent
widened to keep it on screen.

The portfolio chart deliberately has none: that line also steps up whenever money
is paid in, so a rule at the opening value would invite exactly the misreading
the percentage was just fixed to stop making.

### Hot Stocks: missing charts and no after-hours line

Three separate faults:

- `refreshWatchlistQuotes` marked a ticker done as soon as the batch returned
  *anything* for it — and when the server has no batched trace endpoint, the
  batch falls back to plain quotes, which carry a price and no closes. Every one
  of those rows was skipped by the fallback and sat with an empty Chart column
  for the life of the app. Price and trace are tracked separately now.
- The fallback was a strictly sequential loop over two dozen tickers with the
  whole dictionary assigned only after the last one returned, so the column
  filled for the first handful and stayed blank below. Now six requests in
  flight at a time, published as each lands.
- Hot Stocks rows passed `extended: nil` and were not in
  `refreshExtendedQuotes`'s symbol list, so the screen most likely to be read
  after the close was the one that never mentioned the close.

### Search results open

A result row was the add-to-watchlist button and nothing else, so a search was a
one-way door: the only thing you could do with a match was commit it to your
list. The row now opens the ticker; the "+" keeps its own tap target.

### The header title showing as "M…"

The screen name was a leading toolbar item, to sit on the left as Android's
does. That worked until the system began giving every toolbar item a capsule
background and button metrics — "My Portfolio" was then sized as a bar button,
hence a circle containing "M…". It is a real `.inline` navigation title now,
centred. The two apps disagree about where a screen's name sits, which is a
smaller price than a title that cannot be read.

### Android portfolio card

Range chips moved above the chart, under the "Portfolio value" heading, matching
iOS — underneath, the control that decides what the chart shows could be off the
bottom of the screen while the chart was in full view. The gain under the total
follows the selected range, on the same flow-adjusted maths as iOS.

### Android: My Holdings rows open the position

That tab opened the ticker's quote view on the reasoning that the tab is a quote
list. But every row there is something the reader owns, and the quote screen is
the one screen that cannot say what they own of it. Rows now go to the holding's
own screen, as the iOS ones do.

### Verification

Swift checkers clean; the expression-chain scan finds nothing new (the remaining
hits are pre-existing and already compile). Kotlin delimiter balance checked
across all nine changed files. Neither app compiled — no Swift toolchain and no
Android SDK in this sandbox.

## Fixes — 18 September 2026, evening

### Pull-to-refresh emptied the portfolio chart

`refreshPortfolio()` invalidates the chart cache and then fires a quote request
per ticker; `loadHistory()` runs straight after and needs a history request per
security, every one now forced to the network. That burst is what the provider's
rate limiter exists to stop, and a throttled response comes back as an empty
series — which was assigned straight over a good line, so the card fell back to
"Not enough price history yet" and stayed there until the range was changed. The
gesture meaning "show me current data" was the one that reliably emptied the
chart.

`loadHistory()` now keeps what is on screen when the refetch returns nothing,
the same rule the news feed already followed. An empty answer here means the
request failed, not that the portfolio has no history.

### Scrubbing on the iOS charts

Touch or drag anywhere on a chart for a crosshair, a dot on the line, and the
value and moment printed above it — what Android's `StockAreaChart` has had, and
what the Yahoo app does. The readout is drawn inside the plot, because the chart
is the only thing that knows which point is under the finger. The x-axis labels
hide while scrubbing, since they share that band at the ends of the plot.

It does claim vertical drags over the plot, so the page can't be scrolled by
starting on the chart. Every finance chart makes that trade, and the card is
200pt of a scrolling screen.

### Contact Support did nothing, then said it had worked

Two bugs stacked.

`Services.bugReports` was never given an implementation on iOS — Android has
written to Firestore since v1.3, iOS shipped the protocol and left the stub. So
Contact Support could only ever take the mail fallback, and the admin Bug
Reports console could only ever be empty. `FirestoreBugReportService` now writes
to the same `/bug_reports` collection with the same field names as
`FirebaseManager.submitBugReport`, so both apps feed one console.

The fallback itself then called `openURL` and set `sent = true` on the next line
regardless of the result. With no mail account — and on every simulator, where
Mail isn't installed — nothing happened and the screen showed a confirmation for
a report that had gone nowhere. It now waits on the open, and if it fails, puts
the report on the clipboard and says where to send it.

### "Free" looked tappable on the paywall

It wasn't, and it was never meant to be: the tier card describes what each tier
is, and the only choice on the screen is monthly against yearly. But it was
drawn with an empty circle and a filled checkmark, which is what a radio group
looks like. Both platforms now use a tick against each tier and a "Current plan"
badge, so nothing invites a tap it can't answer.

### Privacy policy

"Finance Query" is now "Third-party market data API" on both platforms.

## Pre-release pass — 18 September 2026, evening

Everything from the readiness review except two of the three store items. Apple
sign-in and the paywall's Terms-of-Use link are still outstanding by decision.

### First-build fix

`FirestoreBugReportService` referenced `FirebaseApp` without importing
FirebaseCore, where it is declared. `FirebaseAuthService` has the right
three-way `canImport` guard and this file did not copy it.

### Account deletion — App Store 5.1.1(v), and Play's equivalent

Both platforms. `AuthService.deleteAccount()` / `FirebaseManager.deleteAccount()`
remove the auth user and the cloud copy, reachable from My Account behind a
confirmation that says exactly what goes and what stays.

The cloud data is deleted FIRST, deliberately: deleting the auth user revokes
the credential the Firestore rules check, so anything deleted afterwards would
be refused and the portfolio would outlive the account that owned it. If the
account delete then fails the user is still signed in and can retry; the
reverse order strands data nobody can reach.

Firebase refuses deletion on a session more than a few minutes old. That is
caught and translated into "sign out, sign back in, then delete" rather than
passing through "this operation is sensitive and requires recent
authentication", which tells nobody what to do.

The local portfolio is the user's own data and is left alone. The dialog says so.

### One dividend growth model

`DividendForecast.measuredGrowth` reads each fund's own year-over-year growth —
the last full cycle against the one before it, the same calculation the
per-payment estimator already ran. Both platforms pass nil now; the 3% constant
survives only as `fallbackGrowthRate`, used when there is not a full cycle on
each side to compare.

Clamped to −10%…+15% a year, tighter than the per-payment band, because this one
compounds over as much as eleven years. Verified on seven shapes: XEQT-like
records come out at −0.92% against the +3% previously assumed, a steady riser
reports its real +8%, a fund with one special distribution clamps at +15%
instead of compounding to 2×10¹⁴ over the horizon, and a fund with one cycle on
record falls back.

### "Estimated" and "Projected"

Two near-synonyms on two unrelated quantities. The Upcoming card now says
**"Next payment"**, the forward chart **"Projected income · from each fund's own
payment record"**.

### iOS dividend dates, by rule rather than by luck

`payDatedHistory` ported from Android. iOS was landing on pay dates only because
its tie-break happened to run the other way from Android's — the same
coincidence that had one build putting XEQT's year-end distribution in December
and the other in January. Both now keep the events block's reach and re-date it
from the payout page, falling back to the fund's own median ex→pay gap.

### The paywall stopped promising ads that don't exist

`AdManager.isAvailable` is a documented `false` on Android and
`Services.ads.isAvailable` already was on iOS. All the Premium copy and both
Menu subtitles follow it, so the free tier is described as what it is.

This also closed a real trap on Android: with no ad network, a free user tapping
"Generate report" or "Cloud backup & restore" got a dialog whose only exit was
an ad that could never load. Both gates now pass straight through. A gate whose
key does not exist is worse than no gate.

### Cloud data in one place

iOS moved from `backups/{uid}` to `users/{uid}/portfolio/document`, where
Android already keeps its data. One subtree, one security rule, and deleting an
account now takes both apps' copies.

It does **not** make a backup portable between the apps — same place, different
shapes — and nothing pretends otherwise. See SUBSCRIPTION-SETUP.md.

### armv7

`UIRequiredDeviceCapabilities` declared 32-bit ARM, dropped by iOS 11, on an app
targeting iOS 16. Now arm64.

### Still outstanding, by decision

- Apple sign-in (guideline 4.8) — removed at the owner's request.
- Terms of Use link on the paywall (guideline 3.1.2).
- Firestore security rules — console work, in SUBSCRIPTION-SETUP.md.
- Market data licensing.
- Cross-platform backup format.

### Verification

Swift checkers clean, expression-chain scan finds nothing new, Kotlin delimiter
balance clean across all changed files, growth model simulated on seven record
shapes. Still not compiled on either platform.
