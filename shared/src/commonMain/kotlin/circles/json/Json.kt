package circles.json

private const val MAX_DEPTH = 32
private const val ASCII_CONTROL_LIMIT = 0x20
private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_DIGITS = 4

/**
 * A small strict JSON reader and writer for the few shapes the client sends and reads (ADR 0054). Whole numbers only (no
 * fractions or exponents), no duplicate keys, nesting at most [MAX_DEPTH]. It exists because kotlinx-serialization's JSON
 * support costs about 10 KB gzipped in the browser bundle; the server and the tests still use kotlinx, and the tests check
 * that the two read each other's output.
 */
sealed interface JsonNode {
    data class Obj(val fields: Map<String, JsonNode>) : JsonNode

    data class Arr(val items: List<JsonNode>) : JsonNode

    data class Str(val value: String) : JsonNode

    data class Num(val value: Long) : JsonNode

    data class Bool(val value: Boolean) : JsonNode

    data object Null : JsonNode
}

object Json {
    /** The value in [text], or null if it is not exactly one well-formed JSON value in the supported subset. */
    fun parse(text: String): JsonNode? {
        val reader = Reader(text)
        val value = reader.value(0) ?: return null
        reader.skipSpace()
        return if (reader.atEnd()) value else null
    }

    /** Compact JSON for [node]; object fields come out in insertion order. */
    fun write(node: JsonNode): String {
        val out = StringBuilder()
        put(out, node)
        return out.toString()
    }

    private fun put(out: StringBuilder, node: JsonNode) {
        when (node) {
            is JsonNode.Obj -> {
                out.append('{')
                node.fields.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) out.append(',')
                    quote(out, key)
                    out.append(':')
                    put(out, value)
                }
                out.append('}')
            }
            is JsonNode.Arr -> {
                out.append('[')
                node.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    put(out, item)
                }
                out.append(']')
            }
            is JsonNode.Str -> quote(out, node.value)
            is JsonNode.Num -> out.append(node.value)
            is JsonNode.Bool -> out.append(node.value)
            JsonNode.Null -> out.append("null")
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c.code < ASCII_CONTROL_LIMIT -> out.append("\\u").append(c.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private class Reader(private val text: String) {
        private var at = 0

        fun atEnd(): Boolean = at >= text.length

        fun skipSpace() {
            while (at < text.length && (text[at] == ' ' || text[at] == '\n' || text[at] == '\r' || text[at] == '\t')) at++
        }

        fun value(depth: Int): JsonNode? {
            if (depth > MAX_DEPTH) return null
            skipSpace()
            if (atEnd()) return null
            return when (val c = text[at]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()?.let { JsonNode.Str(it) }
                't' -> literal("true", JsonNode.Bool(true))
                'f' -> literal("false", JsonNode.Bool(false))
                'n' -> literal("null", JsonNode.Null)
                else -> if (c == '-' || c in '0'..'9') number() else null
            }
        }

        private fun literal(word: String, node: JsonNode): JsonNode? =
            if (text.startsWith(word, at)) node.also { at += word.length } else null

        private fun number(): JsonNode? {
            val start = at
            if (text[at] == '-') at++
            val digits = at
            while (at < text.length && text[at] in '0'..'9') at++
            if (at == digits || (text[digits] == '0' && at - digits > 1)) return null
            if (at < text.length && (text[at] == '.' || text[at] == 'e' || text[at] == 'E')) return null // whole numbers only
            return text.substring(start, at).toLongOrNull()?.let { JsonNode.Num(it) }
        }

        private fun string(): String? {
            at++ // opening quote
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) return null
                        when (val e = text[at++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (at + UNICODE_ESCAPE_DIGITS > text.length) return null
                                val digits = text.substring(at, at + UNICODE_ESCAPE_DIGITS)
                                if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                                val code = digits.toInt(HEX_RADIX)
                                out.append(code.toChar())
                                at += UNICODE_ESCAPE_DIGITS
                            }
                            else -> return null
                        }
                    }
                    c.code < ASCII_CONTROL_LIMIT -> return null
                    else -> out.append(c)
                }
            }
            return null
        }

        private fun arr(depth: Int): JsonNode? {
            at++
            val items = mutableListOf<JsonNode>()
            skipSpace()
            if (!atEnd() && text[at] == ']') return JsonNode.Arr(items).also { at++ }
            while (true) {
                items += value(depth + 1) ?: return null
                skipSpace()
                if (atEnd()) return null
                when (text[at++]) {
                    ',' -> Unit
                    ']' -> return JsonNode.Arr(items)
                    else -> return null
                }
            }
        }

        private fun obj(depth: Int): JsonNode? {
            at++
            val fields = linkedMapOf<String, JsonNode>()
            skipSpace()
            if (!atEnd() && text[at] == '}') return JsonNode.Obj(fields).also { at++ }
            while (true) {
                skipSpace()
                if (atEnd() || text[at] != '"') return null
                val key = string() ?: return null
                skipSpace()
                if (atEnd() || text[at++] != ':') return null
                if (fields.containsKey(key)) return null
                fields[key] = value(depth + 1) ?: return null
                skipSpace()
                if (atEnd()) return null
                when (text[at++]) {
                    ',' -> Unit
                    '}' -> return JsonNode.Obj(fields)
                    else -> return null
                }
            }
        }
    }
}
