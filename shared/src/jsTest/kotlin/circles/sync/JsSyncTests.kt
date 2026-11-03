package circles.sync

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.JsCipher
import circles.crypto.JsCrypto

class JsSyncEngineTest : SyncEngineContract() {
    override suspend fun newCrypto(): Crypto = JsCrypto()
    override suspend fun newCipher(): Cipher = JsCipher()
}

class JsInMemoryReplicaTest : ReplicaContract() {
    override fun newCrypto(): Crypto = JsCrypto()
    override fun newCipher(): Cipher = JsCipher()
    override suspend fun newReplica(crypto: Crypto): Replica = InMemoryReplica { it.eventId(crypto) }
}

class JsHttpSyncApiTest : HttpSyncApiContract() {
    override suspend fun newCrypto(): Crypto = JsCrypto()
    override suspend fun newCipher(): Cipher = JsCipher()
}
