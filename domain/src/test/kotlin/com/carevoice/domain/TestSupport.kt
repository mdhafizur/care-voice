package com.carevoice.domain

import com.carevoice.domain.common.Either
import org.assertj.core.api.Assertions.fail

/**
 * Unwraps an [Either] that must be a success. Tests use it to build fixtures through the smart constructors
 * (the only way to obtain a vital sign), and fail with the actual error instead of a ClassCastException.
 */
fun <E, A> Either<E, A>.assertRight(): A = when (this) {
    is Either.Right -> value
    is Either.Left -> fail("Expected Right but was Left($value)")
}

/** Unwraps an [Either] that must be a failure, failing with the unexpected success value otherwise. */
fun <E, A> Either<E, A>.assertLeft(): E = when (this) {
    is Either.Left -> value
    is Either.Right -> fail("Expected Left but was Right($value)")
}
