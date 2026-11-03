package circles.sync

/**
 * Reads a server-sent event stream a piece at a time (ADR 0026). The only message the server sends is
 * `data: {"serverSeq": N}`, the newest sequence number of the circle; comments (`: keep-alive`) and `retry:` lines are
 * ignored here. Pieces can be cut anywhere, even in the middle of a line, and lines may end in LF, CRLF or CR.
 */
class SseParser {
    private var pending = ""

    /** Adds the next [text] of the stream and returns the sequence numbers of the complete messages it finished. */
    fun feed(text: String): List<Long> {
        pending += text
        val hints = mutableListOf<Long>()
        while (true) {
            val end = pending.indexOfAny(charArrayOf('\n', '\r'))
            if (end < 0) break
            // A lone CR at the very end could be the first half of CRLF: wait for the next piece before cutting.
            if (pending[end] == '\r' && end == pending.length - 1) break
            val line = pending.substring(0, end)
            val skip = if (pending[end] == '\r' && pending.getOrNull(end + 1) == '\n') 2 else 1
            pending = pending.substring(end + skip)
            readLine(line)?.let { hints += it }
        }
        if (pending.length > MAX_PENDING_CHARS) pending = "" // an endless line is not a stream we understand
        return hints
    }

    /** The server writes exactly `{"serverSeq":N}`; anything else is not a hint. (A strict match keeps the JSON tree reader out of the page.) */
    private fun readLine(line: String): Long? {
        if (!line.startsWith(DATA_PREFIX)) return null
        return HINT.matchEntire(line.removePrefix(DATA_PREFIX).trim())?.groupValues?.get(1)?.toLongOrNull()
    }

    private companion object {
        const val DATA_PREFIX = "data:"
        val HINT = Regex("""\{\s*"serverSeq"\s*:\s*(\d{1,16})\s*\}""")
        const val MAX_PENDING_CHARS = 64 * 1024
    }
}
