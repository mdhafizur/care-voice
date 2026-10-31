package com.carevoice.loadtest

import io.gatling.javaapi.core.ChainBuilder
import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.bodyString
import io.gatling.javaapi.core.CoreDsl.csv
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.FeederBuilder
import io.gatling.javaapi.http.HttpDsl.header
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import io.gatling.javaapi.http.HttpProtocolBuilder
import java.util.UUID

/**
 * Settings shared by all simulations, read from system properties (`-Dname=value` on the Gradle command line; the
 * build forwards them to the Gatling JVM).
 */
object LoadSettings {
    val baseUrl: String = System.getProperty("baseUrl", "http://localhost:8080")
    val users: Int = Integer.getInteger("users", 100)
    val durationSeconds: Long = java.lang.Long.getLong("durationSeconds", 120)
    val rampSeconds: Long = java.lang.Long.getLong("rampSeconds", 30)
    val maxUsersPerSec: Double = System.getProperty("maxUsersPerSec", "50").toDouble()

    /** Password of the demo users (DEMO_PASSWORD of the stack). Required: there is no default secret. */
    val demoPassword: String =
        System.getProperty("demoPassword")
            ?: error("Pass the demo users' password with -DdemoPassword=... (DEMO_PASSWORD in .env)")

    /** Acceptance criteria of the load tests: p95 < 300 ms, error rate < 1 %. */
    const val P95_MILLIS = 300
    const val MAX_ERROR_PERCENT = 1.0
}

/** Request building blocks; every chain expects `username` and `residentId` in the session (users.csv). */
object CareVoiceRequests {
    val httpProtocol: HttpProtocolBuilder =
        http
            .baseUrl(LoadSettings.baseUrl)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("carevoice-gatling")

    fun users(): FeederBuilder<String> = csv("users.csv").circular()

    fun transcripts(): FeederBuilder<String> = csv("transcripts.csv").random()

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
        append('"')
    }

    /** POST /v1/auth/token once per virtual user; the token is kept in the session as `token`. */
    val login: ChainBuilder =
        exec(
            http("POST /v1/auth/token")
                .post("/v1/auth/token")
                .body(
                    StringBody { session ->
                        val username = json(session.getString("username")!!)
                        """{"username":$username,"password":${json(LoadSettings.demoPassword)}}"""
                    },
                ).check(status().shouldBe(200))
                .check(jsonPath("$.accessToken").saveAs("token")),
        )

    /** POST a transcript with a fresh Idempotency-Key (one per request, so nothing is replayed). */
    val submitTranscript: ChainBuilder =
        exec { session -> session.set("idempotencyKey", UUID.randomUUID().toString()) }
            .exec(
                http("POST /v1/residents/{residentId}/transcripts")
                    .post("/v1/residents/#{residentId}/transcripts")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", "#{idempotencyKey}")
                    .body(StringBody { session -> """{"transcript":${json(session.getString("transcript")!!)}}""" })
                    .check(status().shouldBe(201))
                    .check(header("Location").saveAs("transcriptLocation")),
            )

    val getTranscript: ChainBuilder =
        exec(
            http("GET /v1/transcripts/{transcriptId}")
                .get("#{transcriptLocation}")
                .header("Authorization", "Bearer #{token}")
                .check(status().shouldBe(200)),
        )

    val listObservations: ChainBuilder =
        exec(
            http("GET /v1/residents/{residentId}/observations")
                .get("/v1/residents/#{residentId}/observations?limit=20")
                .header("Authorization", "Bearer #{token}")
                .check(status().shouldBe(200)),
        )

    val getResident: ChainBuilder =
        exec(
            http("GET /v1/residents/{residentId}")
                .get("/v1/residents/#{residentId}")
                .header("Authorization", "Bearer #{token}")
                .check(status().shouldBe(200)),
        )

    val searchObservations: ChainBuilder =
        exec(
            http("POST /v1/observations/search")
                .post("/v1/observations/search")
                .header("Authorization", "Bearer #{token}")
                .body(StringBody("""{"residentIds":["#{residentId}"],"limit":20}"""))
                .check(status().shouldBe(200)),
        )

    val readiness: ChainBuilder =
        exec(http("GET /readyz").get("/readyz").check(status().shouldBe(200)).check(bodyString().exists()))
}
