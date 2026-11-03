# 0028. Client image policy: resize, re-encode, strip metadata

- Status: Accepted
- Date: 2026-11-03

## Context

The server stores blobs as opaque ciphertext (ADR 0026), so it cannot resize, compress or inspect images, and a photo is only ever as small as the client made it. A phone photo is typically 3 to 8 MB, which does not fit the 2 MiB blob cap, and it usually carries EXIF metadata, including GPS coordinates. An item photo taken at home would tell everyone in the circle where the owner lives.

## Decision

Every image is prepared on the device, before it is sealed and uploaded:

1. **Decode and sanitise.** Refuse anything that is not a decodable image, larger than 25 MB, or above 40 megapixels (decompression bombs). Apply the EXIF orientation, convert to sRGB, then drop all metadata (EXIF, XMP, IPTC, ICC). Re-encoding from decoded pixels (browser canvas or `OffscreenCanvas`) removes metadata by construction; a client test scans the output for `Exif`, `XMP` and GPS markers.
2. **Scale.** Longest edge at most 1600 px; never enlarge.
3. **Re-encode** as WebP at quality 80, or JPEG at quality 80 where the browser cannot encode WebP. If the result is over 400 KB, lower the quality in steps to 60, then scale to 1200 px, then give up and tell the user. A result over the blob cap is never uploaded.
4. **Thumbnail.** A second blob with the longest edge at most 320 px at quality 70, aimed at 40 KB or less, for lists and cards.
5. **At most 6 photos per item.** How an item or profile refers to its blobs (field names in the payload) is decided with the photo feature, by an amendment to ADR 0025.
6. **Originals are not kept** by the app, and alt text is a field of the item's own payload, not part of the image.
7. `shared` carries only the numbers and the size checks as named constants and pure functions (`ImagePolicy`), tested on both targets. Decoding and encoding stay in each client, because they need platform image APIs.
8. The server cannot enforce any of this. A modified client can upload any blob up to 2 MiB; the per-circle quota and upload rate limit (ADR 0026) are the backstop.

## Consequences

- Typical photos come to 150 to 400 KB, so a 256 MiB circle quota holds roughly 600 to 1,700 of them.
- Location and device details never leave the owner's device in an image.
- Re-encoding is lossy, and the original is gone; owners who want full resolution must share it another way.
- A client that skips the policy (or an older one) can still leak EXIF; the circle's other members' clients could in principle warn, but cannot prevent it.
