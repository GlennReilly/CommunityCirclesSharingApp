package circles.event

import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.policy.CircleId
import circles.policy.MemberId
import kotlin.jvm.JvmInline

/** Random 128-bit identifiers are 16 bytes, shown as 32 lowercase hex characters (ADR 0013). */
const val RANDOM_ID_BYTES = 16
const val RANDOM_ID_HEX_LENGTH = 32
const val EVENT_ID_HEX_LENGTH = 64

@JvmInline
value class DeviceId(val v: String)

@JvmInline
value class EventId(val v: String)

/** True for [length] lowercase hex characters. */
fun isLowerHex(text: String, length: Int): Boolean = text.length == length && ByteString.fromHex(text) != null

fun isValidRandomId(text: String): Boolean = isLowerHex(text, RANDOM_ID_HEX_LENGTH)

fun newCircleId(crypto: Crypto): CircleId = CircleId(crypto.randomBytes(RANDOM_ID_BYTES).toHex())

fun newMemberId(crypto: Crypto): MemberId = MemberId(crypto.randomBytes(RANDOM_ID_BYTES).toHex())

fun newDeviceId(crypto: Crypto): DeviceId = DeviceId(crypto.randomBytes(RANDOM_ID_BYTES).toHex())

fun newItemId(crypto: Crypto): circles.policy.ItemId = circles.policy.ItemId(crypto.randomBytes(RANDOM_ID_BYTES).toHex())

fun newLoanId(crypto: Crypto): LoanId = LoanId(crypto.randomBytes(RANDOM_ID_BYTES).toHex())

/** Names one version of an item's detail key (ADR 0025). */
fun newDetailKeyId(crypto: Crypto): String = crypto.randomBytes(RANDOM_ID_BYTES).toHex()
