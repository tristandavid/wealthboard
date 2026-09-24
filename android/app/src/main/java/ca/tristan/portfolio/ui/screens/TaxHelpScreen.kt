package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.tax.Residency
import ca.tristan.portfolio.tax.TaxRules
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.WealthBoardTopBar

/**
 * Plain-language explanation of the app's tax features.
 *
 * Written for someone who does not know how investment tax works, because that
 * is most people — the alternative is a screen full of warnings whose reasoning
 * is invisible, which teaches nothing and is easy to mistrust.
 *
 * Two rules it sticks to: explain the mechanism rather than name the rule, and
 * never state a number as though it were the reader's. It also adapts to the
 * user's stated residency, so a Canadian never reads about Roth IRAs.
 */
@Composable
fun TaxHelpScreen(
    viewModel: PortfolioViewModel,
    onBack: () -> Unit
) {
    val residency by viewModel.residency.collectAsStateWithLifecycle()
    val isUs = residency == Residency.UNITED_STATES

    val sheltered = if (isUs) "Traditional IRA or 401(k)" else "RRSP"
    val taxFree = if (isUs) "Roth IRA" else "TFSA"
    val foreign = if (isUs) "Canadian" else "US"

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = "How tax works here",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            Callout(
                "This app does not do your taxes.",
                "It doesn't calculate what you owe, and nothing here goes to any tax " +
                    "authority. What it does is point out one thing that's easy to miss: " +
                    "money being deducted from your dividends before they reach you."
            )

            Spacer(Modifier.height(20.dp))
            H("The only thing you have to tell it")
            P(
                "Two settings turn all of this on. In Settings, say which country you " +
                    "file taxes in. Then, on each account, say whether it's taxed now, " +
                    "later, or never. That's it — the app works the rest out from the " +
                    "tickers you already hold."
            )
            P(
                "If you skip either one, no tax information is shown anywhere. The app " +
                    "would rather tell you nothing than guess and be wrong."
            )

            Spacer(Modifier.height(20.dp))
            H("The three kinds of account")
            P(
                "Every investment account falls into one of three buckets, and the " +
                    "differences between them are the whole game."
            )
            Bullet(
                "Taxed now",
                "An ordinary brokerage or cash account. Dividends are income the year " +
                    "you receive them, and when you sell for a profit, that profit is " +
                    "taxable too."
            )
            Bullet(
                "Taxed later",
                "$sheltered. Nothing is taxed while the money stays inside — dividends " +
                    "and growth compound untouched. You pay tax as regular income when " +
                    "you take money out."
            )
            Bullet(
                "Never taxed",
                "$taxFree. Growth and dividends aren't taxed, and withdrawals aren't " +
                    "either. Nothing to report."
            )

            Spacer(Modifier.height(20.dp))
            H("The part almost everyone misses")
            P(
                "When a $foreign company pays you a dividend, that country takes a cut " +
                    "before the money ever reaches your account. It's called withholding " +
                    "tax, it's usually 15%, and it never appears on your statement as a " +
                    "line item — the dividend just quietly arrives smaller than the " +
                    "company announced."
            )
            P(
                "Whether that 15% costs you anything depends entirely on which account " +
                    "the shares sit in, and the answer is not the one most people expect:"
            )
            Bullet(
                "In a taxable account — usually fine",
                "You can normally claim the amount withheld back when you file, as a " +
                    "foreign tax credit. The real cost ends up near zero."
            )
            Bullet(
                "In a $sheltered — nothing is withheld at all",
                "The tax treaty between the two countries treats retirement accounts as " +
                    "exempt. You get the full dividend."
            )
            if (isUs) {
                Bullet(
                    "In a $taxFree — the same as a $sheltered",
                    "Canada treats US retirement accounts alike under the treaty, so a " +
                        "Roth IRA is in the same position as a Traditional one. Neither " +
                        "exemption is automatic though — see below."
                )
            } else {
                Bullet(
                    "In a $taxFree — the money is simply gone",
                    "This is the one that catches people. The 15% is still deducted, but " +
                        "because a $taxFree generates no tax bill, there's nothing to claim " +
                        "the credit against. You can't get it back. Ever."
                )
            }
            P(
                "So the same $foreign dividend stock can cost you 15% a year in one " +
                    "account and nothing in another. On a holding paying \$1,000 a year, " +
                    "that's \$150 annually — and it compounds, because the money that " +
                    "was taken never gets reinvested."
            )
            P(
                "This is why the app shows a warning on those holdings, with the actual " +
                    "dollar amount at your current payout. It isn't telling you to sell " +
                    "anything. It's telling you a thing that was invisible."
            )

            if (isUs) {
                Spacer(Modifier.height(20.dp))
                H("The exemption exists, but you have to ask for it")
                P(
                    "Article XXI of the Canada–US treaty exempts US retirement accounts " +
                        "from Canadian withholding on dividends. IRAs are named directly, " +
                        "and Roth IRAs and 401(k)s fall under the same provision for " +
                        "arrangements that exist only to provide retirement benefits."
                )
                P(
                    "It is not applied automatically. The CRA issues a Letter of Exemption " +
                        "only on application, with the account holder's details and a " +
                        "notarised affidavit of residency — normally handled by your broker " +
                        "or custodian. Until that is on file, the tax is withheld as normal, " +
                        "which is why the app shows it as withheld. Moving the holding " +
                        "between retirement accounts will not change anything; filing will."
                )
            }

            Spacer(Modifier.height(20.dp))
            H("One catch worth knowing")
            P(
                "That 15% treaty rate only applies if your broker has your residency " +
                    "form on file — a W-8BEN for a Canadian holding US shares, or the " +
                    "equivalent the other way round. Without it the rate is roughly " +
                    "double. Most brokers collect this when you open the account, but " +
                    "it's worth checking if you've never seen one."
            )

            if (!isUs) {
                Spacer(Modifier.height(20.dp))
                H("Why Canadian dividends are treated differently")
                P(
                    "In a taxable account, dividends from Canadian companies get " +
                        "something called the dividend tax credit, which usually makes " +
                        "them the most lightly taxed income you can earn — often taxed " +
                        "less than the same amount of interest or foreign dividends."
                )
                P(
                    "That's why the app marks them as favourably taxed. It doesn't mean " +
                        "Canadian stocks are better investments; it means the same dollar " +
                        "of income keeps more of itself."
                )
            } else {
                Spacer(Modifier.height(20.dp))
                H("Qualified dividends")
                P(
                    "Most dividends from US companies are \"qualified\", which means " +
                        "they're taxed at a lower rate than interest — as long as you've " +
                        "held the shares long enough, generally more than 60 days around " +
                        "the dividend date. Buying just before a dividend and selling " +
                        "straight after can lose you that lower rate."
                )
            }

            Spacer(Modifier.height(20.dp))
            H("What the app deliberately doesn't do")
            P(
                "It doesn't know your income, so it can't know your tax rate. It has no " +
                    "provincial or state rates, no brackets, and no knowledge of anything " +
                    "you hold outside it. It won't tell you what you owe, and it won't " +
                    "produce anything you can file."
            )
            P(
                "Those were left out on purpose. A tax estimate that looks precise and " +
                    "is quietly wrong is worse than no estimate at all — and rates change " +
                    "every year, in every jurisdiction, which is a promise this app can't " +
                    "keep on its own."
            )

            Spacer(Modifier.height(24.dp))
            Callout("Before you act on any of it", TaxRules.DISCLAIMER)

            Spacer(Modifier.height(WbDimens.ScrollBottomGap))
        }
    }
}

@Composable
private fun H(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun P(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Bullet(title: String, body: String) {
    Spacer(Modifier.height(2.dp))
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    Spacer(Modifier.height(2.dp))
    Text(
        body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Callout(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
