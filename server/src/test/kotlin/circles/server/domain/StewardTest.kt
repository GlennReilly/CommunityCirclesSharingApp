package circles.server.domain

import circles.api.PushResult
import circles.api.PushStatus
import circles.crypto.LibsodiumCipher
import circles.event.DeviceIdentity
import circles.event.Envelope
import circles.event.EventAuthor
import circles.event.FIRST_EPOCH
import circles.event.InviteKey
import circles.event.KeyRing
import circles.event.RejectionCode
import circles.event.StewardSettings
import circles.event.newCircleId
import circles.event.toDto
import circles.server.support.TODAY
import circles.server.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `steward.add` and `steward.remove` under the quorum of ADR 0023. */
class StewardTest {
    private val server = TestServer()
    private val crypto = server.crypto
    private val cipher = LibsodiumCipher()
    private val sync = server.services.sync
    private val circle = newCircleId(crypto)
    private val key = KeyRing.newCircleKey(crypto)

    private fun author() = EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher = cipher), cipher = cipher)

    private val founder = author().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, key) }
    private val bea = author()
    private val carol = author()
    private val dave = author()

    @AfterTest
    fun close() = server.close()

    private fun push(e: Envelope): PushResult = sync.submit(listOf(e.toDto()), Entrance.Push(circle)).single()

    private fun accept(e: Envelope) {
        val r = push(e)
        assertEquals(PushStatus.ACCEPTED, r.status, "${e.type}: ${r.reason} ${r.detail}")
    }

    private fun reject(code: RejectionCode, e: Envelope, detail: String? = null) {
        val r = push(e)
        assertEquals(PushStatus.REJECTED, r.status, "expected ${code.wire}")
        assertEquals(code.wire, r.reason, r.detail)
        if (detail != null) assertTrue(r.detail!!.contains(detail), r.detail)
    }

    private fun waitDays(days: Int) = server.clock.advanceSeconds(days * SECONDS_PER_DAY)

    private fun join(who: EventAuthor) {
        val invite = InviteKey(crypto.generateSigningKeyPair())
        val event = founder.invite(circle, invite, TODAY + 60, 1, TODAY)
        accept(event)
        val r = sync.submit(listOf(who.join(circle, event.eventId(crypto), invite, TODAY).toDto()), Entrance.Join(circle)).single()
        assertEquals(PushStatus.ACCEPTED, r.status, r.detail)
    }

    private fun setup(settings: StewardSettings? = null) {
        assertEquals(PushStatus.ACCEPTED, sync.submit(listOf(founder.circleCreate(circle, TODAY, settings).toDto()), Entrance.Create).single().status)
        accept(founder.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, key, TODAY))
        listOf(bea, carol, dave).forEach { join(it) }
    }

    private fun stewards(): Set<String> = server.rows("SELECT member_id FROM stewards WHERE removed_seq IS NULL").map { it[0] as String }.toSet()

    private fun ids(vararg who: EventAuthor): Set<String> = who.map { it.identity.member.v }.toSet()

    /** The founder adds bea alone (a lone steward has a quorum of one), then a week passes so bea can vote. */
    private fun twoStewards() {
        setup()
        accept(founder.stewardAdd(circle, bea.identity.member, TODAY))
        assertEquals(ids(founder, bea), stewards())
        waitDays(COOL_OFF)
    }

    @Test
    fun aStewardCannotRevokeAnotherStewardsDevicesButCanOnceTheyHaveSteppedDown() {
        twoStewards()
        reject(RejectionCode.STEWARD_PROTECTED, founder.deviceRevoke(circle, bea.identity.device, TODAY + COOL_OFF), "only be revoked by that steward")
        // Stepping down is always allowed while another steward remains; after that the ordinary rule applies (ADR 0044).
        accept(bea.stewardRemove(circle, bea.identity.member, TODAY + COOL_OFF))
        assertEquals(ids(founder), stewards())
        accept(founder.deviceRevoke(circle, bea.identity.device, TODAY + COOL_OFF))
    }

    @Test
    fun aLoneStewardAddsASecondAloneThenChangesNeedBothAndTheNewOneWaitsAWeek() {
        setup()
        accept(founder.stewardAdd(circle, bea.identity.member, TODAY, "second pair of hands"))
        assertEquals(ids(founder, bea), stewards())
        accept(founder.stewardAdd(circle, carol.identity.member, TODAY)) // pending: one of two
        assertEquals(ids(founder, bea), stewards())
        reject(RejectionCode.INVALID_PUBLIC, bea.stewardAdd(circle, carol.identity.member, TODAY), "cannot vote until")
        waitDays(COOL_OFF)
        accept(bea.stewardAdd(circle, carol.identity.member, TODAY + COOL_OFF))
        assertEquals(ids(founder, bea, carol), stewards())
        assertEquals(0, server.rows("SELECT 1 FROM steward_votes").size, "votes are cleared once the change takes effect")
    }

    @Test
    fun aStewardsSecondDeviceOrRepeatedVoteIsStillOneVote() {
        twoStewards()
        val phone = author().let { EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher, founder.identity.member), cipher = cipher) }
        accept(founder.deviceAdd(circle, phone.identity, TODAY + COOL_OFF))
        accept(founder.stewardAdd(circle, carol.identity.member, TODAY + COOL_OFF))
        accept(phone.stewardAdd(circle, carol.identity.member, TODAY + COOL_OFF))
        accept(founder.stewardAdd(circle, carol.identity.member, TODAY + COOL_OFF))
        assertEquals(ids(founder, bea), stewards())
        accept(bea.stewardAdd(circle, carol.identity.member, TODAY + COOL_OFF))
        assertEquals(ids(founder, bea, carol), stewards())
    }

    @Test
    fun aProposalExpiresAfterTheWindow() {
        twoStewards()
        accept(founder.stewardAdd(circle, carol.identity.member, TODAY + COOL_OFF))
        waitDays(PROPOSAL_DAYS + 1)
        accept(bea.stewardAdd(circle, carol.identity.member, TODAY + COOL_OFF + PROPOSAL_DAYS + 1))
        assertEquals(ids(founder, bea), stewards(), "the old proposal is too old to count")
        accept(founder.stewardAdd(circle, carol.identity.member, TODAY + COOL_OFF + PROPOSAL_DAYS + 1))
        assertEquals(ids(founder, bea, carol), stewards())
    }

    @Test
    fun onlyAStewardMayVoteAndTheTargetMustBeANewMemberOfTheCircle() {
        twoStewards()
        reject(RejectionCode.NOT_STEWARD, carol.stewardAdd(circle, carol.identity.member, TODAY + COOL_OFF))
        reject(RejectionCode.NOT_STEWARD, carol.stewardRemove(circle, founder.identity.member, TODAY + COOL_OFF))
        reject(RejectionCode.INVALID_PUBLIC, founder.stewardAdd(circle, author().identity.member, TODAY + COOL_OFF), "not in this circle")
        reject(RejectionCode.INVALID_PUBLIC, founder.stewardAdd(circle, bea.identity.member, TODAY + COOL_OFF), "already a steward")
        reject(RejectionCode.INVALID_PUBLIC, founder.stewardRemove(circle, carol.identity.member, TODAY + COOL_OFF), "not a steward")
    }

    @Test
    fun aStewardMayLeaveAloneAndTheLastStewardStays() {
        setup()
        reject(RejectionCode.INVALID_PUBLIC, founder.stewardRemove(circle, founder.identity.member, TODAY), "at least one steward")
        accept(founder.stewardAdd(circle, bea.identity.member, TODAY))
        accept(founder.stewardRemove(circle, founder.identity.member, TODAY))
        assertEquals(ids(bea), stewards())
        reject(RejectionCode.NOT_STEWARD, founder.stewardAdd(circle, carol.identity.member, TODAY))
        reject(RejectionCode.INVALID_PUBLIC, bea.stewardRemove(circle, bea.identity.member, TODAY), "at least one steward")
    }

    @Test
    fun inATwoStewardCircleARemovalIsRecordedButNeverGathersItsVotes() {
        twoStewards()
        accept(founder.stewardRemove(circle, bea.identity.member, TODAY + COOL_OFF))
        accept(bea.stewardRemove(circle, founder.identity.member, TODAY + COOL_OFF))
        assertEquals(ids(founder, bea), stewards(), "the target's own vote does not count and the other alone is below the quorum")
        assertEquals(2, server.rows("SELECT 1 FROM steward_votes WHERE kind = 'REMOVE'").size)
    }

    @Test
    fun removingAnotherStewardNeedsTwoOthers() {
        twoStewards()
        val day = TODAY + COOL_OFF
        accept(founder.stewardAdd(circle, carol.identity.member, day))
        accept(bea.stewardAdd(circle, carol.identity.member, day))
        assertEquals(ids(founder, bea, carol), stewards())
        accept(founder.stewardRemove(circle, carol.identity.member, day)) // one of two others
        assertEquals(ids(founder, bea, carol), stewards())
        accept(bea.stewardRemove(circle, carol.identity.member, day))
        assertEquals(ids(founder, bea), stewards())
    }

    @Test
    fun aRemovedStewardCoolsOffAgainWhenAddedBackAndRecoverySharesFollowTheRole() {
        twoStewards()
        val day = TODAY + COOL_OFF
        val devices = listOf(founder.identity.registration, bea.identity.registration)
        checkNotNull(founder.recoveryShares(circle, FIRST_EPOCH, devices, 2, day)).forEach { accept(it) }
        accept(bea.stewardRemove(circle, bea.identity.member, day)) // bea leaves
        val shares = checkNotNull(founder.recoveryShares(circle, FIRST_EPOCH, devices, 2, day))
        accept(shares[0])
        reject(RejectionCode.NOT_STEWARD, shares[1])
        accept(founder.stewardAdd(circle, bea.identity.member, day)) // lone steward again, so alone
        accept(shares[1])
        reject(RejectionCode.INVALID_PUBLIC, bea.stewardRemove(circle, founder.identity.member, day), "cannot vote until")
        assertEquals(ids(founder, bea), stewards())
    }

    private fun settingsInDb(): List<Int> =
        server.rows("SELECT steward_quorum, steward_inactive_days, steward_fallback_delay_days FROM circles").single().map { (it as Number).toInt() }

    @Test
    fun aCircleKeepsTheSettingsItWasCreatedWith() {
        setup(StewardSettings(quorum = 1, inactiveDays = 45, fallbackDelayDays = 10))
        assertEquals(listOf(1, 45, 10), settingsInDb())
    }

    @Test
    fun aCircleCreatedWithoutSettingsGetsTheDefaults() {
        setup()
        assertEquals(listOf(2, 60, 14), settingsInDb())
    }

    @Test
    fun aCircleCannotBeCreatedWithSettingsOutsideTheLimits() {
        listOf(
            StewardSettings(quorum = 0),
            StewardSettings(quorum = 10),
            StewardSettings(inactiveDays = 6),
            StewardSettings(fallbackDelayDays = 6),
            StewardSettings(inactiveDays = 366),
            StewardSettings(fallbackDelayDays = 91),
        ).forEach { bad ->
            val r = sync.submit(listOf(founder.circleCreate(newCircleId(crypto), TODAY, bad).toDto()), Entrance.Create).single()
            assertEquals(PushStatus.REJECTED, r.status, "$bad")
            assertEquals(RejectionCode.INVALID_PUBLIC.wire, r.reason, r.detail)
        }
        assertEquals(0, server.rows("SELECT 1 FROM circles").size)
    }

    @Test
    fun withAQuorumOfOneStewardsActAloneAtOnce() {
        setup(StewardSettings(quorum = 1))
        accept(founder.stewardAdd(circle, bea.identity.member, TODAY))
        accept(bea.stewardAdd(circle, carol.identity.member, TODAY)) // bea is new and acts today
        assertEquals(ids(founder, bea, carol), stewards())
        accept(carol.stewardRemove(circle, bea.identity.member, TODAY)) // and a steward can remove another alone
        assertEquals(ids(founder, carol), stewards())
        accept(founder.stewardRemove(circle, founder.identity.member, TODAY))
        reject(RejectionCode.INVALID_PUBLIC, carol.stewardRemove(circle, carol.identity.member, TODAY), "at least one steward")
    }

    @Test
    fun theStewardsChangeTheSettingsTogether() {
        twoStewards()
        val day = TODAY + COOL_OFF
        val relaxed = StewardSettings(quorum = 1, inactiveDays = 45, fallbackDelayDays = 10)
        accept(founder.stewardPolicy(circle, relaxed, day, "we are two close friends"))
        assertEquals(listOf(2, 60, 14), settingsInDb(), "one vote of two is not enough")
        accept(bea.stewardPolicy(circle, relaxed, day))
        assertEquals(listOf(1, 45, 10), settingsInDb())
        assertEquals(0, server.rows("SELECT 1 FROM steward_votes").size)
        accept(founder.stewardAdd(circle, carol.identity.member, day)) // quorum 1 now: alone
        assertEquals(ids(founder, bea, carol), stewards())
    }

    @Test
    fun aSettingsChangeMustBeValidDifferentAndFromASteward() {
        twoStewards()
        val day = TODAY + COOL_OFF
        reject(RejectionCode.INVALID_PUBLIC, founder.stewardPolicy(circle, StewardSettings(inactiveDays = 5), day), "inactiveDays")
        reject(RejectionCode.INVALID_PUBLIC, founder.stewardPolicy(circle, StewardSettings(), day), "already")
        reject(RejectionCode.NOT_STEWARD, carol.stewardPolicy(circle, StewardSettings(quorum = 3), day))
    }

    @Test
    fun differentProposedSettingsDoNotCountAsTheSameVote() {
        twoStewards()
        val day = TODAY + COOL_OFF
        accept(founder.stewardPolicy(circle, StewardSettings(quorum = 3), day))
        accept(bea.stewardPolicy(circle, StewardSettings(quorum = 1), day))
        assertEquals(listOf(2, 60, 14), settingsInDb())
        accept(bea.stewardPolicy(circle, StewardSettings(quorum = 3), day))
        assertEquals(listOf(3, 60, 14), settingsInDb())
    }

    // ---- the inactivity fallback (ADR 0027) ----

    @Test
    fun aLoneActiveStewardCanAddOnceTheQuietHelperHasBeenSilentLongEnoughAndTheDelayPassed() {
        setup()
        accept(founder.stewardAdd(circle, bea.identity.member, TODAY)) // a lone steward adds alone; bea's last event is her join
        waitDays(QUIET)
        val start = TODAY + QUIET
        accept(founder.stewardAdd(circle, carol.identity.member, start)) // proposal: pending
        assertEquals(ids(founder, bea), stewards())
        waitDays(DELAY - 1)
        accept(founder.stewardAdd(circle, carol.identity.member, start + DELAY - 1))
        assertEquals(ids(founder, bea), stewards(), "one day short of the delay")
        waitDays(1)
        accept(founder.stewardAdd(circle, carol.identity.member, start + DELAY))
        assertEquals(ids(founder, bea, carol), stewards())
    }

    @Test
    fun theFallbackIsRefusedIfTheHelperWasActiveRecentlyBeforeTheProposal() {
        setup()
        accept(founder.stewardAdd(circle, bea.identity.member, TODAY))
        waitDays(QUIET - 5)
        accept(bea.invite(circle, newInvite(), TODAY + QUIET + 20, 1, TODAY + QUIET - 5)) // bea speaks 5 days before the 60 are up
        waitDays(5)
        val start = TODAY + QUIET
        accept(founder.stewardAdd(circle, carol.identity.member, start))
        waitDays(DELAY)
        accept(founder.stewardAdd(circle, carol.identity.member, start + DELAY))
        assertEquals(ids(founder, bea), stewards())
    }

    @Test
    fun anyEventFromTheHelperAfterTheProposalCancelsTheFallback() {
        setup()
        accept(founder.stewardAdd(circle, bea.identity.member, TODAY))
        waitDays(QUIET)
        val start = TODAY + QUIET
        accept(founder.stewardRemove(circle, bea.identity.member, start))
        waitDays(5)
        accept(bea.invite(circle, newInvite(), start + 30, 1, start + 5)) // the helper is back
        waitDays(DELAY)
        accept(founder.stewardRemove(circle, bea.identity.member, start + 5 + DELAY))
        assertEquals(ids(founder, bea), stewards(), "the helper's event vetoed it")
    }

    @Test
    fun theFallbackLetsAnActiveStewardRemoveAQuietHelper() {
        setup()
        accept(founder.stewardAdd(circle, bea.identity.member, TODAY))
        waitDays(QUIET)
        val start = TODAY + QUIET
        accept(founder.stewardRemove(circle, bea.identity.member, start))
        waitDays(DELAY)
        accept(founder.stewardRemove(circle, bea.identity.member, start + DELAY))
        assertEquals(ids(founder), stewards())
    }

    @Test
    fun theFallbackNeverChangesTheSettingsAndShortTimesUseTheCirclesOwnSetting() {
        setup(StewardSettings(quorum = 2, inactiveDays = 30, fallbackDelayDays = 7))
        accept(founder.stewardAdd(circle, bea.identity.member, TODAY))
        waitDays(30)
        val start = TODAY + 30
        val proposal = StewardSettings(quorum = 3)
        accept(founder.stewardPolicy(circle, proposal, start))
        waitDays(7)
        accept(founder.stewardPolicy(circle, proposal, start + 7))
        assertEquals(listOf(2, 30, 7), settingsInDb(), "a settings change cannot use the fallback")
        accept(founder.stewardAdd(circle, carol.identity.member, start + 7))
        waitDays(7)
        accept(founder.stewardAdd(circle, carol.identity.member, start + 14))
        assertEquals(ids(founder, bea, carol), stewards(), "the circle's own shorter times applied")
    }

    private fun newInvite() = InviteKey(crypto.generateSigningKeyPair())

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
        const val COOL_OFF = 7
        const val PROPOSAL_DAYS = 14
        const val QUIET = 60
        const val DELAY = 14
    }
}