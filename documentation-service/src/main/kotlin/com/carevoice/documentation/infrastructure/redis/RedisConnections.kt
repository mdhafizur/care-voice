package com.carevoice.documentation.infrastructure.redis

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.TimeoutOptions
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Owns the Lettuce client and its two connections (commands, pub/sub). Accepts `redis://`, `rediss://` and Sentinel
 * URLs (`redis-sentinel://h1:26379,h2:26379/0#mymaster`); with Sentinel, Lettuce follows a failover to the new primary.
 *
 * Redis is NOT on the critical path (docs/CONTRACTS.md, section 9a): when it is unreachable at startup the service
 * starts anyway and [start] keeps trying to connect in the background; once connected, Lettuce reconnects (and
 * re-subscribes) by itself. Commands are rejected immediately while disconnected instead of being queued, so callers
 * fail open within milliseconds rather than waiting for a timeout on every request.
 */
class RedisConnections(
    redisUrl: String,
    meterRegistry: MeterRegistry,
    private val commandTimeout: Duration = 500.milliseconds,
    private val reconnectInterval: Duration = 5.seconds,
) {
    private val log = LoggerFactory.getLogger(RedisConnections::class.java)
    private val client: RedisClient =
        RedisClient.create(RedisURI.create(redisUrl)).apply {
            setOptions(
                ClientOptions
                    .builder()
                    .autoReconnect(true)
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .timeoutOptions(TimeoutOptions.enabled(commandTimeout.toJavaDuration()))
                    .build(),
            )
        }

    @Volatile
    private var commands: StatefulRedisConnection<String, String>? = null

    @Volatile
    private var pubSub: StatefulRedisPubSubConnection<String, String>? = null
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()
    private var connector: Job? = null

    init {
        Gauge
            .builder(UP_METRIC, this) { if (it.isUp) 1.0 else 0.0 }
            .description("1 when Redis is reachable, 0 while the service runs degraded without it")
            .register(meterRegistry)
    }

    /** True while the command connection is open. */
    val isUp: Boolean get() = commands?.isOpen == true

    /** The command connection, or null while Redis has never been reachable. */
    internal fun commandsOrNull(): StatefulRedisConnection<String, String>? = commands?.takeIf { it.isOpen }

    /** Connects now (best effort) and keeps retrying in the background until both connections exist. */
    fun start(scope: CoroutineScope) {
        connector =
            scope.launch(Dispatchers.IO + CoroutineName("redis-connector")) {
                while (isActive && (commands == null || pubSub == null)) {
                    try {
                        connect()
                    } catch (e: Exception) {
                        log.event(
                            Level.WARN,
                            LogType.EXTERNAL_REQUEST,
                            "Redis unreachable, running degraded",
                            "error" to e.javaClass.simpleName,
                        )
                        delay(reconnectInterval)
                    }
                }
            }
    }

    private fun connect() {
        if (commands == null) commands = client.connect()
        if (pubSub == null) {
            val connection = client.connectPubSub()
            connection.addListener(
                object : RedisPubSubAdapter<String, String>() {
                    override fun message(channel: String, message: String) {
                        listeners[channel]?.forEach { listener ->
                            runCatching { listener(message) }.onFailure {
                                log.event(
                                    Level.WARN,
                                    LogType.EXTERNAL_REQUEST,
                                    "Redis message handler failed",
                                    "channel" to channel,
                                    cause = it,
                                )
                            }
                        }
                    }
                },
            )
            if (listeners.isNotEmpty()) connection.sync().subscribe(*listeners.keys.toTypedArray())
            pubSub = connection
        }
        log.event(Level.INFO, LogType.EXTERNAL_REQUEST, "Redis connected")
    }

    /** Registers a listener for [channel]; subscribes now if connected, otherwise on connect. */
    fun subscribe(channel: String, listener: (String) -> Unit) {
        val channelListeners = listeners.computeIfAbsent(channel) { CopyOnWriteArrayList() }
        channelListeners.add(listener)
        if (channelListeners.size == 1) pubSub?.async()?.subscribe(channel)
    }

    /** Publishes [message] on [channel]; throws when Redis is unavailable so the caller can fall back. */
    suspend fun publish(channel: String, message: String) {
        val connection = commandsOrNull() ?: throw RedisUnavailableException()
        connection.async().publish(channel, message).await()
    }

    suspend fun close() {
        connector?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            runCatching { pubSub?.close() }
            runCatching { commands?.close() }
            runCatching { client.shutdown() }
        }
    }

    companion object {
        const val UP_METRIC = "carevoice.redis.up"
    }
}

class RedisUnavailableException : RuntimeException("Redis is not connected")
