package circles.sync

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.JvmCrypto
import circles.crypto.LibsodiumCipher

class SyncEngineTest : SyncEngineContract() {
    override suspend fun newCrypto(): Crypto = JvmCrypto()
    override suspend fun newCipher(): Cipher = LibsodiumCipher()
}

class InMemoryReplicaTest : ReplicaContract() {
    override fun newCrypto(): Crypto = JvmCrypto()
    override fun newCipher(): Cipher = LibsodiumCipher()
    override suspend fun newReplica(crypto: Crypto): Replica = InMemoryReplica { it.eventId(crypto) }
}

class HttpSyncApiTest : HttpSyncApiContract() {
    override suspend fun newCrypto(): Crypto = JvmCrypto()
    override suspend fun newCipher(): Cipher = LibsodiumCipher()
}
