package circles.encoding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ByteStringTest {
    @Test
    fun hexRoundTripIsLowercase() {
        val bytes = ByteString.of(byteArrayOf(0x00, 0x7f, 0x80.toByte(), 0xff.toByte()))
        assertEquals("007f80ff", bytes.toHex())
        assertEquals(bytes, ByteString.fromHex("007f80ff"))
    }

    @Test
    fun hexRefusesUppercaseOddLengthAndNonHex() {
        assertNull(ByteString.fromHex("00AB"))
        assertNull(ByteString.fromHex("abc"))
        assertNull(ByteString.fromHex("zz"))
    }

    @Test
    fun base64UrlHasNoPaddingAndNoPlusOrSlash() {
        val bytes = ByteString.of(byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xfe.toByte(), 0x01))
        val text = bytes.toBase64Url()
        assertEquals("-__-AQ", text)
        assertEquals(bytes, ByteString.fromBase64Url(text))
        assertEquals(ByteString.EMPTY, ByteString.fromBase64Url(""))
    }

    @Test
    fun base64UrlRefusesNonsense() {
        assertNull(ByteString.fromBase64Url("a b"))
        assertNull(ByteString.fromBase64Url("+/+/"))
    }

    @Test
    fun hasContentEqualityAndCopiesItsInput() {
        val source = byteArrayOf(1, 2, 3)
        val a = ByteString.of(source)
        source[0] = 9
        assertEquals(ByteString.of(byteArrayOf(1, 2, 3)), a)
        assertEquals(ByteString.of(byteArrayOf(1, 2, 3)).hashCode(), a.hashCode())
        assertNotEquals(ByteString.of(byteArrayOf(1, 2, 4)), a)
        a.toByteArray()[0] = 9
        assertEquals(1, a[0].toInt())
        assertTrue(ByteString.EMPTY.isEmpty())
    }
}
