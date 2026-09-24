import SwiftUI

/// Plain-language explanation of the app's tax features.
///
/// Written for someone who does not know how investment tax works, because that
/// is most people — the alternative is a screen full of warnings whose reasoning
/// is invisible, which teaches nothing and is easy to mistrust.
///
/// Two rules it sticks to: explain the mechanism rather than name the rule, and
/// never state a number as though it were the reader's. It also adapts to the
/// user's stated residency, so a Canadian never reads about Roth IRAs.
struct TaxHelpView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    private var isUS: Bool { viewModel.residency == .unitedStates }
    private var sheltered: String { isUS ? "Traditional IRA or 401(k)" : "RRSP" }
    private var taxFree: String { isUS ? "Roth IRA" : "TFSA" }
    private var foreign: String { isUS ? "Canadian" : "US" }

    var body: some View {
        ScrollView {
            // Grouped into sections rather than one flat stack: SwiftUI's
            // ViewBuilder takes at most ten children, and a page of prose has
            // far more than ten paragraphs in it.
            VStack(alignment: .leading, spacing: 0) {
                intro
                accountKinds
                withholding
                filingException
                treatyCatch
                residencySpecific
                limits
                Callout(title: "Before you act on any of it", message: TaxRules.disclaimer)
                    .padding(.top, 24)
                gap(40)
            }
            .padding(.horizontal, 20)
        }
        .wbScreenBackground(scheme)
        .navigationTitle("How tax works here")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Sections

    private var intro: some View {
        VStack(alignment: .leading, spacing: 0) {
            Callout(
                title: "This app does not do your taxes.",
                message: "It doesn't calculate what you owe, and nothing here goes to any tax authority. What it does is point out one thing that's easy to miss: money being deducted from your dividends before they reach you."
            )
            .padding(.top, 12)

            gap(20)
            heading("The only thing you have to tell it")
            paragraph("Two settings turn all of this on. In Settings, say which country you file taxes in. Then, on each account, say whether it's taxed now, later, or never. That's it \u{2014} the app works the rest out from the tickers you already hold.")
            paragraph("If you skip either one, no tax information is shown anywhere. The app would rather tell you nothing than guess and be wrong.")
        }
    }

    private var accountKinds: some View {
        VStack(alignment: .leading, spacing: 0) {
            gap(20)
            heading("The three kinds of account")
            paragraph("Every investment account falls into one of three buckets, and the differences between them are the whole game.")
            bullet("Taxed now", "An ordinary brokerage or cash account. Dividends are income the year you receive them, and when you sell for a profit, that profit is taxable too.")
            bullet("Taxed later", "\(sheltered). Nothing is taxed while the money stays inside \u{2014} dividends and growth compound untouched. You pay tax as regular income when you take money out.")
            bullet("Never taxed", "\(taxFree). Growth and dividends aren't taxed, and withdrawals aren't either. Nothing to report.")
        }
    }

    private var withholding: some View {
        VStack(alignment: .leading, spacing: 0) {
            gap(20)
            heading("The part almost everyone misses")
            paragraph("When a \(foreign) company pays you a dividend, that country takes a cut before the money ever reaches your account. It's called withholding tax, it's usually 15%, and it never appears on your statement as a line item \u{2014} the dividend just quietly arrives smaller than the company announced.")
            paragraph("Whether that 15% costs you anything depends entirely on which account the shares sit in, and the answer is not the one most people expect:")
            bullet("In a taxable account \u{2014} usually fine", "You can normally claim the amount withheld back when you file, as a foreign tax credit. The real cost ends up near zero.")
            bullet("In a \(sheltered) \u{2014} nothing is withheld at all", "The tax treaty between the two countries treats retirement accounts as exempt. You get the full dividend.")

            if isUS {
                bullet(
                    "In a \(taxFree) \u{2014} the same as a \(sheltered)",
                    "Canada treats US retirement accounts alike under the treaty, so a Roth IRA is in the same position as a Traditional one. Neither exemption is automatic though \u{2014} see below."
                )
            } else {
                bullet(
                    "In a \(taxFree) \u{2014} the money is simply gone",
                    "This is the one that catches people. The 15% is still deducted, but because a \(taxFree) generates no tax bill, there's nothing to claim the credit against. You can't get it back. Ever."
                )
            }

            paragraph("So the same \(foreign) dividend stock can cost you 15% a year in one account and nothing in another. On a holding paying $1,000 a year, that's $150 annually \u{2014} and it compounds, because the money that was taken never gets reinvested.")
            paragraph("This is why the app shows a warning on those holdings, with the actual dollar amount at your current payout. It isn't telling you to sell anything. It's telling you a thing that was invisible.")
        }
    }

    @ViewBuilder
    private var filingException: some View {
        if isUS {
            VStack(alignment: .leading, spacing: 0) {
                gap(20)
                heading("The exemption exists, but you have to ask for it")
                paragraph("Article XXI of the Canada\u{2013}US treaty exempts US retirement accounts from Canadian withholding on dividends. IRAs are named directly, and Roth IRAs and 401(k)s fall under the same provision for arrangements that exist only to provide retirement benefits.")
                paragraph("It is not applied automatically. The CRA issues a Letter of Exemption only on application, with the account holder's details and a notarised affidavit of residency \u{2014} normally handled by your broker or custodian. Until that is on file, the tax is withheld as normal, which is why the app shows it as withheld. Moving the holding between retirement accounts will not change anything; filing will.")
            }
        }
    }

    private var treatyCatch: some View {
        VStack(alignment: .leading, spacing: 0) {
            gap(20)
            heading("One catch worth knowing")
            paragraph("That 15% treaty rate only applies if your broker has your residency form on file \u{2014} a W-8BEN for a Canadian holding US shares, or the equivalent the other way round. Without it the rate is roughly double. Most brokers collect this when you open the account, but it's worth checking if you've never seen one.")
        }
    }

    @ViewBuilder
    private var residencySpecific: some View {
        if isUS {
            VStack(alignment: .leading, spacing: 0) {
                gap(20)
                heading("Qualified dividends")
                paragraph("Most dividends from US companies are \"qualified\", which means they're taxed at a lower rate than interest \u{2014} as long as you've held the shares long enough, generally more than 60 days around the dividend date. Buying just before a dividend and selling straight after can lose you that lower rate.")
            }
        } else {
            VStack(alignment: .leading, spacing: 0) {
                gap(20)
                heading("Why Canadian dividends are treated differently")
                paragraph("In a taxable account, dividends from Canadian companies get something called the dividend tax credit, which usually makes them the most lightly taxed income you can earn \u{2014} often taxed less than the same amount of interest or foreign dividends.")
                paragraph("That's why the app marks them as favourably taxed. It doesn't mean Canadian stocks are better investments; it means the same dollar of income keeps more of itself.")
            }
        }
    }

    private var limits: some View {
        VStack(alignment: .leading, spacing: 0) {
            gap(20)
            heading("What the app deliberately doesn't do")
            paragraph("It doesn't know your income, so it can't know your tax rate. It has no provincial or state rates, no brackets, and no knowledge of anything you hold outside it. It won't tell you what you owe, and it won't produce anything you can file.")
            paragraph("Those were left out on purpose. A tax estimate that looks precise and is quietly wrong is worse than no estimate at all \u{2014} and rates change every year, in every jurisdiction, which is a promise this app can't keep on its own.")
        }
    }

    // MARK: - Text primitives

    private func gap(_ height: CGFloat) -> some View {
        Color.clear.frame(height: height)
    }

    private func heading(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 17, weight: .bold))
            .foregroundStyle(Palette.onSurface(scheme))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, 6)
    }

    private func paragraph(_ text: String) -> some View {
        Text(text)
            .font(.wbBodyMedium)
            .foregroundStyle(Palette.onSurface(scheme).opacity(0.85))
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, 10)
    }

    private func bullet(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Palette.onSurface(scheme))
            Text(body)
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 2)
        .padding(.bottom, 10)
    }
}

/// Tinted panel used for the two statements that frame the page — what the app
/// will not do, and the disclaimer. Distinct from a `WbCard` on purpose: these
/// are caveats, not content.
struct Callout: View {
    @Environment(\.colorScheme) private var scheme

    let title: String
    let message: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(Palette.onSurface(scheme))
            Text(message)
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Palette.accent(scheme).opacity(0.14))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
