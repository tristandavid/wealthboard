import Foundation

// The same fund bought in a TFSA, an RRSP and an FHSA is three rows in the
// store — necessarily, since cost basis and tax treatment are per account. But
// the user owns ONE position in it.
//
// These types are that position: the combined figures the app shows by default,
// and the per-account split it can break them back down into on request.

/// One account's slice of a position.
struct AccountPosition: Identifiable {
    /// The underlying holding row, which is what an edit or a transaction acts on.
    let holding: Holding
    let account: Account?

    let units: Double
    let price: Double
    let previousClose: Double?
    let costBasis: Double?

    var id: UUID { holding.id }

    var accountName: String { account?.displayName ?? "Unassigned" }
    var treatment: TaxTreatment? { account?.taxTreatment }
    var currency: String { holding.normalizedCurrency }

    var marketValue: Double { price * units }

    var averagePrice: Double? {
        guard let costBasis, units > 0 else { return nil }
        return costBasis / units
    }

    /// Today's move on this slice, from the quote's previous close.
    var todaysReturn: Double? {
        guard let previousClose else { return nil }
        return (price - previousClose) * units
    }

    var todaysReturnPercent: Double? {
        guard let previousClose, previousClose > 0 else { return nil }
        return (price - previousClose) / previousClose * 100
    }

    /// Gain against what was paid. Nil when no cost basis has been recorded —
    /// an unrecorded cost is not a zero cost, and showing it as a 100% gain
    /// would be a lie.
    var totalReturn: Double? {
        guard let costBasis else { return nil }
        return marketValue - costBasis
    }

    var totalReturnPercent: Double? {
        guard let costBasis, costBasis > 0 else { return nil }
        return (marketValue - costBasis) / costBasis * 100
    }
}

/// A whole position in one security, across every account it sits in.
struct SecurityPosition: Identifiable {
    /// `Holding.securityKey` — the ticker where there is one, the name where
    /// there isn't.
    let key: String
    /// Every stored row that makes up this position, largest slice first.
    let slices: [AccountPosition]

    var id: String { key }

    /// The row the position is named and priced from. Largest slice, so a
    /// token holding in a fourth account can't decide the display name.
    var principal: Holding { slices[0].holding }

    var name: String { principal.name }
    var ticker: String? { principal.ticker }
    var type: HoldingType { principal.type }
    var currency: String { principal.normalizedCurrency }
    var price: Double { slices[0].price }

    var isSplit: Bool { slices.count > 1 }
    var accountCount: Int { slices.count }

    var units: Double { slices.reduce(0) { $0 + $1.units } }
    var marketValue: Double { slices.reduce(0) { $0 + $1.marketValue } }

    /// Summed only when every slice has one. A partial sum would read as the
    /// whole position's cost and understate it.
    var costBasis: Double? {
        guard slices.allSatisfy({ $0.costBasis != nil }) else { return nil }
        return slices.reduce(0) { $0 + ($1.costBasis ?? 0) }
    }

    var averagePrice: Double? {
        guard let costBasis, units > 0 else { return nil }
        return costBasis / units
    }

    var todaysReturn: Double? {
        let known = slices.compactMap(\.todaysReturn)
        guard known.count == slices.count else { return nil }
        return known.reduce(0, +)
    }

    var totalReturn: Double? {
        guard let costBasis else { return nil }
        return marketValue - costBasis
    }

    var totalReturnPercent: Double? {
        guard let costBasis, costBasis > 0 else { return nil }
        return (marketValue - costBasis) / costBasis * 100
    }

    /// The distinct tax treatments this position spans.
    ///
    /// More than one means no single tax answer applies to it, which is exactly
    /// the case the per-account breakdown exists for.
    var treatments: [TaxTreatment?] {
        var seen: [TaxTreatment?] = []
        for slice in slices where !seen.contains(where: { $0 == slice.treatment }) {
            seen.append(slice.treatment)
        }
        return seen
    }

    /// Share of the position sitting in one account, 0…1.
    func share(of slice: AccountPosition) -> Double {
        marketValue > 0 ? slice.marketValue / marketValue : 0
    }
}
