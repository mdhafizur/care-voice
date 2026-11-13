package com.carevoice.testsupport

import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.mongo.transitions.Mongod
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess
import de.flapdoodle.reverse.TransitionWalker
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.utility.DockerImageName
import redis.embedded.RedisServer
import java.net.ServerSocket

/** Connection details of the PostgreSQL instance the tests run against, whichever backend started it. */
interface PostgresInstance {
    val jdbcUrl: String
    val username: String
    val password: String
}

/** Connection details of the MongoDB instance the tests run against. */
interface MongoInstance {
    val connectionString: String
}

/** Connection details of the Redis instance the tests run against, e.g. "redis://localhost:6379". */
interface RedisInstance {
    val uri: String
}

/** Connection details of the RabbitMQ broker the tests run against, e.g. "amqp://guest:guest@localhost:5672". */
interface RabbitInstance {
    val amqpUri: String
}

/**
 * One database per test JVM, started on first use and shared by every test class.
 * Isolation comes from per-test data (unique tenant/ward ids) and cleanup, not from fresh databases,
 * because starting a database costs seconds.
 *
 * Two interchangeable backends, chosen with the system property / env var `CAREVOICE_TEST_DB`:
 *
 * - `embedded` (default): real PostgreSQL, mongod and redis-server binaries started as local processes, no Docker
 *   needed. The JVM equivalent of MongoMemoryServer: zonky embedded-postgres ships the Postgres binaries
 *   as a Maven artifact, flapdoodle downloads and caches the mongod binary on first use.
 * - `containers`: Testcontainers, the same images as docker-compose. Used in CI so tests run against
 *   exactly the versions production uses.
 *
 * Deliberately NOT H2 or another "Postgres-compatible" in-memory database: the schema relies on
 * FOR UPDATE SKIP LOCKED, partial indexes, JSONB, row-value comparisons for keyset pagination and
 * CHECK constraints. A fake that accepts different SQL would make tests pass that production fails.
 */
object TestDatabases {
    private val backend: String =
        (System.getProperty("CAREVOICE_TEST_DB") ?: System.getenv("CAREVOICE_TEST_DB") ?: "embedded").lowercase()

    /** True for `containers`; any other value means the embedded backend. */
    private val useContainers: Boolean = backend == "containers"

    val postgres: PostgresInstance by lazy { if (useContainers) ContainerPostgres() else EmbeddedPostgresInstance() }

    val mongo: MongoInstance by lazy { if (useContainers) ContainerMongo() else EmbeddedMongoInstance() }

    val redis: RedisInstance by lazy { if (useContainers) ContainerRedis() else EmbeddedRedisInstance() }

    /**
     * RabbitMQ always runs in a container, in both modes: there is no embedded broker that behaves like RabbitMQ
     * (quorum queues, dead-lettering, delivery limits), and a fake would test the fake. Tests that need it call
     * [dockerAvailable] in an assumption and are skipped, not failed, on machines without Docker.
     */
    val rabbit: RabbitInstance by lazy { ContainerRabbit() }

    val dockerAvailable: Boolean by lazy {
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }
}

private class EmbeddedPostgresInstance : PostgresInstance {
    private val postgres: EmbeddedPostgres = EmbeddedPostgres.builder().start()

    init {
        // The JVM exits at the end of the test run; stop the child process with it.
        Runtime.getRuntime().addShutdownHook(Thread { postgres.close() })
    }

    override val jdbcUrl: String = postgres.getJdbcUrl("postgres", "postgres")
    override val username: String = "postgres"
    override val password: String = "postgres"
}

private class ContainerPostgres : PostgresInstance {
    private val container =
        PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:17-alpine")).apply {
            withDatabaseName("carevoice")
            withUsername("carevoice")
            withPassword("carevoice")
            start()
        }

    override val jdbcUrl: String = container.jdbcUrl
    override val username: String = container.username
    override val password: String = container.password
}

private class EmbeddedMongoInstance : MongoInstance {
    private val running: TransitionWalker.ReachedState<RunningMongodProcess> = Mongod.instance().start(
        Version.Main.V8_0,
    )

    init {
        Runtime.getRuntime().addShutdownHook(Thread { running.close() })
    }

    override val connectionString: String =
        running.current().serverAddress.let { "mongodb://${it.host}:${it.port}" }
}

private class ContainerMongo : MongoInstance {
    private val container = MongoDBContainer(DockerImageName.parse("mongo:8.0")).apply { start() }

    override val connectionString: String = container.connectionString
}

private class EmbeddedRedisInstance : RedisInstance {
    // A free port instead of 6379, so a Redis already running on the developer machine is never touched.
    private val port: Int = ServerSocket(0).use { it.localPort }
    private val server: RedisServer = RedisServer(port).apply { start() }

    init {
        Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    }

    override val uri: String = "redis://localhost:$port"
}

private class ContainerRedis : RedisInstance {
    private val container =
        GenericContainer<Nothing>(DockerImageName.parse("redis:7.4-alpine")).apply {
            withExposedPorts(6379)
            start()
        }

    override val uri: String = "redis://${container.host}:${container.getMappedPort(6379)}"
}

private class ContainerRabbit : RabbitInstance {
    private val container = RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine")).apply { start() }

    override val amqpUri: String = container.amqpUrl.replace(
        "amqp://",
        "amqp://${container.adminUsername}:${container.adminPassword}@",
    )
}
