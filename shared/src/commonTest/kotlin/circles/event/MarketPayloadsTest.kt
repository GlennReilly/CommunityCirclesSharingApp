package circles.event

import circles.encoding.ByteString
import circles.encoding.CborArray
import circles.encoding.CborBool
import circles.encoding.CborBytes
import circles.encoding.CborInt
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import circles.policy.ItemClass
import circles.policy.ItemId
import circles.policy.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The strict payload readers of ADR 0025. */
class MarketPayloadsTest {
    private val item = ItemId("ab".repeat(16))
    private val loan = LoanId("cd".repeat(16))
    private val keyId = "ef".repeat(16)
    private val device = DeviceId("12".repeat(16))
    private val wrap = DeviceKeyWrap(device, ByteString.of(ByteArray(80) { it.toByte() }))

    private fun <T> ok(parsed: Parsed<T>): T = assertIs<Parsed.Ok<T>>(parsed).value

    private fun entries(map: CborMap) = map.entries

    @Test
    fun everyPayloadRoundTripsThroughItsBuilderAndReader() {
        val upsert = ItemUpsert(item, "Ladder", ItemClass.ESSENTIAL, Tier.NEIGHBOUR, keyId, listOf(wrap))
        assertEquals(upsert, ok(MarketPayloads.parseItemUpsert(MarketPayloads.itemUpsert(upsert))))
        assertEquals(upsert.copy(minTier = null), ok(MarketPayloads.parseItemUpsert(MarketPayloads.itemUpsert(upsert.copy(minTier = null)))))
        assertEquals(ItemRetire(item), ok(MarketPayloads.parseItemRetire(MarketPayloads.itemRetire(ItemRetire(item)))))
        val detail = ItemDetail(item, keyId, ByteString.of(ByteArray(40)))
        assertEquals(detail, ok(MarketPayloads.parseItemDetail(MarketPayloads.itemDetail(detail))))
        val request = LoanRequest(loan, item, 100, 107, "for the weekend")
        assertEquals(request, ok(MarketPayloads.parseLoanRequest(MarketPayloads.loanRequest(request))))
        assertEquals(request.copy(note = null), ok(MarketPayloads.parseLoanRequest(MarketPayloads.loanRequest(request.copy(note = null)))))
        val approve = LoanRespond(loan, true, 100, 105, null, listOf(wrap))
        assertEquals(approve, ok(MarketPayloads.parseLoanRespond(MarketPayloads.loanRespond(approve))))
        val decline = LoanRespond(loan, false, 100, 105, "not this week", emptyList())
        assertEquals(decline, ok(MarketPayloads.parseLoanRespond(MarketPayloads.loanRespond(decline))))
        assertEquals(LoanHandover(loan), ok(MarketPayloads.parseLoanHandover(MarketPayloads.loanHandover(LoanHandover(loan)))))
        ReturnOutcome.entries.forEach {
            assertEquals(LoanReturn(loan, it), ok(MarketPayloads.parseLoanReturn(MarketPayloads.loanReturn(LoanReturn(loan, it)))))
        }
    }

    @Test
    fun unknownMissingOrMistypedKeysAreRefused() {
        val good = MarketPayloads.loanRequest(LoanRequest(loan, item, 100, 107, null))
        assertIs<Parsed.Invalid>(MarketPayloads.parseLoanRequest(CborMap(entries(good) + ("extra" to CborInt(1)))))
        assertIs<Parsed.Invalid>(MarketPayloads.parseLoanRequest(CborMap(entries(good) - "toDay")))
        assertIs<Parsed.Invalid>(MarketPayloads.parseLoanRequest(CborMap(entries(good) + ("fromDay" to CborText("100")))))
        assertIs<Parsed.Invalid>(MarketPayloads.parseLoanRequest(CborMap(entries(good) + ("loan" to CborText("NOT-HEX")))))
        assertIs<Parsed.Invalid>(MarketPayloads.parseLoanRequest(CborMap.EMPTY))
        assertIs<Parsed.Invalid>(MarketPayloads.parseLoanReturn(CborMap.of("loan" to CborText(loan.v), "outcome" to CborText("FINE"))))
    }

    @Test
    fun windowsAndTextLimitsAreEnforced() {
        fun request(from: Int, to: Int, note: String? = null) = MarketPayloads.parseLoanRequest(MarketPayloads.loanRequest(LoanRequest(loan, item, from, to, note)))
        assertIs<Parsed.Ok<*>>(request(10, 10))
        assertIs<Parsed.Ok<*>>(request(10, 10 + MAX_LOAN_DAYS))
        assertIs<Parsed.Invalid>(request(10, 11 + MAX_LOAN_DAYS))
        assertIs<Parsed.Invalid>(request(11, 10))
        assertIs<Parsed.Invalid>(request(-1, 5))
        assertIs<Parsed.Ok<*>>(request(1, 2, "x".repeat(MAX_LOAN_NOTE_CHARS)))
        assertIs<Parsed.Invalid>(request(1, 2, "x".repeat(MAX_LOAN_NOTE_CHARS + 1)))
        fun upsert(name: String) = MarketPayloads.parseItemUpsert(MarketPayloads.itemUpsert(ItemUpsert(item, name, ItemClass.EVERYDAY, null, keyId, listOf(wrap))))
        assertIs<Parsed.Ok<*>>(upsert("x".repeat(MAX_ITEM_NAME_CHARS)))
        assertIs<Parsed.Invalid>(upsert("x".repeat(MAX_ITEM_NAME_CHARS + 1)))
        assertIs<Parsed.Invalid>(upsert("   "))
    }

    @Test
    fun anOwnerMayRaiseButNeverLowerTheMinimumTier() {
        fun upsert(itemClass: ItemClass, tier: Tier?) = MarketPayloads.parseItemUpsert(MarketPayloads.itemUpsert(ItemUpsert(item, "Drill", itemClass, tier, keyId, listOf(wrap))))
        assertIs<Parsed.Ok<*>>(upsert(ItemClass.VALUABLE, Tier.TRUSTED))
        assertIs<Parsed.Ok<*>>(upsert(ItemClass.VALUABLE, Tier.NEIGHBOUR))
        assertIs<Parsed.Invalid>(upsert(ItemClass.VALUABLE, Tier.NEWCOMER))
        assertIs<Parsed.Invalid>(upsert(ItemClass.HIGH_VALUE, Tier.NEIGHBOUR))
    }

    @Test
    fun approvalsNeedWrapsAndDeclinesMustNotHaveThem() {
        val approveWithout = MarketPayloads.loanRespond(LoanRespond(loan, true, 1, 2, null, emptyList()))
        assertIs<Parsed.Invalid>(MarketPayloads.parseLoanRespond(approveWithout))
        val declineWith = MarketPayloads.loanRespond(LoanRespond(loan, false, 1, 2, null, listOf(wrap)))
        assertIs<Parsed.Invalid>(MarketPayloads.parseLoanRespond(declineWith))
    }

    @Test
    fun keyWrapListsAreBoundedDistinctAndWellFormed() {
        fun upsert(wraps: List<DeviceKeyWrap>) = MarketPayloads.parseItemUpsert(MarketPayloads.itemUpsert(ItemUpsert(item, "Tent", ItemClass.EVERYDAY, null, keyId, wraps)))
        val many = List(MAX_KEY_WRAPS) { DeviceKeyWrap(DeviceId(it.toString(16).padStart(32, '0')), wrap.wrapped) }
        assertIs<Parsed.Ok<*>>(upsert(many))
        assertIs<Parsed.Invalid>(upsert(many + DeviceKeyWrap(DeviceId("ff".repeat(16)), wrap.wrapped)))
        assertIs<Parsed.Invalid>(upsert(listOf(wrap, wrap)))
        assertIs<Parsed.Invalid>(upsert(emptyList()))
        val odd = MarketPayloads.itemUpsert(ItemUpsert(item, "Tent", ItemClass.EVERYDAY, null, keyId, listOf(wrap)))
        val badEntry = CborMap(entries(odd) + ("keyWraps" to CborArray(listOf(CborMap.of("device" to CborText(device.v), "wrapped" to CborBytes(ByteString.EMPTY))))))
        assertIs<Parsed.Invalid>(MarketPayloads.parseItemUpsert(badEntry))
        val notAMap = CborMap(entries(odd) + ("keyWraps" to CborArray(listOf(CborBool(true)))))
        assertIs<Parsed.Invalid>(MarketPayloads.parseItemUpsert(notAMap))
    }

    @Test
    fun aVouchRoundTripsAndTheReaderIsStrict() {
        val vouchee = circles.policy.MemberId("34".repeat(16))
        val terms = VouchTerms(vouchee, 20_500)
        assertEquals(terms, ok(MarketPayloads.parseVouch(MarketPayloads.vouch(terms))))
        val good = MarketPayloads.vouch(terms).entries
        assertIs<Parsed.Invalid>(MarketPayloads.parseVouch(CborMap(good + ("extra" to CborInt(1)))))
        assertIs<Parsed.Invalid>(MarketPayloads.parseVouch(CborMap(good - "expiresDay")))
        assertIs<Parsed.Invalid>(MarketPayloads.parseVouch(CborMap(good + ("vouchee" to CborText("not-an-id")))))
        assertIs<Parsed.Invalid>(MarketPayloads.parseVouch(CborMap(good + ("expiresDay" to CborInt(-1)))))
        assertIs<Parsed.Invalid>(MarketPayloads.parseVouch(CborMap(good + ("expiresDay" to CborText("soon")))))
        assertIs<Parsed.Invalid>(MarketPayloads.parseVouch(CborMap(good + ("expiresDay" to CborInt(5_000_000)))))
    }
}
