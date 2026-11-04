# 0060. Item photos

- Status: Proposed
- Date: 2026-11-04

## Context

Items are listed by name and a private description, which makes it hard to tell which ladder is meant or whether a tool is in good shape. ADR 0028 settled how a photo is prepared on the device (decoded, metadata stripped, at most 1600 px, WebP or JPEG at quality 80, plus a 320 px thumbnail, never over the 2 MiB blob cap) and said that the item payload's field names are decided with the feature. ADR 0026 gives the storage: a photo is a sealed blob on the server, named by its hash, uploaded with `PUT /blobs/{hash}`; the server cannot see inside it and never deletes unreferenced ones. `BlobSealing` seals and opens blobs under the circle key. What is missing is the reference from an item, the screen to add a photo and the way to show it.

## Decision (proposed)

1. **Reference.** `item.upsert` gains an optional list of photos, each `{photo: blob hash, thumb: blob hash, alt: text}`, sealed with the rest of the payload (the server learns only the hashes it already knows from the uploads). Older items have none.
2. **Preparing.** The browser follows ADR 0028 exactly, using a canvas: decode, apply orientation, scale, re-encode (WebP, JPEG where WebP is not available), thumbnail, metadata scan on the result. A photo that cannot be brought under the limits is refused with a plain message.
3. **Showing.** Item cards show the first thumbnail; the item page shows the photos at full size; a photo is fetched, opened with the circle key and shown from a temporary object URL on this device. A photo that cannot be fetched (offline, not yet synced) shows a grey box with the item's name; nothing blocks borrowing.
4. **Replacing.** Changing or removing a photo is another `item.upsert` without it. The old blob stays on the server (ADR 0026) and is dropped when the circle is next exported and imported.
5. **Offline and the replica.** Photos are fetched when seen and kept in the browser cache for the session; they are not part of the local log and are not in a browser export (that export holds only the log, ADR 0030), which the Settings text says.

## Decisions for the owner

**A. Who sees a photo. A1 (recommended): every member**, referenced from the sealed `item.upsert`; thumbnails in the Items list help people find things. **A2: only the owner and people approved to borrow it**, kept inside `item.detail`; nobody can see what an item looks like before asking, and the list has no pictures.

**B. How many. B1 (recommended): up to 3 per item**, enough for the front, the back and a close-up; keeps the quota and the screen small. **B2: up to 6**, the limit ADR 0028 allows. **B3: one photo only**, the simplest.

**C. Description for screen readers. C1 (recommended): required**, a short text of what the photo shows (up to 140 characters), because a picture without a description is invisible to a blind member and the project asks for accessibility first. **C2: optional**, quicker to add.

## Consequences

- Needs a payload change and strict parser update, fold and tests, a canvas pipeline in the browser (about 6 to 10 KB of script, within the remaining headroom of about 18 KB), a file picker with camera capture on phones, and upload through the blob routes.
- Photos cost storage: about 300 KB each, so a 256 MiB circle holds well over a thousand. The quota and upload rate limit remain the backstop for a modified client.
- A member who sees a photo can save it; sealing keeps it from the server and from non-members, not from members.
