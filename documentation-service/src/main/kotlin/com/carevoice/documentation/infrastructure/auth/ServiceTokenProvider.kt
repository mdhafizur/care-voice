package com.carevoice.documentation.infrastructure.auth

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Caches the 5-minute `SERVICE` token and refreshes it 30 s before expiry, so the outbox relay does not sign a token
 * per event, and never sends one that expires in flight. A [Mutex] makes concurrent publishers share one refresh.
 */
class ServiceTokenProvider(
    private val issuer: JwtIssuer,
    private val clock: Clock,
    private val ttl: Duration = JwtIssuer.SERVICE_TOKEN_TTL,
    private val refreshBefore: Duration = 30.seconds,
) {
    private val mutex = Mutex()
    private val log = LoggerFactory.getLogger(ServiceTokenProvider::class.java)
    private var cached: String? = null
    private var refreshAt: Instant = Instant.MIN

    /** Returns the cached service token, or issues a new one when its refresh time has come. */
    suspend fun token(): String = mutex.withLock {
        val now = clock.instant()
        val current = cached
        if (current != null && now.isBefore(refreshAt)) return@withLock current
        issuer.issueServiceToken(ttl).also {
            cached = it
            refreshAt = now.plus((ttl - refreshBefore).toJavaDuration())
            log.event(
                Level.DEBUG,
                LogType.BUSINESS,
                if (current == null) "Service token created" else "Service token refreshed",
                "refreshAt" to refreshAt.toString(),
            )
        }
    }
}
