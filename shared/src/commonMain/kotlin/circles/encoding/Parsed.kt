package circles.encoding

/** Result of parsing untrusted input: a value, or the reason it was refused. Used instead of exceptions. */
sealed interface Parsed<out T> {
    data class Ok<out T>(val value: T) : Parsed<T>

    data class Invalid(val reason: String) : Parsed<Nothing>
}

inline fun <T, R> Parsed<T>.map(transform: (T) -> R): Parsed<R> =
    when (this) {
        is Parsed.Ok -> Parsed.Ok(transform(value))
        is Parsed.Invalid -> this
    }
