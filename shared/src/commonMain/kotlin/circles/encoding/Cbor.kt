package circles.encoding

/**
 * The CBOR subset used by events (ADR 0012): integers in the signed 64-bit range, byte and text strings,
 * arrays, maps with text keys, booleans and null. No floats, tags or indefinite lengths.
 */
sealed interface CborValue

data class CborInt(val value: Long) : CborValue

data class CborBytes(val value: ByteString) : CborValue

data class CborText(val value: String) : CborValue

data class CborArray(val items: List<CborValue>) : CborValue

/** Keys are text. Encoding order is decided by the encoder, not by insertion order. */
data class CborMap(val entries: Map<String, CborValue>) : CborValue {
    operator fun get(key: String): CborValue? = entries[key]

    companion object {
        val EMPTY: CborMap = CborMap(emptyMap())

        fun of(vararg pairs: Pair<String, CborValue>): CborMap = CborMap(mapOf(*pairs))
    }
}

data class CborBool(val value: Boolean) : CborValue

data object CborNull : CborValue

fun String.cbor(): CborText = CborText(this)

fun Long.cbor(): CborInt = CborInt(this)

fun Int.cbor(): CborInt = CborInt(this.toLong())

fun ByteString.cbor(): CborBytes = CborBytes(this)
