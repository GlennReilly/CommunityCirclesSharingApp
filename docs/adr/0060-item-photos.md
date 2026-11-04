# 0060. Item photos

- Status: Accepted
- Date: 2026-11-04

## Context

Items are listed by name and a private description, which makes it hard to tell which ladder is meant or whether a tool is in good shape. ADR 0028 settled how a photo is prepared on the device (decoded, metadata stripped, at most 1600 px, WebP or JPEG at quality 80, plus a 320 px thumbnail, never over the 2 MiB blob cap) and said that the item payload's field names are decided with the feature. ADR 0026 gives the storage: a photo is a sealed blob on the server, named by its hash, uploaded with `PUT /blobs/{hash}`; the server cannot see inside it and never deletes unreferenced ones. `BlobSealing` seals and opens blobs under the circle key. What is missing is the reference from an item, the screen to add a photo and the way to show it.

## Decision

1. **Reference.** `item.upsert` gains an optional list of photos, each `{photo: blob hash, thumb: blob hash, alt: text}`, sealed with the rest of the payload (the server learns only the hashes it already knows from the uploads). Older items have none.
2. **Preparing.** The browser follows ADR 0028 exactly, using a canvas: decode, apply orientation, scale, re-encode (WebP, JPEG where WebP is not available), thumbnail, metadata scan on the result. A photo that cannot be brought under the limits is refused with a plain message.
3. **Showing.** Item cards show the first thumbnail; the item page shows the photos at full size; a photo is fetched, opened with the circle key and shown from a temporary object URL on this device. A photo that cannot be fetched (offline, not yet synced) shows a grey box with the item's name; nothing blocks borrowing.
4. **Replacing.** Changing or removing a photo is another `item.upsert` without it. The old blob stays on the server (ADR 0026) and is dropped when the circle is next exported and imported.
5. **Offline and the replica.** Photos are fetched when seen and kept in the browser cache for the session; they are not part of the local log and are not in a browser export (that export holds only the log, ADR 0030), which the Settings text says.

## Decisions for the owner (answered 2026-11-04: A1, B1, C1)

**A. Who sees a photo. A1 (recommended): every member**, referenced from the sealed `item.upsert`; thumbnails in the Items list help people find things. **A2: only the owner and people approved to borrow it**, kept inside `item.detail`; nobody can see what an item looks like before asking, and the list has no pictures.

**B. How many. B1 (recommended): up to 3 per item**, enough for the front, the back and a close-up; keeps the quota and the screen small. **B2: up to 6**, the limit ADR 0028 allows. **B3: one photo only**, the simplest.

**C. Description for screen readers. C1 (recommended): always present**, a short text of what the photo shows (up to 140 characters), because a picture without a description is invisible to a blind member and the project asks for accessibility first. **C2: optional**, quicker to add.

## Consequences

- Needs a payload change and strict parser update, fold and tests, a canvas pipeline in the browser (about 6 to 10 KB of script, within the remaining headroom of about 18 KB), a file picker with camera capture on phones, and upload through the blob routes.
- Photos cost storage: about 300 KB each, so a 256 MiB circle holds well over a thousand. The quota and upload rate limit remain the backstop for a modified client.
- A member who sees a photo can save it; sealing keeps it from the server and from non-members, not from members.

## Amendment (2026-11-04, the owner)

The description of a photo is a field the user may leave blank; the app then uses the item's name. The stored description is never empty (the payload still requires 1 to 140 characters), so a screen reader always has something to say.

## As built (2026-11-04)

`item.upsert` carries an optional `photos` list (`ItemPhoto`: photo hash, thumbnail hash, circle-key epoch, description; at most 3, description 1 to 140 characters; later upserts replace the list, and every place that re-sends an item (editing, adding a device, changing the item key after a removal) keeps it). Blob upload and download go through `BlobTransport` / `HttpBlobApi` (raw bytes with the device's session token; `FetchBlobTransport` in the browser). `CircleApp.addPhoto/removePhoto/photoBytes` seal both images with the circle key, upload them, then publish the item; a blank description becomes the item's name. The page prepares pictures in `ImagePrep.kt` following ADR 0028 (orientation applied, scaled, WebP or JPEG ladders, thumbnail), removes the colour profile Chrome's encoder adds (`ImagePolicy.stripIcc`, a JPEG or WebP rewrite tested on both targets) and refuses anything the metadata scan still flags. Item cards show thumbnails, "Larger photos" loads full size when opened; the item's change page lists the photos with Remove and offers the add form while there is room. Photos are fetched when seen, kept in a small in-memory cache, and are not in a browser export.
