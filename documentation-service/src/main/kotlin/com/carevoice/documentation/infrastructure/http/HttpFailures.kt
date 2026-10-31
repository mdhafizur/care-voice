package com.carevoice.documentation.infrastructure.http

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Why an HTTP call produced no response. Each kind maps to a short, stable reason string for logs and last_error. */
enum class TransportFailure(val reason: String) {
    TIMEOUT("timeout"),
    CONNECTION("connection error"),
}

/** Translates exceptions of the Ktor client into [TransportFailure]s, the same way for every outbound adapter. */
object HttpFailures {
    /**
     * Classifies an exception thrown by a Ktor client call, or returns null when it is not a transport failure (a
     * bug, e.g. a serialization error).
     *
     * Note the ordering: Ktor's timeout exceptions are IOExceptions too, so they are matched first. A
     * [CancellationException] is never classified, so cancellation always propagates. Since Ktor 2,
     * [HttpRequestTimeoutException] is an IOException rather than a CancellationException, so catching it cannot
     * swallow a real cancellation.
     */
    fun classify(e: Throwable): TransportFailure? = when (e) {
        is CancellationException -> null

        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is SocketTimeoutException,
        -> TransportFailure.TIMEOUT

        is IOException -> TransportFailure.CONNECTION

        else -> null
    }
}
