package circles.server.domain

import circles.api.PushResult
import circles.api.PushStatus
import circles.encoding.ByteString
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import circles.event.CircleFold
import circles.event.DeviceIdentity
import circles.event.Envelope
import circles.event.EventAuthor
import circles.event.FIRST_EPOCH
import circles.event.InviteKey
import circles.event.ItemKeys
import circles.event.KeyRing
import circles.event.LoanStatus
import circles.event.RejectionCode
import circles.event.ReturnOutcome
import circles.event.newCircleId
import circles.event.newDetailKeyId
import circles.event.newItemId
import circles.event.newLoanId
import circles.event.toDto
import circles.policy.ItemClass
import circles.server.support.TODAY
import circles.server.support.TestServer
import circles.server.support.testCipher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Item and loan events through the real server rules (ADR 0025): the server relays them but cannot judge them. */
class MarketServerTest {
    private val server = TestServer()
    private val crypto = server.crypto
    private val sync = server.services.sync
    private val circle = newCircleId(crypto)
    private val circleKey = KeyRing.newCircleKey(crypto)

    private fun author() = EventAuthor(crypto, DeviceIdentity.generate(crypto, testCipher), testCipher)

    private val owner = author().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey) }
    private val bea = author().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey) }
    private val item = newItemId(crypto)
    private val keyId = newDetailKeyId(crypto)
    private val itemKey = ItemKeys.newKey(crypto)

    @AfterTest
    fun close() = server.close()

    private fun push(e: Envelope): PushResult = sync.submit(listOf(e.toDto()), Entrance.Push(circle)).single()

    private fun accept(e: Envelope) {
        val r = push(e)
        assertEquals(PushStatus.ACCEPTED, r.status, "${e.type}: ${r.reason} ${r.detail}")
    }

    private fun reject(code: RejectionCode, e: Envelope) {
        val r = push(e)
        assertEquals(PushStatus.REJECTED, r.status, "expected ${code.wire}")
        assertEquals(code.wire, r.reason, r.detail)
    }

    private fun resign(e: Envelope, who: EventAuthor = owner) = e.signedBy(crypto, who.identity.signing.secretSeed)

    private fun setup() {
        assertEquals(PushStatus.ACCEPTED, sync.submit(listOf(owner.circleCreate(circle, TODAY).toDto()), Entrance.Create).single().status)
        accept(owner.keyWrap(circle, owner.identity.registration, FIRST_EPOCH, circleKey, TODAY))
        val invite = InviteKey(crypto.generateSigningKeyPair())
        val event = owner.invite(circle, invite, TODAY + 30, 1, TODAY)
        accept(event)
        assertEquals(PushStatus.ACCEPTED, sync.submit(listOf(bea.join(circle, event.eventId(crypto), invite, TODAY).toDto()), Entrance.Join(circle)).single().status)
        accept(owner.keyWrap(circle, bea.identity.registration, FIRST_EPOCH, circleKey, TODAY))
    }

    private fun events(): List<Envelope> = sync.pull(circle, 0, 200).events.map { (it.envelope.toEnvelope() as Parsed.Ok).value }

    @Test
    fun theWholeLoanFlowIsAcceptedAndNothingReadableIsStored() {
        setup()
        accept(owner.itemUpsert(circle, TODAY, item, "Extension ladder", ItemClass.ESSENTIAL, keyId, itemKey, listOf(owner.identity.registration)))
        accept(owner.itemDetail(circle, TODAY, item, keyId, itemKey, CborMap.of("address" to CborText("12 Example St, Elermore Vale"))))
        val loan = newLoanId(crypto)
        accept(bea.loanRequest(circle, TODAY, loan, item, TODAY + 1, TODAY + 3, "for the weekend, thanks"))
        accept(owner.loanApprove(circle, TODAY, loan, item, TODAY + 1, TODAY + 3, itemKey, listOf(bea.identity.registration)))
        accept(owner.loanHandover(circle, TODAY, loan))
        accept(bea.loanHandover(circle, TODAY, loan))
        accept(bea.loanReturn(circle, TODAY, loan, ReturnOutcome.OK))

        val view = CircleFold(crypto, testCipher, bea.identity).fold(circle, events())
        assertEquals(emptyList(), view.rejected)
        assertEquals(LoanStatus.RETURNED, view.market.loans.getValue(loan).status)
        assertEquals("12 Example St, Elermore Vale", ((view.market.details.getValue(item) as Parsed.Ok<CborMap>).value["address"] as CborText).value)

        val dump = dumpEveryTable()
        listOf("Extension ladder", "Elermore", "weekend", itemKey.toHex()).forEach { secret ->
            val needle = if (secret == itemKey.toHex()) secret else ByteString.of(secret.encodeToByteArray()).toHex()
            assertFalse(dump.contains(needle), "'$secret' leaked into the database")
        }
        assertTrue(server.rows("SELECT public FROM events WHERE type LIKE 'item.%' OR type LIKE 'loan.%'").all { (it[0] as ByteArray).size <= 1 })
    }

    private fun dumpEveryTable(): String =
        server.rows("SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] as String }.joinToString("\n") { table ->
            server.rows("SELECT * FROM $table").joinToString("\n") { row ->
                row.joinToString("|") { cell ->
                    when (cell) {
                        is ByteArray -> ByteString.of(cell).toHex()
                        else -> cell.toString().let { text -> text + "|" + ByteString.of(text.encodeToByteArray()).toHex() }
                    }
                }
            }
        }

    @Test
    fun theServerRelaysWhatItCannotJudgeAndTheClientFoldIgnoresIt() {
        setup()
        accept(owner.itemUpsert(circle, TODAY, item, "Extension ladder", ItemClass.ESSENTIAL, keyId, itemKey, listOf(owner.identity.registration)))
        // Bea tries to take over the item. The server cannot read the payload, so it accepts; clients refuse it.
        val grab = bea.itemUpsert(circle, TODAY, item, "Bea's ladder", ItemClass.ESSENTIAL, newDetailKeyId(crypto), itemKey, listOf(bea.identity.registration))
        accept(grab)
        val view = CircleFold(crypto, testCipher, owner.identity).fold(circle, events())
        assertEquals(listOf("only the owner may change an item"), view.rejected.map { it.reason })
        assertEquals("Extension ladder", view.market.items.getValue(item).name)
    }

    @Test
    fun marketEventsMustHaveNoPublicPartAndASealedPayload() {
        setup()
        val real = owner.itemRetire(circle, TODAY, item)
        reject(RejectionCode.INVALID_PUBLIC, resign(real.copy(public = CborMap.of("item" to CborText(item.v)))))
        reject(RejectionCode.INVALID_PAYLOAD, resign(real.copy(nonce = ByteString.EMPTY, encrypted = ByteString.EMPTY, epoch = 0)))
    }

    @Test
    fun anItemRestoreIsAcceptedAsASealedMarketEventAndRefusedWithAPublicPart() {
        setup()
        accept(owner.itemRetire(circle, TODAY, item))
        val restore = owner.itemRestore(circle, TODAY, item)
        reject(RejectionCode.INVALID_PUBLIC, resign(restore.copy(public = CborMap.of("item" to CborText(item.v)))))
        accept(restore)
    }

    @Test
    fun marketEventsNeedAKeyEpochTheCircleHasReachedAndARegisteredDevice() {
        setup()
        val real = owner.itemRetire(circle, TODAY, item)
        reject(RejectionCode.MALFORMED, resign(real.copy(epoch = 5)))
        val stranger = author().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey) }
        reject(RejectionCode.UNKNOWN_DEVICE, stranger.itemRetire(circle, TODAY, item))
    }

    @Test
    fun aRevokedDeviceCannotSendMarketEvents() {
        setup()
        accept(owner.deviceRevoke(circle, bea.identity.device, TODAY))
        reject(RejectionCode.DEVICE_REVOKED, bea.loanRequest(circle, TODAY, newLoanId(crypto), item, TODAY + 1, TODAY + 2))
    }
}
