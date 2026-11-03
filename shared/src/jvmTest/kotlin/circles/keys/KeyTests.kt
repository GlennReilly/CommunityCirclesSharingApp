package circles.keys

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.JvmCrypto
import circles.crypto.LibsodiumCipher

class SeedWrappingTest : SeedWrappingContract() {
    override suspend fun newCrypto(): Crypto = JvmCrypto()
    override suspend fun newCipher(): Cipher = LibsodiumCipher()
    override fun newHasher(): PasswordHasher = JvmPasswordHasher()
}

class InMemoryKeyStoreTest : KeyStoreContract() {
    override suspend fun newStore(): KeyStore = InMemoryKeyStore()
}

class KeyVaultTest : KeyVaultContract() {
    override suspend fun newCrypto(): Crypto = JvmCrypto()
    override suspend fun newCipher(): Cipher = LibsodiumCipher()
    override fun newHasher(): PasswordHasher = JvmPasswordHasher()
}

class CircleAppTest : circles.app.CircleAppContract() {
    override suspend fun newCrypto(): Crypto = JvmCrypto()
    override suspend fun newCipher(): Cipher = LibsodiumCipher()
    override fun newHasher(): PasswordHasher = JvmPasswordHasher()
}

class RecoveryCodesTest : RecoveryCodesContract() {
    override suspend fun newCrypto(): Crypto = JvmCrypto()
    override suspend fun newCipher(): Cipher = LibsodiumCipher()
}
