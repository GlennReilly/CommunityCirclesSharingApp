# Fixtures for the JVM (lazysodium) and JS (libsodium-wrappers) Cipher tests, made with PyNaCl (libsodium).
# Both implementations must open these, which shows they interoperate. Run: python3 tools/vectors/cipher_vectors.py
import nacl.bindings as b
from nacl.public import PrivateKey, SealedBox

key = bytes(range(32))
nonce = bytes(range(0x40, 0x58))
aad = b"circle:epoch:1"
msg = b"epoch key for the knitting circle"
aead = nonce + b.crypto_aead_xchacha20poly1305_ietf_encrypt(msg, aad, nonce, key)

sk = PrivateKey(bytes(range(0x80, 0xA0)))
sealed = SealedBox(sk.public_key).encrypt(b"wrapped circle key")

print("AEAD_KEY", key.hex())
print("AEAD_AAD", aad.hex())
print("AEAD_SEALED", aead.hex())
print("AEAD_PLAIN", msg.decode())
print("BOX_SECRET", bytes(sk).hex())
print("BOX_PUBLIC", bytes(sk.public_key).hex())
print("BOX_SEALED", sealed.hex())
print("BOX_PLAIN", "wrapped circle key")
