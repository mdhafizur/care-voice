package com.carevoice.loadtest

import io.gatling.javaapi.core.CoreDsl.feed
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.rampUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import java.time.Duration

/**
 * "100 nurses documenting at once": every virtual user logs in once (a token per user, demo users from users.csv),
 * then for the test duration repeatedly submits a transcript (unique Idempotency-Key, English text from
 * transcripts.csv) and lists the resident's observations, with a short think time like a nurse on a ward round.
 *
 * -Dusers (default 100), -DrampSeconds (30), -DdurationSeconds (120), -DbaseUrl, -DdemoPassword.
 * Assertions: p95 < 300 ms and failed requests < 1 %.
 */
class DocumentationLoadSimulation : Simulation() {
    private val duration = Duration.ofSeconds(LoadSettings.durationSeconds)

    private val nurses =
        scenario("100 nurses documenting at once")
            .feed(CareVoiceRequests.users())
            .exec(CareVoiceRequests.login)
            .during(duration)
            .on(
                feed(CareVoiceRequests.transcripts())
                    .exec(CareVoiceRequests.submitTranscript)
                    .pause(Duration.ofMillis(500), Duration.ofSeconds(2))
                    .exec(CareVoiceRequests.listObservations)
                    .pause(Duration.ofSeconds(1), Duration.ofSeconds(3)),
            )

    init {
        setUp(nurses.injectOpen(rampUsers(LoadSettings.users).during(LoadSettings.rampSeconds)))
            .protocols(CareVoiceRequests.httpProtocol)
            .assertions(
                global().responseTime().percentile(95.0).lt(LoadSettings.P95_MILLIS),
                global().failedRequests().percent().lt(LoadSettings.MAX_ERROR_PERCENT),
            )
    }
}
