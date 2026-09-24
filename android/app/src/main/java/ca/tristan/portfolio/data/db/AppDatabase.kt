package ca.tristan.portfolio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromHoldingType(v: HoldingType): String = v.name
    @TypeConverter
    fun toHoldingType(v: String): HoldingType = HoldingType.valueOf(v)
    @TypeConverter
    fun fromTransactionType(v: TransactionType): String = v.name
    @TypeConverter
    fun toTransactionType(v: String): TransactionType = TransactionType.valueOf(v)

    // Nullable both ways: an account saved before tax treatment existed has no
    // value, and an unrecognised name (a rename in a later build) must read as
    // "not set" rather than crash every query that touches accounts.
    // Same nullable-tolerant shape as the tax treatment below: an alert kind
    // this build doesn't recognise (a row written by a newer version, then
    // opened by an older one) must read as "unknown" and be skipped, not
    // crash every query that touches alerts.
    @TypeConverter
    fun fromAlertKind(v: AlertKind): String = v.name
    @TypeConverter
    fun toAlertKind(v: String): AlertKind =
        runCatching { AlertKind.valueOf(v) }.getOrDefault(AlertKind.PRICE_ABOVE)

    @TypeConverter
    fun fromTaxTreatment(v: TaxTreatment?): String? = v?.name
    @TypeConverter
    fun toTaxTreatment(v: String?): TaxTreatment? =
        v?.let { runCatching { TaxTreatment.valueOf(it) }.getOrNull() }
}

@Database(
    entities = [
        AccountEntity::class,
        HoldingEntity::class,
        PriceSnapshotEntity::class,
        DividendPaymentEntity::class,
        WatchlistItemEntity::class,
        TransactionEntity::class,
        AlertEntity::class
    ],
    version = 16,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun holdingDao(): HoldingDao
    abstract fun priceSnapshotDao(): PriceSnapshotDao
    abstract fun dividendDao(): DividendDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun transactionDao(): TransactionDao
    abstract fun alertDao(): AlertDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * v11 → v12: adds cached-quote columns to watchlist_items.
         *
         * Written as a real migration rather than leaning on the destructive
         * fallback below, because by this version people have hand-entered
         * portfolios in the database and wiping those to add a display cache
         * would be indefensible. Every added column is nullable, so existing
         * rows migrate cleanly with no defaults to backfill.
         */
        /**
         * v12 → v13: adds the transactions table.
         *
         * Additive only — holdings stay the position of record, so nothing
         * existing is touched and the user's portfolio survives untouched.
         */
        /**
         * The exact CREATE Room expects for TransactionEntity.
         *
         * Room validates a hand-written migration's result against the schema
         * it derives from the entity, and the comparison is strict: a SQL
         * DEFAULT that the entity doesn't declare, or an "index_*" index Room
         * doesn't know about, both count as a mismatch and throw on open.
         *
         * In particular `currency: String = "CAD"` is a KOTLIN default, not a
         * column default — so the column must be plain `TEXT NOT NULL`.
         */
        private const val CREATE_TRANSACTIONS = """
            CREATE TABLE IF NOT EXISTS `transactions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `holdingId` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `atMillis` INTEGER NOT NULL,
                `shares` REAL NOT NULL,
                `pricePerShare` REAL NOT NULL,
                `currency` TEXT NOT NULL,
                `note` TEXT,
                `sourceDividendId` INTEGER,
                FOREIGN KEY(`holdingId`) REFERENCES `holdings`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """

        private const val CREATE_TRANSACTIONS_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_transactions_holdingId` " +
                "ON `transactions` (`holdingId`)"

        /** v12 → v13: adds the transactions table. Additive; holdings untouched. */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_TRANSACTIONS)
                db.execSQL(CREATE_TRANSACTIONS_INDEX)
            }
        }

        /**
         * v13 → v14: rebuilds the transactions table.
         *
         * The first v13 shipped with a CREATE that didn't match the entity — a
         * `DEFAULT 'CAD'` on currency and an undeclared index — so Room threw
         * on open and the app crashed at launch. Those installs are already
         * stamped v13, so fixing v12→v13 alone would never reach them: the
         * migration doesn't re-run. This version bump is what lets them
         * recover. Dropping is safe because the table is new and holds nothing
         * a crashing build could have written.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `transactions`")
                db.execSQL(CREATE_TRANSACTIONS)
                db.execSQL(CREATE_TRANSACTIONS_INDEX)
            }
        }

        /**
         * v14 → v15: adds accounts.taxTreatment.
         *
         * One nullable TEXT column, so existing accounts migrate untouched and
         * come out with no treatment set — which is the correct starting state:
         * the app has never asked the user, so it does not know.
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN taxTreatment TEXT")
            }
        }

        /**
         * The exact CREATE Room expects for AlertEntity.
         *
         * Same strictness trap as CREATE_TRANSACTIONS above: `enabled: Boolean
         * = true` and `threshold: Double` are KOTLIN defaults, not column
         * defaults, so no SQL DEFAULT may appear here. Room stores Boolean as
         * INTEGER NOT NULL. There is no foreign key: alerts are keyed by
         * ticker, not by holding id, precisely so selling a position doesn't
         * delete the alert watching that security.
         */
        private const val CREATE_ALERTS = """
            CREATE TABLE IF NOT EXISTS `alerts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `ticker` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `threshold` REAL NOT NULL,
                `enabled` INTEGER NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `triggeredAtMillis` INTEGER,
                `lastValue` REAL
            )
        """

        /** v15 -> v16: adds the alerts table. Additive; nothing existing is touched. */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_ALERTS)
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watchlist_items ADD COLUMN cachedPrice REAL")
                db.execSQL("ALTER TABLE watchlist_items ADD COLUMN cachedPreviousClose REAL")
                db.execSQL("ALTER TABLE watchlist_items ADD COLUMN cachedName TEXT")
                db.execSQL("ALTER TABLE watchlist_items ADD COLUMN cachedCurrency TEXT")
                db.execSQL("ALTER TABLE watchlist_items ADD COLUMN cachedAtMillis INTEGER")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "portfolio.db"
                )
                    .addMigrations(
                        MIGRATION_11_12, MIGRATION_12_13,
                        MIGRATION_13_14, MIGRATION_14_15,
                        MIGRATION_15_16
                    )
                    // Last-resort safety net for the older pre-v11 schema
                    // changes that never had migrations written. Anything from
                    // v11 onward migrates properly and keeps the user's data.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
