package com.carevoice.documentation.application

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Startup task: seeds the demo tenant, wards, residents and users (docs/CONTRACTS.md, section 5) when
 * `DEMO_SEED_ENABLED=true`. Seed data is never a migration: it is environment-specific and must not reach production.
 *
 * Safe when several pods start at the same moment: the work runs under the advisory lock `demo-seed`, and the
 * repository inserts with `ON CONFLICT DO NOTHING`, so a pod that loses the lock race (or a restart) changes nothing.
 */
class DemoSeeder(
    private val repository: DemoDataRepository,
    private val transactions: TransactionRunner,
    private val hasher: PasswordHasher,
) {
    private val log = LoggerFactory.getLogger(DemoSeeder::class.java)

    /** Returns the number of inserted rows, or null when another instance was seeding at the same time. */
    suspend fun seed(password: String): Int? {
        // Step 1: hash the demo password on the CPU pool, because bcrypt is slow on purpose.
        val hash = withContext(Dispatchers.Default) { hasher.hash(password) }
        // Step 2: insert the missing demo rows while holding a Postgres advisory lock (a named lock held by
        // one connection), so only one instance seeds. Null means another one had the lock.
        val inserted = transactions.tryWithAdvisoryLock(LOCK_NAME) { repository.insertIfAbsent(dataset(hash)) }
        // Step 3: log how many rows were inserted, or that seeding was skipped.
        log.event(
            Level.INFO,
            LogType.BUSINESS,
            "Demo seed finished",
            "insertedRows" to inserted,
            "skipped" to (inserted == null),
        )
        return inserted
    }

    companion object {
        const val LOCK_NAME = "carevoice:demo-seed"
        const val TENANT = "sunrise-care"

        /** The fixed demo data: two wards with three residents each, and three users who all share [passwordHash]. */
        fun dataset(passwordHash: String): DemoDataset {
            val residents =
                (1..6).map { n ->
                    val ward = if (n <= 3) "ward-a" else "ward-b"
                    Resident(id = "res-$n", tenantId = TENANT, wardId = ward, displayName = DEMO_NAMES[n - 1])
                }
            return DemoDataset(
                tenantId = TENANT,
                tenantName = "Sunrise Care",
                wards = listOf(DemoWard("ward-a", "Maple Ward"), DemoWard("ward-b", "Oak Ward")),
                residents = residents,
                users =
                listOf(
                    UserAccount("nurse.anna", TENANT, "ward-a", Role.NURSE, passwordHash),
                    UserAccount("nurse.ben", TENANT, "ward-b", Role.NURSE, passwordHash),
                    UserAccount("lead.clara", TENANT, "ward-a", Role.WARD_LEAD, passwordHash),
                ),
            )
        }

        private val DEMO_NAMES =
            listOf("Margaret Smith", "Harold Jones", "Edith Brown", "Walter Taylor", "Dorothy Wilson", "Arthur Davies")
    }
}
