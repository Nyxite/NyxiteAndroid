# 12 — Version History

Full history of **encrypted, content-addressed snapshots**; **diffs and restore run on the device** because the server can't read either version ([server 10](https://github.com/Nyxite/server)).

## 12.1 Listing

- `GET /files/{id}/versions` (paginated, newest first) → version metadata: `seq`, `contentHash` (BLAKE3, opaque to server), `blobRef`, `sizeCipher`, `keyId`, `authorId?` (null = guest), `createdAt`. Cache as `FileVersionEntity` ([04](04-local-data-model.md)).
- The history screen shows a timeline: timestamp, author (resolve `authorId` → display name; "guest" for null), and a size hint. No plaintext size or server summary exists.

## 12.2 Fetch & decrypt a version

- `GET /files/{id}/versions/{seq}/blob` → framed ciphertext. Unwrap the FK for that version's `keyId`/generation ([07](07-key-and-device-management.md)); `open` it; verify the BLAKE3 address ([06 §6.6](06-cryptography.md)).
- For text, a "version" is an encrypted Yrs **snapshot**; load it into a transient Yrs doc to render its content. For ink/binary it is the encrypted blob at that point.

## 12.3 Client-side diff

- **Text**: decrypt both snapshots → render to text → compute a line/word diff (a Myers/Hunt-McIlroy diff, e.g. `java-diff-utils`, run on `Default`). Optionally a structural diff from Yrs. Present additions/deletions inline or side-by-side; large diffs virtualized.
- **Ink/binary**: a metadata-level diff (strokes/pages added/removed, size/time) rather than a pixel diff; render thumbnails of both for comparison.
- There is **no server `/diff`** — everything is local.

## 12.4 Restore (non-destructive)

`POST /files/{id}/restore` with body **`{ seq }`** only records a **new head** derived from version `seq`; prior versions are untouched.

- **Text**: the client computes the target text from the restored snapshot, **diffs it against the current Yrs doc text, and applies the minimal insert/delete ops as a single Yrs transaction** so collaborators converge. The resulting CRDT update is encrypted and submitted via the relay/log, then the new head is snapshotted ([09 §9.6](09-realtime-collaboration.md)). This is **best-effort textual, not structural** convergence.
- **Ink/binary**: the client uploads the restored bytes as a new ciphertext head (`PUT /files/{id}/blob`), creating a new version.
- Restores are audited server-side (structural only). UX confirms before restoring and explains it creates a new version (nothing is overwritten).

## 12.5 LWW conflict surfacing

Because LWW losers are retained as versions ([08 §8.5](08-sync-engine.md)), the history screen is also where a user recovers a "lost" concurrent ink/binary edit: flag concurrent versions and offer "restore this one" / "keep both".

## 12.6 Storage considerations

- Snapshots are fetched on demand; the version list metadata is lightweight. Fetched version blobs go in the evictable convenience cache, not keep-on-device storage ([16 §16.3](16-offline-and-storage-policies.md)).
- Dedup is content-addressed and intra-file: re-saving identical content resolves to the same address (a `file_versions` row is still recorded for the timeline).
