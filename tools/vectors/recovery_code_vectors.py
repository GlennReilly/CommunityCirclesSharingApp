# Recovery code vectors (ADR 0047), made independently of the Kotlin code with hashlib.
# Words: 128 bits of entropy + 4-bit checksum (top bits of byte 0 of BLAKE2b-256("circles/recovery-code/v1" || entropy)),
# written as 12 indices of 11 bits into a 2048-word list. Seed = BLAKE2b-256("circles/recovery-seed/v1" || entropy);
# device id = first 16 bytes of BLAKE2b-256("circles/recovery-device-id/v1" || entropy).
# Run: python3 tools/vectors/recovery_code_vectors.py
import hashlib
from pathlib import Path

def h(domain: bytes, data: bytes) -> bytes:
    return hashlib.blake2b(domain + data, digest_size=32).digest()

def words_for(entropy: bytes, wordlist):
    checksum = h(b"circles/recovery-code/v1", entropy)[0] >> 4
    bits = (int.from_bytes(entropy, "big") << 4) | checksum
    return [wordlist[(bits >> (11 * (11 - i))) & 0x7FF] for i in range(12)]

real = (Path(__file__).resolve().parents[2] / "web/src/jsMain/resources/wordlist.txt").read_text().split()
synthetic = [f"w{i:04d}" for i in range(2048)]
for name, wordlist in (("real", real), ("synthetic", synthetic)):
    assert len(wordlist) == 2048
    for entropy in (bytes(16), bytes(range(16)), bytes([0xFF] * 16), bytes.fromhex("0123456789abcdef0011223344556677")):
        print(name, "ENTROPY", entropy.hex())
        print(name, "WORDS", " ".join(words_for(entropy, wordlist)))
        print(name, "SEED", h(b"circles/recovery-seed/v1", entropy).hex())
        print(name, "DEVICE", h(b"circles/recovery-device-id/v1", entropy)[:16].hex())
