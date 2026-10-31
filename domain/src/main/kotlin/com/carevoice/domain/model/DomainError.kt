package com.carevoice.domain.model

/**
 * Every way the domain can reject input. A sealed hierarchy lets the HTTP layer map
 * each case to a status code with an exhaustive `when` (adding a new error type is a
 * compile error until the mapping handles it).
 */
sealed interface DomainError {
    val message: String
}

sealed interface ValidationError : DomainError

/**
 * A value outside what is physiologically possible, e.g. a pulse of 400.
 *
 * Note: the property is `fieldName`, not `field`. Inside a getter, `field` is Kotlin's
 * soft keyword for the backing field, so `get() = "$field..."` would not compile.
 */
data class OutOfRange(val fieldName: String, val value: Number, val min: Number, val max: Number) : ValidationError {
    override val message: String get() = "$fieldName=$value is outside the plausible range [$min, $max]"
}

/** Values that are plausible on their own but contradict each other, e.g. diastolic >= systolic. */
data class InconsistentValues(override val message: String) : ValidationError

data class BlankInput(val fieldName: String) : ValidationError {
    override val message: String get() = "$fieldName must not be blank"
}

data class TooLong(val fieldName: String, val maxLength: Int) : ValidationError {
    override val message: String get() = "$fieldName must be at most $maxLength characters"
}
