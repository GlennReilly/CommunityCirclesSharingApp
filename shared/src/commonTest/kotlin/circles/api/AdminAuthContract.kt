package circles.api

import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.event.DeviceId
import circles.event.newDeviceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The signed admin request of ADR 0033, on every Crypto implementation. */
abstract class AdminAuthContract {
    protected abstract suspend fun newCrypto(): Crypto

    private val path = "/api/v1/admin/import"
    private val body = "the zip bytes".encodeToByteArray()

    private class Signer(val crypto: Crypto) {
        val pair = crypto.generateSigningKeyPair()
        val device: DeviceId = newDeviceId(crypto)

        fun header(method: String = "POST", path: String, ts: Long = 1_800_000_000, body: ByteArray = ByteArray(0)) =
            AdminAuth.sign(crypto, device, pair.secretSeed, method, path, ts, body)
    }

    @Test
    fun aSignedRequestParsesAndVerifies() = runTest {
        val s = Signer(newCrypto())
        val credentials = assertNotNull(AdminAuth.parse(s.header(path = path, body = body)))
        assertEquals(s.device, credentials.device)
        assertEquals(1_800_000_000L, credentials.ts)
        assertTrue(AdminAuth.verify(s.crypto, credentials, "POST", path, body, s.pair.publicKey))
    }

    @Test
    fun theSignatureCoversTheMethodPathBodyTimeAndKey() = runTest {
        val s = Signer(newCrypto())
        val header = s.header(path = path, body = body)
        val c = assertNotNull(AdminAuth.parse(header))
        assertFalse(AdminAuth.verify(s.crypto, c, "GET", path, body, s.pair.publicKey), "another method")
        assertFalse(AdminAuth.verify(s.crypto, c, "POST", "/api/v1/admin/export/x", body, s.pair.publicKey), "another path")
        assertFalse(AdminAuth.verify(s.crypto, c, "POST", path, body + 1, s.pair.publicKey), "another body")
        assertFalse(AdminAuth.verify(s.crypto, c.copy(ts = c.ts + 1), "POST", path, body, s.pair.publicKey), "another time")
        assertFalse(AdminAuth.verify(s.crypto, c.copy(nonce = ByteString.of(ByteArray(16)).toBase64Url()), "POST", path, body, s.pair.publicKey), "another nonce")
        assertFalse(AdminAuth.verify(s.crypto, c, "POST", path, body, Signer(s.crypto).pair.publicKey), "another key")
    }

    @Test
    fun everyRequestHasADifferentNonce() = runTest {
        val s = Signer(newCrypto())
        assertNotEquals(AdminAuth.parse(s.header(path = path))!!.nonce, AdminAuth.parse(s.header(path = path))!!.nonce)
    }

    @Test
    fun aGetHasAnEmptyBodyHash() = runTest {
        val s = Signer(newCrypto())
        val c = AdminAuth.parse(s.header("GET", "/api/v1/admin/export/abc"))!!
        assertTrue(AdminAuth.verify(s.crypto, c, "GET", "/api/v1/admin/export/abc", ByteArray(0), s.pair.publicKey))
    }

    @Test
    fun theParserIsStrict() = runTest {
        val s = Signer(newCrypto())
        val good = s.header(path = path)
        assertNotNull(AdminAuth.parse(good))
        assertNull(AdminAuth.parse(null))
        assertNull(AdminAuth.parse(""))
        assertNull(AdminAuth.parse("Bearer abc"))
        assertNull(AdminAuth.parse(good.replace("Circles-Signature", "Circles-Other")))
        assertNull(AdminAuth.parse(good.substringBeforeLast(", sig="))) // field missing
        assertNull(AdminAuth.parse("$good, extra=1")) // field added
        assertNull(AdminAuth.parse(good.replace("device=", "devic=")))
        assertNull(AdminAuth.parse(good.replace(Regex("device=[0-9a-f]+"), "device=NOT-HEX")))
        assertNull(AdminAuth.parse(good.replace(Regex("ts=\\d+"), "ts=-5")))
        assertNull(AdminAuth.parse(good.replace(Regex("ts=\\d+"), "ts=abc")))
        assertNull(AdminAuth.parse(good.replace(Regex("ts=\\d+"), "ts=99999999999999999999")))
        assertNull(AdminAuth.parse(good.replace(Regex("nonce=[^,]+"), "nonce=short")))
        assertNull(AdminAuth.parse(good.replace(Regex("sig=.+$"), "sig=tooShort")))
        assertNull(AdminAuth.parse(good.replace(", ", ",") + ",")) // trailing junk
    }

    @Test
    fun theMessageIsDomainSeparatedAndLinesAreFixed() = runTest {
        val text = AdminAuth.message("POST", "/p", 5, "N", "H").decodeToString()
        assertEquals("circles/admin/v1\nPOST\n/p\n5\nN\nH", text)
    }
}
