package com.carevoice.loadtest

import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.rampUsersPerSec
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation

/**
 * Ramp until errors: the arrival rate grows linearly from 1 to -DmaxUsersPerSec (default 50) new nurses per second
 * over -DdurationSeconds (default 120). Each nurse logs in, submits one transcript and lists observations (a nurse
 * whose login fails stops there). The report's "responses per second" and error charts show the arrival rate at which
 * errors and latency start to climb; the assertions (same as the load test) are expected to fail on a real stress run.
 */
class StressSimulation : Simulation() {
    private val burst =
        scenario("Stress: ramp nurses until errors")
            .feed(CareVoiceRequests.users())
            .exec(CareVoiceRequests.login)
            .exitHereIfFailed()
            .feed(CareVoiceRequests.transcripts())
            .exec(CareVoiceRequests.submitTranscript)
            .exec(CareVoiceRequests.listObservations)

    init {
        setUp(
            burst.injectOpen(
                rampUsersPerSec(1.0).to(LoadSettings.maxUsersPerSec).during(LoadSettings.durationSeconds),
            ),
        ).protocols(CareVoiceRequests.httpProtocol)
            .assertions(
                global().responseTime().percentile(95.0).lt(LoadSettings.P95_MILLIS),
                global().failedRequests().percent().lt(LoadSettings.MAX_ERROR_PERCENT),
            )
    }
}
