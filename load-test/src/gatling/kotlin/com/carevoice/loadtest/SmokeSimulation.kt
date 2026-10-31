package com.carevoice.loadtest

import io.gatling.javaapi.core.CoreDsl.atOnceUsers
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation

/** One user through the main endpoints: a functional check of a deployed stack, not a measurement. */
class SmokeSimulation : Simulation() {
    private val smoke =
        scenario("Smoke: one nurse through the main endpoints")
            .feed(CareVoiceRequests.users())
            .feed(CareVoiceRequests.transcripts())
            .exec(CareVoiceRequests.readiness)
            .exec(CareVoiceRequests.login)
            .exec(CareVoiceRequests.getResident)
            .exec(CareVoiceRequests.submitTranscript)
            .exec(CareVoiceRequests.getTranscript)
            .exec(CareVoiceRequests.listObservations)
            .exec(CareVoiceRequests.searchObservations)

    init {
        setUp(smoke.injectOpen(atOnceUsers(1)))
            .protocols(CareVoiceRequests.httpProtocol)
            .assertions(global().failedRequests().count().shouldBe(0L))
    }
}
