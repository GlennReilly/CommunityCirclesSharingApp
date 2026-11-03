# Argon2id vectors for the passphrase hasher (ADR 0039), from libsodium via PyNaCl, independent of the Kotlin code.
# libsodium fixes parallelism at 1 and takes memory in bytes. Run: python3 tools/vectors/argon2_vectors.py
import unicodedata
import nacl.bindings as b

password = b"correct horse battery staple"
salt = bytes(range(16))


def argon2id(pw: bytes, t: int, m_kib: int) -> str:
    return b.crypto_pwhash_alg(32, pw, salt, t, m_kib * 1024, b.crypto_pwhash_ALG_ARGON2ID13).hex()


print("small  (t=1, m=1024 KiB):", argon2id(password, 1, 1024))
print("default (t=3, m=65536 KiB):", argon2id(password, 3, 65536))
decomposed = "café au lait 123"
print("NFKC of a decomposed passphrase (t=1, m=1024 KiB):", argon2id(unicodedata.normalize("NFKC", decomposed).encode(), 1, 1024))
