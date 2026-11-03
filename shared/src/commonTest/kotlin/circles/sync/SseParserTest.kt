package circles.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class SseParserTest {
    private val one = "data: {\"serverSeq\":7}\n\n"

    @Test fun aWholeMessageGivesItsNumber() {
        assertEquals(listOf(7L), SseParser().feed(one))
    }

    @Test fun severalMessagesInOnePiece() {
        assertEquals(listOf(1L, 2L, 3L), SseParser().feed("data: {\"serverSeq\":1}\n\ndata: {\"serverSeq\":2}\n\ndata: {\"serverSeq\":3}\n\n"))
    }

    @Test fun aMessageCutAnywhereStillArrivesOnce() {
        for (cut in 1 until one.length) {
            val parser = SseParser()
            val got = parser.feed(one.substring(0, cut)) + parser.feed(one.substring(cut))
            assertEquals(listOf(7L), got, "cut at $cut")
        }
    }

    @Test fun oneCharacterAtATime() {
        val parser = SseParser()
        assertEquals(listOf(7L, 8L), (one + "data: {\"serverSeq\":8}\n\n").map { parser.feed(it.toString()) }.flatten())
    }

    @Test fun lineEndingsMayBeLfCrLfOrCr() {
        assertEquals(listOf(5L), SseParser().feed("data: {\"serverSeq\":5}\r\n\r\n"))
        assertEquals(listOf(5L), SseParser().feed("data: {\"serverSeq\":5}\r\r"))
        val split = SseParser()
        assertEquals(emptyList(), split.feed("data: {\"serverSeq\":5}\r"))
        assertEquals(listOf(5L), split.feed("\n\n"))
    }

    @Test fun commentsRetryAndOtherFieldsAreIgnored() {
        val text = "retry: 5000\n: keep-alive\nevent: ping\nid: 4\n$one"
        assertEquals(listOf(7L), SseParser().feed(text))
    }

    @Test fun messagesThatAreNotUnderstoodAreIgnored() {
        val text = listOf(
            "data: not json", "data: [1,2]", "data: {}", "data: {\"serverSeq\":\"7\"}", "data: {\"serverSeq\":-1}", "data: {\"serverSeq\":1.5}",
            "data: {\"serverSeq\":null}", "data:", "dat: {\"serverSeq\":9}",
        ).joinToString("") { "$it\n\n" }
        assertEquals(emptyList(), SseParser().feed(text))
    }

    @Test fun theSpaceAfterTheColonIsOptional() {
        assertEquals(listOf(3L), SseParser().feed("data:{\"serverSeq\":3}\n"))
    }

    @Test fun largeNumbersSurvive() {
        assertEquals(listOf(9_007_199_254_740_991L), SseParser().feed("data: {\"serverSeq\":9007199254740991}\n"))
    }

    @Test fun anEndlessLineDoesNotGrowWithoutLimit() {
        val parser = SseParser()
        repeat(40) { parser.feed("x".repeat(4_096)) }
        assertEquals(listOf(7L), parser.feed("\n$one"), "the parser recovers after dropping the junk")
    }
}
