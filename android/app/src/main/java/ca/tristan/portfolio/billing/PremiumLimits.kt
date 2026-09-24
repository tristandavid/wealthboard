package ca.tristan.portfolio.billing

/**
 * Where the free tier stops.
 *
 * One place for every number the paywall enforces, because a limit written
 * into the gate and quoted again in the marketing copy drifts apart the first
 * time one of them is changed — and a paywall that promises "up to 2" while
 * refusing at 1 is a bug report, not a pricing decision.
 *
 * Mirrors `PremiumLimits` on iOS; the numbers have to match, or the same
 * person hits a different wall depending on which phone is in their hand.
 */
object PremiumLimits {

    /**
     * Accounts a free install may create.
     *
     * Two rather than one: a single account is what the app auto-creates and
     * would make the limit invisible until the moment it bites, and the most
     * common real setup — one taxable account and one registered one — should
     * work without paying. Someone tracking a TFSA, an RRSP and a taxable
     * account is the person this is for, and they are also the person for whom
     * per-account tax treatment is worth money.
     */
    const val FREE_ACCOUNT_LIMIT = 2

    /** Whether another account may be created right now. */
    fun canCreateAccount(currentCount: Int, isPremium: Boolean): Boolean =
        isPremium || currentCount < FREE_ACCOUNT_LIMIT
}
