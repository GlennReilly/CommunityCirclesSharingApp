# Key commitment vectors (ADR 0045), made independently of the Kotlin code with hashlib.
# keyHash = BLAKE2b-256("circles/key-commitment/v1" || circle id (ASCII hex) || epoch (4 bytes, big endian) || key)
# Run: python3 tools/vectors/key_commitment_vectors.py
import hashlib

circle = "0123456789abcdef0123456789abcdef"
key = bytes(range(32))
print("CIRCLE", circle)
print("KEY", key.hex())
for epoch in (1, 2, 258):
    digest = hashlib.blake2b(b"circles/key-commitment/v1" + circle.encode() + epoch.to_bytes(4, "big") + key, digest_size=32).hexdigest()
    print(f"EPOCH {epoch}", digest)
