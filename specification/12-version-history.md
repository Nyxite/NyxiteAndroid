# 12 — Version History

Full history of **encrypted, content-addressed snapshots**; **diffs and restore run on the device** because the server can't read either version ([server 10](https://github.com/Nyxite/NyxiteServer)).

## 12.1 Listing

- `GET /files/{id}/versions?cursor=&limit=` (newest first, paged until `nextCursor` is null) → version records: `seq`, `contentHash` (BLAKE3, opaque to server), `layout`, `sizeCipher`, `keyId`, `isHead`, `authorId?` (null = guest), `restoredFromSeq?`, `crdtSeq?` (text snapshots), `createdAt`. Cache as `FileVersionEntity` ([04](04-local-data-model.md)).
  The record has no blob address, because the ciphertext is fetched by `seq` ([§12.2](#122-fetch--decrypt-a-version)).
- The history screen shows a timeline: timestamp, author (resolve `authorId` → display name; "guest" for null), and a size hint. No plaintext size or server summary exists.
  A version with `restoredFromSeq` is labelled "restored from version n", because the server links a recorded restore to the version that wrote it ([server 10 §10.5](https://github.com/Nyxite/NyxiteServer)).
- Every account with read reach sees the history, a read-only grantee included, because it can already decrypt the content.
  A read-only grantee gets no restore action, because the server refuses it with `403 acl_denied`.
  Guest mode shows no history, because the server accepts a guest token only on the `/share/{token}` paths ([13](13-sharing.md)).
- A version sealed with a key generation this account never received stays in the timeline as "not readable with your keys" instead of an error, because a member added after a rotation gets wraps of the new generation only ([07 §7.6](07-key-and-device-management.md)).

## 12.2 Fetch & decrypt a version

- `GET /files/{id}/versions/{seq}/blob` → framed ciphertext. Unwrap the FK for that version's `keyId`/generation ([07](07-key-and-device-management.md)); `open` it; verify the BLAKE3 address ([06 §6.6](06-cryptography.md)).
  `GET /files/{id}/keys` returns the wraps of every generation this account received, so an older version opens with the wrap whose `keyId` matches the frame's header and the `Nyxite-Key-Id` response header.
- The BLAKE3 check is the client's, because the server never holds the plaintext and cannot check it.
  The client recomputes BLAKE3 over the decrypted bytes and compares it with the record's `contentHash`, which the response also sends as `ETag`.
  A mismatch or a failed `open` marks the version as damaged, and a damaged version is neither rendered, diffed nor restored.
- For text, a "version" is an encrypted Yrs **snapshot**; load it into a transient Yrs doc to render its content. For ink/binary it is the encrypted blob at that point.

## 12.3 Client-side diff

- **Text**: decrypt both snapshots → render to text → compute a line/word diff (a Myers/Hunt-McIlroy diff, e.g. `java-diff-utils`, run on `Default`). Optionally a structural diff from Yrs. Present additions/deletions inline or side-by-side; large diffs virtualized.
- **Ink/binary**: a metadata-level diff (strokes/pages added/removed, size/time) rather than a pixel diff; render thumbnails of both for comparison.
- There is **no server `/diff`** — everything is local.
  Both versions pass [§12.2](#122-fetch--decrypt-a-version) before the diff starts, so a diff never shows bytes that failed the integrity check (P2.4-TC-1).

## 12.4 Restore (non-destructive)

`POST /files/{id}/restore` with body **`{ seq }`** only records a **new head** derived from version `seq`; prior versions are untouched.

- **Text**: the client computes the target text from the restored snapshot, **diffs it against the current Yrs doc text, and applies the minimal insert/delete ops as a single Yrs transaction** so collaborators converge. The resulting CRDT update is encrypted and submitted via the relay/log, then the new head is snapshotted ([09 §9.6](09-realtime-collaboration.md)). This is **best-effort textual, not structural** convergence.
- **Ink/binary**: the client uploads the restored bytes as a new ciphertext head (`PUT /files/{id}/blob`), creating a new version.
- Restores are audited server-side (structural only). UX confirms before restoring and explains it creates a new version (nothing is overwritten).

The restore runs in this order, as the server contract of Phase 2.4 pins it ([server 10 §10.5](https://github.com/Nyxite/NyxiteServer)):

1. Fetch and verify version `seq` ([§12.2](#122-fetch--decrypt-a-version)), because a restore must not start from bytes that failed the check.
2. `POST /files/{id}/restore { seq }` with an `Idempotency-Key` kept for retries of this restore.
   The server marks this account's next version of the file as `restoredFromSeq: seq`, and a version a collaborator writes in between does not take the mark.
3. Write the new head through the normal write path, sealed with the file's current key.
   A version of an older generation is opened with its own wrap and sealed again, because the server refuses every write under an older key with `412 key_generation_stale`.
   For text, this device takes the snapshot after its restore update itself, because only this account's next version carries the link.
   A snapshot that meets `409` because a collaborator snapshotted later is taken again at the newer log head, which still contains the restore.
4. A `PUT /files/{id}/blob` that loses LWW is kept as a sibling and carries the link, and the screen shows the conflict ([§12.5](#125-lww-conflict-surfacing)).
   Before a retry on top of the new head the client records the restore again, so the head it then writes is labelled as well.

The restore runs as one unit of work on the restoring device and survives process death through the outbox, because the update or blob it writes is an ordinary pending write ([08](08-sync-engine.md)).
The restore action is not offered for a file excluded from sync, because the server takes no content for it and answers `409 excluded_content`.

## 12.5 LWW conflict surfacing

Because LWW losers are retained as versions ([08 §8.5](08-sync-engine.md)), the history screen is also where a user recovers a "lost" concurrent ink/binary edit: flag concurrent versions and offer "restore this one" / "keep both".

The version record does not say that a version lost LWW, so the client flags it from what it saw arrive.
A version is a kept loser when it came back as the `sibling` of a `409`, when the change feed delivered it with `isHead: false` ([08](08-sync-engine.md)), or when its `seq` is above the head's.
The client stores the flag as `lwwLoser` ([04](04-local-data-model.md)).
An older loser that a device only meets in the list, after later writes, looks like any earlier version, which stays open in the decision log of [`Nyxite`](https://github.com/Nyxite/Nyxite) (Phase 2.4 entry).

## 12.6 Storage considerations

- Snapshots are fetched on demand; the version list metadata is lightweight. Fetched version blobs go in the evictable convenience cache, not keep-on-device storage ([16 §16.3](16-offline-and-storage-policies.md)).
- Dedup is content-addressed and intra-file: re-saving identical content resolves to the same address (a `file_versions` row is still recorded for the timeline).
