package com.carevoice.alert.infrastructure.persistence

import com.carevoice.alert.application.DependencyHealthCheck
import com.carevoice.alert.config.MongoConfig
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ReadConcern
import com.mongodb.ReadPreference
import com.mongodb.WriteConcern
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.mongodb.MongoMetricsCommandListener
import io.micrometer.core.instrument.binder.mongodb.MongoMetricsConnectionPoolListener
import kotlinx.coroutines.withTimeoutOrNull
import org.bson.Document
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Factory pattern: builds the one [MongoClient] of the process with every setting explicit instead of relying on
 * driver defaults or on whatever the URI happens to contain (explicit settings below override the URI).
 *
 * - **writeConcern majority (+ wtimeout)**: an alert is acknowledged to the caller (and the AMQP message acked) only
 *   when a majority of the replica set has it, so a primary failover cannot roll back an alert we already confirmed.
 *   The wtimeout bounds how long a write waits when the majority is unreachable.
 * - **readConcern majority + readPreference primary**: decisions (is this event processed? which version is current?)
 *   are made on data that cannot be rolled back, and reads see our own writes.
 * - **retryable reads/writes**: one transparent retry across a failover or a transient network error.
 * - **short server-selection timeout**: during a failover the request fails fast with a timeout that is mapped to
 *   503 + Retry-After, instead of holding HTTP threads and AMQP deliveries for the driver's 30 s default.
 * - **pool**: `MONGO_POOL_SIZE` per server, bounded wait for a connection, so a saturated pool surfaces as an error
 *   instead of an unbounded queue; pool and command metrics are published through Micrometer.
 */
object MongoClientFactory {
    val SERVER_SELECTION_TIMEOUT: Duration = 3.seconds
    val WRITE_CONCERN_TIMEOUT: Duration = 5.seconds

    /**
     * All client settings, see the class comment for the reasons. The URI is applied first, so the explicit
     * settings after it win. Also used on its own, without creating a client.
     */
    fun settings(config: MongoConfig, registry: MeterRegistry): MongoClientSettings = MongoClientSettings
        .builder()
        .applyConnectionString(ConnectionString(config.uri))
        .applicationName("alert-service")
        .writeConcern(
            WriteConcern.MAJORITY.withWTimeout(WRITE_CONCERN_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS),
        )
        .readConcern(ReadConcern.MAJORITY)
        .readPreference(ReadPreference.primary())
        .retryWrites(true)
        .retryReads(true)
        .applyToClusterSettings {
            it.serverSelectionTimeout(SERVER_SELECTION_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }.applyToSocketSettings {
            // Opening a connection to a server may take at most 3 s.
            it.connectTimeout(3, TimeUnit.SECONDS)
            // A read on an open connection fails after 10 s without an answer from the server.
            it.readTimeout(10, TimeUnit.SECONDS)
        }.applyToConnectionPoolSettings {
            it.maxSize(config.poolSize)
            // Keep 2 connections open even when idle (fewer when the pool itself is smaller).
            it.minSize(minOf(2, config.poolSize))
            // A request waits at most 2 s for a free connection, then fails (the bounded wait in the class comment).
            it.maxWaitTime(2, TimeUnit.SECONDS)
            // A connection that was not used for 60 s is closed.
            it.maxConnectionIdleTime(60, TimeUnit.SECONDS)
            it.addConnectionPoolListener(MongoMetricsConnectionPoolListener(registry))
        }.addCommandListener(MongoMetricsCommandListener(registry))
        .build()

    /** Creates the client; call it once per process, because each client keeps its own connection pool. */
    fun create(config: MongoConfig, registry: MeterRegistry): MongoClient =
        MongoClient.create(settings(config, registry))
}

/**
 * Adapter for the readiness port: ready only while a WRITABLE PRIMARY answers. A reachable secondary is not enough —
 * every write (and every majority read we rely on) needs the primary.
 */
class MongoHealthCheck(private val database: MongoDatabase, private val timeout: Duration = 2.seconds) :
    DependencyHealthCheck {
    override val name: String = "mongo"

    /** Errors propagate to the readiness use case, which reports DOWN and logs the cause once. */
    override suspend fun isHealthy(): Boolean = withTimeoutOrNull(timeout) {
        // `hello` asks the server about its role; the 1 is only the value the command needs.
        // See https://www.mongodb.com/docs/manual/reference/command/hello/
        val hello = database.runCommand(Document("hello", 1), ReadPreference.primary(), Document::class.java)
        // `isWritablePrimary` is true on the primary; older servers call the same field `ismaster`.
        hello.getBoolean("isWritablePrimary", false) || hello.getBoolean("ismaster", false)
    } ?: false
}
