package com.carevoice.documentation.infrastructure.tracing

import com.carevoice.documentation.application.Correlation
import com.carevoice.documentation.application.CorrelationProvider
import io.opentelemetry.api.trace.Span
import org.slf4j.MDC

/**
 * Adapter: reads the request id from the log MDC (set by Ktor CallId) and the W3C `traceparent` of the current span.
 *
 * Only the OpenTelemetry API is on the classpath; the Java agent supplies the implementation at runtime. Without the
 * agent (tests, local runs) `Span.current()` is the invalid no-op span and no traceparent is stored.
 */
class MdcCorrelationProvider : CorrelationProvider {
    /**
     * Builds the correlation ids for the current request: `requestId` from the MDC and a `traceparent` header value
     * from the current span, or null when there is no real span.
     */
    override fun current(): Correlation {
        val context = Span.current().spanContext
        val traceparent =
            if (context.isValid) {
                "00-${context.traceId}-${context.spanId}-${context.traceFlags.asHex()}"
            } else {
                null
            }
        return Correlation(requestId = MDC.get(REQUEST_ID_KEY), traceparent = traceparent)
    }

    companion object {
        const val REQUEST_ID_KEY = "requestId"
    }
}
