package circles.keys

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.event.DeviceId
import circles.event.DeviceIdentity
import circles.policy.MemberId

const val RECOVERY_WORDS = 12
const val RECOVERY_ENTROPY_BYTES = 16
const val RECOVERY_WORDLIST_SIZE = 2048
private const val BITS_PER_WORD = 11
private const val CHECKSUM_BITS = 4
private const val DOMAIN_CHECKSUM = "circles/recovery-code/v1"
private const val DOMAIN_SEED = "circles/recovery-seed/v1"
private const val DOMAIN_DEVICE = "circles/recovery-device-id/v1"
private const val BYTE_BITS = 8
private const val DEVICE_ID_BYTES = 16

/** What reading a typed recovery code came to. A wrong word is reported by its place, so it can be found on the paper. */
sealed interface RecoveryParse {
    class Valid(val entropy: ByteString) : RecoveryParse

    data class WrongCount(val count: Int) : RecoveryParse

    data class UnknownWord(val position: Int, val word: String) : RecoveryParse

    /** Every word is on the list but together they are not a code: a word is wrong or two are swapped. */
    data object BadChecksum : RecoveryParse
}

/**
 * The recovery code (ADR 0047): 128 random bits written as 12 words of the BIP-39 English list, with a 4-bit checksum of our own
 * (so the words are never a valid wallet phrase). The device seed and the device id are derived from the same 128 bits, so a
 * browser holding only the words can become that device again.
 */
object RecoveryCodes {
    fun newEntropy(crypto: Crypto): ByteString = crypto.randomBytes(RECOVERY_ENTROPY_BYTES)

    private fun hash(crypto: Crypto, domain: String, entropy: ByteString): ByteString = crypto.hash256(domain.encodeToByteArray() + entropy.toByteArray())

    private fun checksum(crypto: Crypto, entropy: ByteString): Int = (hash(crypto, DOMAIN_CHECKSUM, entropy)[0].toInt() and 0xFF) shr (BYTE_BITS - CHECKSUM_BITS)

    /** The device seed (ADR 0039) for [entropy]. */
    fun seedOf(crypto: Crypto, entropy: ByteString): ByteString = hash(crypto, DOMAIN_SEED, entropy)

    /** The device id for [entropy]: derived, so it can be found again from the words. */
    fun deviceIdOf(crypto: Crypto, entropy: ByteString): DeviceId = DeviceId(hash(crypto, DOMAIN_DEVICE, entropy).toByteArray().copyOf(DEVICE_ID_BYTES).let { ByteString.of(it).toHex() })

    /** The identity of the recovery device for [entropy], as a device of [member]. */
    fun identityOf(crypto: Crypto, cipher: Cipher, entropy: ByteString, member: MemberId): DeviceIdentity =
        DeviceIdentity.fromSeed(crypto, cipher, seedOf(crypto, entropy), member, deviceIdOf(crypto, entropy))

    /** The 12 words for [entropy], from a list of exactly [RECOVERY_WORDLIST_SIZE] words. */
    fun wordsOf(crypto: Crypto, wordlist: List<String>, entropy: ByteString): List<String> {
        require(wordlist.size == RECOVERY_WORDLIST_SIZE) { "the word list must have $RECOVERY_WORDLIST_SIZE words" }
        require(entropy.size == RECOVERY_ENTROPY_BYTES) { "the entropy must be $RECOVERY_ENTROPY_BYTES bytes" }
        val bits = bitsOf(entropy, checksum(crypto, entropy))
        return List(RECOVERY_WORDS) { word -> wordlist[(0 until BITS_PER_WORD).fold(0) { acc, bit -> acc * 2 + bits[word * BITS_PER_WORD + bit] }] }
    }

    /** Reads typed words (any case, any spacing). */
    fun parse(crypto: Crypto, wordlist: List<String>, text: String): RecoveryParse {
        require(wordlist.size == RECOVERY_WORDLIST_SIZE) { "the word list must have $RECOVERY_WORDLIST_SIZE words" }
        val typed = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (typed.size != RECOVERY_WORDS) return RecoveryParse.WrongCount(typed.size)
        val index = wordlist.withIndex().associate { it.value to it.index }
        val bits = IntArray(RECOVERY_WORDS * BITS_PER_WORD)
        typed.forEachIndexed { place, word ->
            val value = index[word] ?: return RecoveryParse.UnknownWord(place + 1, word)
            for (bit in 0 until BITS_PER_WORD) bits[place * BITS_PER_WORD + bit] = (value shr (BITS_PER_WORD - 1 - bit)) and 1
        }
        val entropy = ByteString.of(ByteArray(RECOVERY_ENTROPY_BYTES) { byte -> (0 until BYTE_BITS).fold(0) { acc, bit -> acc * 2 + bits[byte * BYTE_BITS + bit] }.toByte() })
        val given = (0 until CHECKSUM_BITS).fold(0) { acc, bit -> acc * 2 + bits[RECOVERY_ENTROPY_BYTES * BYTE_BITS + bit] }
        return if (given == checksum(crypto, entropy)) RecoveryParse.Valid(entropy) else RecoveryParse.BadChecksum
    }

    /** The 132 bits (entropy, then checksum), most significant first. */
    private fun bitsOf(entropy: ByteString, checksum: Int): IntArray {
        val bits = IntArray(RECOVERY_WORDS * BITS_PER_WORD)
        for (index in 0 until RECOVERY_ENTROPY_BYTES * BYTE_BITS) bits[index] = (entropy[index / BYTE_BITS].toInt() shr (BYTE_BITS - 1 - index % BYTE_BITS)) and 1
        for (bit in 0 until CHECKSUM_BITS) bits[RECOVERY_ENTROPY_BYTES * BYTE_BITS + bit] = (checksum shr (CHECKSUM_BITS - 1 - bit)) and 1
        return bits
    }
}
