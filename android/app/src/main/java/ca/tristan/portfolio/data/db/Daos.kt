package ca.tristan.portfolio.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY id ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    /**
     * INSERT ONLY. Never call this with an id that already exists.
     *
     * OnConflictStrategy.REPLACE is implemented by SQLite as DELETE followed by
     * INSERT — not as an UPDATE. `holdings.accountId` is a foreign key with
     * ON DELETE CASCADE, so "replacing" an existing account deletes every
     * holding inside it, and their transactions, dividends and price snapshots
     * with them. Silently, and with no error.
     *
     * That is exactly what happened when setting an account's tax treatment
     * went through here as `upsert(existing.copy(...))`: the user's entire
     * portfolio was deleted by a settings change. Use [update] or a targeted
     * @Query for any change to a row that already exists.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    /**
     * Targeted write for the one mutable field on an account.
     *
     * A plain UPDATE statement, so no row is ever deleted and nothing cascades.
     */
    @Query("UPDATE accounts SET taxTreatment = :treatment WHERE id = :id")
    suspend fun setTaxTreatment(id: Long, treatment: String?)

    // Cascades to the account's holdings via ForeignKey.CASCADE.
    // Added so empty leftover accounts (e.g. a stale "Good morning" husk
    // account, or the default empty "Wealthsimple" manual account) can be
    // removed the same way an individual holding can.
    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface HoldingDao {
    @Query("SELECT * FROM holdings ORDER BY id ASC")
    fun observeAll(): Flow<List<HoldingEntity>>

    @Query("SELECT * FROM holdings WHERE accountId = :accountId ORDER BY id ASC")
    fun observeForAccount(accountId: Long): Flow<List<HoldingEntity>>

    @Query("SELECT * FROM holdings WHERE id = :id")
    suspend fun getById(id: Long): HoldingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(holding: HoldingEntity): Long

    @Update
    suspend fun update(holding: HoldingEntity)

    @Query("DELETE FROM holdings WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM holdings WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: Long)
}

@Dao
interface PriceSnapshotDao {
    @Insert
    suspend fun insert(snapshot: PriceSnapshotEntity)

    @Query("SELECT * FROM price_snapshots WHERE holdingId = :holdingId AND atMillis >= :sinceMillis ORDER BY atMillis ASC")
    suspend fun history(holdingId: Long, sinceMillis: Long): List<PriceSnapshotEntity>

    @Query("SELECT * FROM price_snapshots WHERE holdingId = :holdingId ORDER BY atMillis ASC")
    suspend fun allHistory(holdingId: Long): List<PriceSnapshotEntity>
}

@Dao
interface DividendDao {
    @Query("SELECT * FROM dividend_payments ORDER BY paidAtMillis DESC")
    fun observeAll(): Flow<List<DividendPaymentEntity>>

    @Query("SELECT * FROM dividend_payments WHERE holdingId = :holdingId ORDER BY paidAtMillis DESC")
    fun observeForHolding(holdingId: Long): Flow<List<DividendPaymentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(payment: DividendPaymentEntity): Long

    @Delete
    suspend fun delete(payment: DividendPaymentEntity)

    @Query("DELETE FROM dividend_payments WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM dividend_payments WHERE paidAtMillis BETWEEN :startMillis AND :endMillis")
    suspend fun totalBetween(startMillis: Long, endMillis: Long): Double

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM dividend_payments WHERE holdingId = :holdingId")
    suspend fun totalForHolding(holdingId: Long): Double
}

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist_items ORDER BY addedAtMillis ASC")
    fun observeAll(): Flow<List<WatchlistItemEntity>>

    @Query("SELECT COUNT(*) FROM watchlist_items")
    suspend fun count(): Int

    @Query("SELECT * FROM watchlist_items WHERE id = :id")
    suspend fun getById(id: Long): WatchlistItemEntity?

    @Query("UPDATE watchlist_items SET customName = :name WHERE id = :id")
    suspend fun updateCustomName(id: Long, name: String?)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: WatchlistItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<WatchlistItemEntity>)

    @Query("DELETE FROM watchlist_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM watchlist_items WHERE ticker = :ticker")
    suspend fun countForTicker(ticker: String): Int

    @Query("SELECT * FROM watchlist_items WHERE ticker = :ticker LIMIT 1")
    suspend fun getByTicker(ticker: String): WatchlistItemEntity?

    /** Stores the last seen quote so the list can render before the network answers. */
    @Query("""
        UPDATE watchlist_items
        SET cachedPrice = :price,
            cachedPreviousClose = :previousClose,
            cachedName = COALESCE(:name, cachedName),
            cachedCurrency = COALESCE(:currency, cachedCurrency),
            cachedAtMillis = :atMillis
        WHERE ticker = :ticker
    """)
    suspend fun cacheQuote(
        ticker: String,
        price: Double?,
        previousClose: Double?,
        name: String?,
        currency: String?,
        atMillis: Long
    )
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY atMillis DESC, id DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE holdingId = :holdingId ORDER BY atMillis DESC, id DESC")
    fun observeForHolding(holdingId: Long): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tx: TransactionEntity): Long

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    /** Used to stop the same dividend being reinvested twice. */
    @Query("SELECT COUNT(*) FROM transactions WHERE sourceDividendId = :dividendId")
    suspend fun countForDividend(dividendId: Long): Int
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY ticker ASC, id ASC")
    fun observeAll(): Flow<List<AlertEntity>>

    /** Snapshot for the background worker, which has no lifecycle to collect on. */
    @Query("SELECT * FROM alerts WHERE enabled = 1")
    suspend fun enabled(): List<AlertEntity>

    @Query("SELECT COUNT(*) FROM alerts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(alert: AlertEntity): Long

    @Update
    suspend fun update(alert: AlertEntity)

    /**
     * Targeted writes for the two fields the evaluator touches.
     *
     * Not @Update with a copied row: the worker reads its snapshot, then does
     * network calls that take seconds, and writing the whole row back would
     * overwrite an edit the user made on the Alerts screen in between —
     * silently turning a disabled alert back on, or restoring a threshold they
     * had just changed.
     */
    @Query("UPDATE alerts SET triggeredAtMillis = :at, lastValue = :value WHERE id = :id")
    suspend fun markTriggered(id: Long, at: Long?, value: Double?)

    @Query("UPDATE alerts SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM alerts WHERE id = :id")
    suspend fun delete(id: Long)
}
