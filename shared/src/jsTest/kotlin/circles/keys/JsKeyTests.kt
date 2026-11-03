package circles.keys

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.JsCipher
import circles.crypto.JsCrypto

class JsSeedWrappingTest : SeedWrappingContract() {
    override suspend fun newCrypto(): Crypto = JsCrypto()
    override suspend fun newCipher(): Cipher = JsCipher()
    override fun newHasher(): PasswordHasher = JsPasswordHasher()
}

class JsInMemoryKeyStoreTest : KeyStoreContract() {
    override suspend fun newStore(): KeyStore = InMemoryKeyStore()
}

class JsKeyVaultTest : KeyVaultContract() {
    override suspend fun newCrypto(): Crypto = JsCrypto()
    override suspend fun newCipher(): Cipher = JsCipher()
    override fun newHasher(): PasswordHasher = JsPasswordHasher()
}

class JsCircleAppTest : circles.app.CircleAppContract() {
    override suspend fun newCrypto(): Crypto = JsCrypto()
    override suspend fun newCipher(): Cipher = JsCipher()
    override fun newHasher(): PasswordHasher = JsPasswordHasher()
}

class JsRecoveryCodesTest : RecoveryCodesContract() {
    override suspend fun newCrypto(): Crypto = JsCrypto()
    override suspend fun newCipher(): Cipher = JsCipher()
}
