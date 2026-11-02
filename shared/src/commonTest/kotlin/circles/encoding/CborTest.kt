package circles.encoding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Vectors are from RFC 8949 Appendix A, so the same bytes are pinned on the JVM and in JS. */
class CborTest {
    private fun hex(value: CborValue): String = ByteString.of(CborEncoder.encode(value)).toHex()

    private fun decode(hex: String): Parsed<CborValue> = CborDecoder.decode(ByteString.fromHex(hex)!!.toByteArray())

    private fun assertInvalid(hex: String, fragment: String? = null) {
        val result = decode(hex)
        assertIs<Parsed.Invalid>(result, "expected $hex to be refused")
        if (fragment != null) assertTrue(result.reason.contains(fragment), "reason '${result.reason}' should mention '$fragment'")
    }

    private val vectors: List<Pair<CborValue, String>> = listOf(
        CborInt(0) to "00",
        CborInt(1) to "01",
        CborInt(10) to "0a",
        CborInt(23) to "17",
        CborInt(24) to "1818",
        CborInt(25) to "1819",
        CborInt(100) to "1864",
        CborInt(255) to "18ff",
        CborInt(256) to "190100",
        CborInt(1000) to "1903e8",
        CborInt(65535) to "19ffff",
        CborInt(65536) to "1a00010000",
        CborInt(1_000_000) to "1a000f4240",
        CborInt(4_294_967_295) to "1affffffff",
        CborInt(4_294_967_296) to "1b0000000100000000",
        CborInt(1_000_000_000_000) to "1b000000e8d4a51000",
        CborInt(Long.MAX_VALUE) to "1b7fffffffffffffff",
        CborInt(-1) to "20",
        CborInt(-10) to "29",
        CborInt(-100) to "3863",
        CborInt(-1000) to "3903e7",
        CborInt(Long.MIN_VALUE) to "3b7fffffffffffffff",
        CborBytes(ByteString.EMPTY) to "40",
        CborBytes(ByteString.fromHex("01020304")!!) to "4401020304",
        CborText("") to "60",
        CborText("a") to "6161",
        CborText("IETF") to "6449455446",
        CborText("\"\\") to "62225c",
        CborText("ü") to "62c3bc",
        CborText("水") to "63e6b0b4",
        CborArray(emptyList()) to "80",
        CborArray(listOf(CborInt(1), CborInt(2), CborInt(3))) to "83010203",
        CborArray(listOf(CborInt(1), CborArray(listOf(CborInt(2), CborInt(3))), CborArray(listOf(CborInt(4), CborInt(5))))) to "8301820203820405",
        CborMap.EMPTY to "a0",
        CborMap.of("a" to CborInt(1), "b" to CborArray(listOf(CborInt(2), CborInt(3)))) to "a26161016162820203",
        CborBool(false) to "f4",
        CborBool(true) to "f5",
        CborNull to "f6",
    )

    @Test
    fun encodesTheRfcVectors() {
        vectors.forEach { (value, expected) -> assertEquals(expected, hex(value), "encoding of $value") }
    }

    @Test
    fun decodesTheRfcVectorsBackToTheSameValues() {
        vectors.forEach { (value, hex) -> assertEquals(Parsed.Ok(value), decode(hex), "decoding of $hex") }
    }

    @Test
    fun mapKeysAreSortedByEncodedBytesNotInsertionOrder() {
        // "b" encodes as 6162 and "aa" as 626161: the shorter key sorts first.
        val forward = CborMap(linkedMapOf("b" to CborInt(1), "aa" to CborInt(2)))
        val reverse = CborMap(linkedMapOf("aa" to CborInt(2), "b" to CborInt(1)))
        assertEquals("a26162016261610" + "2", hex(forward))
        assertEquals(hex(forward), hex(reverse))
    }

    @Test
    fun nestedStructuresRoundTrip() {
        val value = CborMap.of(
            "list" to CborArray(listOf(CborNull, CborBool(true), CborInt(-5), CborText("x"))),
            "bytes" to CborBytes(ByteString.fromHex("00ff")!!),
            "nested" to CborMap.of("deep" to CborMap.of("n" to CborInt(7))),
        )
        assertEquals(Parsed.Ok(value), CborDecoder.decode(CborEncoder.encode(value)))
    }

    @Test
    fun refusesIntegersAndLengthsNotInShortestForm() {
        assertInvalid("1800", "shortest")
        assertInvalid("1817", "shortest")
        assertInvalid("1900ff", "shortest")
        assertInvalid("1a0000ffff", "shortest")
        assertInvalid("1b00000000ffffffff", "shortest")
        assertInvalid("5800", "shortest") // a byte string length of 0 written with a length byte
    }

    @Test
    fun refusesUnsortedAndDuplicateMapKeys() {
        assertInvalid("a2616201616102", "increasing")
        assertInvalid("a2616101616102", "increasing")
        assertInvalid("a2626161016162" + "02", "increasing") // "aa" before "b" would be fine, "b" then "aa" is not
    }

    @Test
    fun refusesWhatTheSubsetDoesNotCover() {
        assertInvalid("9fff", "indefinite")
        assertInvalid("5fff", "indefinite")
        assertInvalid("c074323031332d30332d32315432303a30343a30305a", "tags")
        assertInvalid("f90000", "float")
        assertInvalid("fb3ff199999999999a", "float")
        assertInvalid("f7", "simple")
        assertInvalid("a10101", "text")
        assertInvalid("1c", "reserved")
    }

    @Test
    fun refusesBrokenInput() {
        assertInvalid("", "truncated")
        assertInvalid("18", "truncated")
        assertInvalid("4401", "exceeds")
        assertInvalid("83010203" + "04", "trailing")
        assertInvalid("0000", "trailing")
        assertInvalid("61ff", "UTF-8")
        assertInvalid("1b8000000000000000", "too large")
        assertInvalid("5bffffffffffffffff", "too large")
        assertInvalid("9b0000000100000000", "exceeds")
    }

    @Test
    fun refusesNestingDeeperThanTheLimit() {
        val atLimit = "81".repeat(CBOR_MAX_DEPTH) + "00"
        val overLimit = "81".repeat(CBOR_MAX_DEPTH + 1) + "00"
        assertIs<Parsed.Ok<*>>(decode(atLimit))
        assertInvalid(overLimit, "deep")
    }
}
