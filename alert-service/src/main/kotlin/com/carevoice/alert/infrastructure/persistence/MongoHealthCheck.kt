package com.carevoice.alert.infrastructure.persistence

import com.carevoice.alert.application.DependencyHealthCheck
import com.mongodb.ReadPreference
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.withTimeoutOrNull
import org.bson.Document
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
