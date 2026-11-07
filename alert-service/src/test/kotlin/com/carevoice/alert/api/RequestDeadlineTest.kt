package com.carevoice.alert.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@DisplayName("Request deadline")
class RequestDeadlineTest {
    private fun withDeadlineApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            installStatusPages()
            installRequestDeadline(200.milliseconds, exemptPaths = setOf("/exempt"))
            routing {
                get("/fast") { call.respondText("ok") }
                get("/slow") {
                    delay(5.seconds)
                    call.respondText("too late")
                }
                get("/exempt") {
                    delay(500.milliseconds)
                    call.respondText("done")
                }
            }
        }
        block()
    }

    @Test
    fun `should leave a request that finishes in time untouched`() = withDeadlineApp {
        val response = client.get("/fast")

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.bodyAsText()).isEqualTo("ok")
    }

    @Test
    fun `should cancel a request past the deadline and answer 503 with Retry-After`() = withDeadlineApp {
        val response = client.get("/slow")

        assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
        assertThat(response.headers[HttpHeaders.RetryAfter]).isEqualTo("2")
        assertThat(response.headers[HttpHeaders.ContentType]).startsWith("application/problem+json")
        assertThat(response.bodyAsText()).doesNotContain("too late")
    }

    @Test
    fun `should not bound an exempt path`() = withDeadlineApp {
        val response = client.get("/exempt")

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.bodyAsText()).isEqualTo("done")
    }
}
