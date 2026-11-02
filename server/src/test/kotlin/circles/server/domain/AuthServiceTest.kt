package circles.server.domain

import circles.api.SessionRequest
import circles.api.SessionResponse
import circles.encoding.ByteString
import circles.event.DeviceIdentity
import circles.event.authMessage
import circles.event.toDto
import circles.server.support.Person
import circles.server.support.TODAY
import circles.server.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServiceTest {
    private val server = TestServer()
    private val crypto = server.crypto
    private val auth = server.services.auth
    private val circle = circles.event.newCircleId(crypto)
    private val alice = Person(crypto)

    init {
        val result = server.services.sync.submit(listOf(alice.author.circleCreate(circle, TODAY).toDto()), Entrance.Create)
        assertEquals("accepted", result.single().status.name.lowercase())
    }

    @AfterTest
    fun close() = server.close()

    private fun request(identity: DeviceIdentity, nonce: String, claimed: DeviceIdentity = identity, signer: DeviceIdentity = identity): SessionRequest {
        val signature = crypto.sign(signer.signing.secretSeed, authMessage(identity.device, ByteString.fromBase64Url(nonce)!!))
        return SessionRequest(claimed.device.v, nonce, signature.toBase64Url())
    }

    private fun granted(result: SessionResult): SessionResponse = assertIs<SessionResult.Granted>(result).session

    @Test
    fun aDeviceCanLogInAndTheTokenIdentifiesIt() {
        val nonce = auth.challenge(alice.identity.device.v).nonce
        val session = granted(auth.session(request(alice.identity, nonce)))
        assertEquals(SESSION_TTL_SECONDS, session.expiresIn)
        val device = assertNotNull(auth.authenticate(session.token))
        assertEquals(alice.identity.device, device.device)
        assertEquals(circle, device.circle)
    }

    @Test
    fun anUnknownDeviceGetsANonceButCannotLogInAndNothingIsStored() {
        val stranger = DeviceIdentity.generate(crypto)
        val nonce = auth.challenge(stranger.device.v).nonce
        assertEquals(0, server.rows("SELECT 1 FROM challenges").size)
        assertEquals(SessionResult.Denied, auth.session(request(stranger, nonce)))
    }

    @Test
    fun challengesForKnownDevicesAreStoredAndGarbageIdsStillGetAnAnswer() {
        auth.challenge(alice.identity.device.v)
        assertEquals(1, server.rows("SELECT 1 FROM challenges").size)
        assertTrue(auth.challenge("not-an-id").nonce.isNotEmpty())
        assertEquals(1, server.rows("SELECT 1 FROM challenges").size)
    }

    @Test
    fun aNonceWorksOnlyOnce() {
        val nonce = auth.challenge(alice.identity.device.v).nonce
        granted(auth.session(request(alice.identity, nonce)))
        assertEquals(SessionResult.Denied, auth.session(request(alice.identity, nonce)))
    }

    @Test
    fun aChallengeExpiresAfterSixtySeconds() {
        val early = auth.challenge(alice.identity.device.v).nonce
        server.clock.advanceSeconds(59)
        granted(auth.session(request(alice.identity, early)))
        val late = auth.challenge(alice.identity.device.v).nonce
        server.clock.advanceSeconds(CHALLENGE_TTL_MILLIS / 1000)
        assertEquals(SessionResult.Denied, auth.session(request(alice.identity, late)))
    }

    @Test
    fun aWrongSignatureIsDeniedAndBurnsTheNonce() {
        val nonce = auth.challenge(alice.identity.device.v).nonce
        val imposter = DeviceIdentity.generate(crypto)
        assertEquals(SessionResult.Denied, auth.session(request(alice.identity, nonce, signer = imposter)))
        assertEquals(SessionResult.Denied, auth.session(request(alice.identity, nonce)))
    }

    @Test
    fun aNonceIssuedForOneDeviceCannotLogInAnother() {
        val bea = Person(crypto)
        val inviteKey = circles.server.support.newInviteKey(crypto)
        val invite = alice.author.invite(circle, inviteKey, TODAY + 5, 1, TODAY)
        server.services.sync.submit(listOf(invite.toDto()), Entrance.Push(circle))
        server.services.sync.submit(listOf(bea.author.join(circle, invite.eventId(crypto), inviteKey, TODAY).toDto()), Entrance.Join(circle))
        val nonceForAlice = auth.challenge(alice.identity.device.v).nonce
        assertEquals(SessionResult.Denied, auth.session(request(bea.identity, nonceForAlice)))
    }

    @Test
    fun malformedFieldsAreDenied() {
        val nonce = auth.challenge(alice.identity.device.v).nonce
        assertEquals(SessionResult.Denied, auth.session(SessionRequest(alice.identity.device.v, "!!", "!!")))
        assertEquals(SessionResult.Denied, auth.session(SessionRequest(alice.identity.device.v, nonce, "!!")))
    }

    @Test
    fun unknownAndGarbageTokensAreNotAuthenticated() {
        assertNull(auth.authenticate(crypto.randomBytes(32).toBase64Url()))
        assertNull(auth.authenticate("!!"))
        assertNull(auth.authenticate(""))
    }

    @Test
    fun aTokenLastsTwelveHours() {
        val token = granted(auth.session(request(alice.identity, auth.challenge(alice.identity.device.v).nonce))).token
        server.clock.advanceSeconds(SESSION_TTL_SECONDS - 1)
        assertNotNull(auth.authenticate(token))
        server.clock.advanceSeconds(1)
        assertNull(auth.authenticate(token))
    }

    @Test
    fun aRevokedDeviceCannotLogInAndItsTokenStopsWorking() {
        val token = granted(auth.session(request(alice.identity, auth.challenge(alice.identity.device.v).nonce))).token
        val nonce = auth.challenge(alice.identity.device.v).nonce
        server.exec("UPDATE devices SET revoked_seq = 9 WHERE device_id = '${alice.identity.device.v}'")
        assertNull(auth.authenticate(token))
        assertEquals(SessionResult.Denied, auth.session(request(alice.identity, nonce)))
    }

    @Test
    fun tokensAreStoredOnlyAsHashes() {
        val token = granted(auth.session(request(alice.identity, auth.challenge(alice.identity.device.v).nonce))).token
        val raw = ByteString.fromBase64Url(token)!!
        val stored = server.rows("SELECT token_hash FROM sessions").single().single() as ByteArray
        assertEquals(32, stored.size)
        assertTrue(!stored.contentEquals(raw.toByteArray()))
        assertEquals(crypto.hash256(raw.toByteArray()), ByteString.of(stored))
    }

    @Test
    fun expiredChallengesAndSessionsArePurgedOnTheNextChallenge() {
        granted(auth.session(request(alice.identity, auth.challenge(alice.identity.device.v).nonce)))
        auth.challenge(alice.identity.device.v)
        assertEquals(1, server.rows("SELECT 1 FROM sessions").size)
        server.clock.advanceSeconds(SESSION_TTL_SECONDS + 1)
        auth.challenge("x")
        assertEquals(0, server.rows("SELECT 1 FROM sessions").size)
        assertEquals(0, server.rows("SELECT 1 FROM challenges").size)
    }
}
