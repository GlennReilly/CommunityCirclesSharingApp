# Vectors for DeviceSeed (ADR 0039), made independently of the Kotlin code with hashlib and `cryptography`.
# Run: python3 tools/vectors/device_seed_vectors.py
import hashlib
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives import serialization as ser

seed = bytes(range(32))


def derive(label: bytes) -> bytes:
    return hashlib.blake2b(b"circles/device-seed/v1/" + label + seed, digest_size=32).digest()


sign, box = derive(b"sign"), derive(b"box")
raw = dict(encoding=ser.Encoding.Raw, format=ser.PublicFormat.Raw)
print("SEED", seed.hex())
print("SIGN_SECRET", sign.hex())
print("SIGN_PUBLIC", Ed25519PrivateKey.from_private_bytes(sign).public_key().public_bytes(**raw).hex())
print("BOX_SECRET", box.hex())
print("BOX_PUBLIC", X25519PrivateKey.from_private_bytes(box).public_key().public_bytes(**raw).hex())
