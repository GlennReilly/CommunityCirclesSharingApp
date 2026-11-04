package circles.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The small JSON reader and writer behind the browser's wire format (ADR 0054), on both targets. */
class JsonTest {
    private fun obj(vararg f: Pair<String, JsonNode>) = JsonNode.Obj(linkedMapOf(*f))

    @Test
    fun readsEveryKindOfValueAndIgnoresWhitespace() {
        val parsed = Json.parse(""" { "a" : [1, -2, 0, true, false, null, "x"], "b": {}, "c": [] , "d" : "é\u0041\n" } """)
        assertEquals(
            obj(
                "a" to JsonNode.Arr(listOf(JsonNode.Num(1), JsonNode.Num(-2), JsonNode.Num(0), JsonNode.Bool(true), JsonNode.Bool(false), JsonNode.Null, JsonNode.Str("x"))),
                "b" to obj(), "c" to JsonNode.Arr(emptyList()), "d" to JsonNode.Str("éA\n"),
            ),
            parsed,
        )
    }

    @Test
    fun refusesEverythingOutsideTheSubset() {
        val bad = listOf(
            "", " ", "{", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "{a:1}", "[1 2]", "01", "-", "1.5", "1e3", "+1", "nul", "tru", "\"abc", "\"a\\qb\"", "\"\\u12\"", "\"\\u12G4\"", "\"\\u-001\"", "\"\\u+041\"",
            "{\"a\":1,\"a\":2}", "1 2", "[1]]", "\"tab\there\"", "9223372036854775808", "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[1]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]",
        )
        for (text in bad) assertNull(Json.parse(text), "should be refused: $text")
    }

    @Test
    fun numbersKeepTheirFullLongRange() {
        assertEquals(JsonNode.Num(Long.MAX_VALUE), Json.parse("9223372036854775807"))
        assertEquals(JsonNode.Num(Long.MIN_VALUE), Json.parse("-9223372036854775808"))
    }

    @Test
    fun writesCompactJsonInInsertionOrderWithTheSameEscapesAsKotlinx() {
        val text = "quote\" back\\ nl\n cr\r tab\t bs\b ff\u000C nul\u0000 us\u001F é €"
        val node = obj("z" to JsonNode.Str(text), "a" to JsonNode.Arr(listOf(JsonNode.Num(7), JsonNode.Bool(true), JsonNode.Null)))
        val written = Json.write(node)
        assertEquals("""{"z":"quote\" back\\ nl\n cr\r tab\t bs\b ff\f nul\u0000 us\u001f é €","a":[7,true,null]}""", written)
        assertEquals(node, Json.parse(written))
    }

    @Test
    fun roundTripsEveryAsciiCharacterAndSurrogatePairs() {
        val all = (0..127).map { it.toChar() }.joinToString("") + "😀 \uD83D\uDE00"
        assertEquals(JsonNode.Str(all), Json.parse(Json.write(JsonNode.Str(all))))
        assertNotNull(Json.parse("\"\\ud83d\\ude00\""))
    }
}
