package com.carevoice.domain.common

/**
 * A minimal Either: a value is either an error ([Left]) or a success ([Right]).
 *
 * Why not exceptions? Validation failures are expected outcomes, not exceptional ones.
 * Returning them as values makes every error path visible in the type signature,
 * and the compiler forces callers to handle both cases.
 *
 * Why not Arrow? Arrow's Either is the production choice. This hand-rolled version keeps
 * the domain module dependency-free and is small enough to explain in a minute.
 */
sealed interface Either<out E, out A> {
    data class Left<out E>(val value: E) : Either<E, Nothing>

    data class Right<out A>(val value: A) : Either<Nothing, A>
}

fun <A> A.right(): Either<Nothing, A> = Either.Right(this)

fun <E> E.left(): Either<E, Nothing> = Either.Left(this)

inline fun <E, A, B> Either<E, A>.map(transform: (A) -> B): Either<E, B> = when (this) {
    is Either.Left -> this
    is Either.Right -> Either.Right(transform(value))
}

inline fun <E, A, B> Either<E, A>.flatMap(transform: (A) -> Either<E, B>): Either<E, B> = when (this) {
    is Either.Left -> this
    is Either.Right -> transform(value)
}

inline fun <E, A, R> Either<E, A>.fold(onLeft: (E) -> R, onRight: (A) -> R): R = when (this) {
    is Either.Left -> onLeft(value)
    is Either.Right -> onRight(value)
}

fun <E, A> Either<E, A>.getOrNull(): A? = (this as? Either.Right)?.value

fun <E, A> Either<E, A>.leftOrNull(): E? = (this as? Either.Left)?.value
