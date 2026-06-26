# 11 — Search (Client-Side)

The server holds **no index** (it can't read content). Search lives where the keys and plaintext live — on the device — over the device's **local subset** ([server 11](https://github.com/Nyxite/server)). The desktop is the full-corpus surface; Android searches what it has cached.

## 11.1 Scope on Android

| Included in the index | Excluded |
|---|---|
| All **keep-on-device** files (always present, decrypted) ([16 §16.2](16-offline-and-storage-policies.md)) | Files never fetched to this device |
| Recently opened, not-kept files still in the convenience cache | `excluded` files on *other* devices |
| Decrypted titles of all files known in the structure | Content the device hasn't decrypted |

So Android search is **best-effort over the local subset**; the UI must make this scope explicit ("searching X files on this device — open more or mark them keep-on-device to include them; the desktop searches everything").

## 11.2 Index

- **SQLite FTS5** virtual table `FileContentFts(fileId UNINDEXED, title, body)` inside the SQLCipher DB ([04 §4.3](04-local-data-model.md)). The index is encrypted at rest with the rest of the DB and **never uploaded**.
- Tokenizer: `unicode61` with diacritic folding; consider `porter` stemming for English; record the choice and keep it consistent. Support prefix queries.
- **Indexed content**: markdown/plaintext body (the decrypted current head), and titles for every file. Ink files index title only in v1.0.0 (no recognition yet; reserve a hook for recognized-text later, [10 §10.5](10-editors.md)).

## 11.3 Indexing lifecycle

- On decrypt: whenever a file's plaintext head is produced (open, sync pull, snapshot, CRDT merge to a quiescent point), `data-search` upserts its FTS row.
- On keep-on-device: `BlobPrefetchWorker` decrypts kept content → index it ([08 §8.7](08-sync-engine.md),[16 §16.2](16-offline-and-storage-policies.md)).
- On eviction: when a not-kept file's plaintext is evicted from the convenience cache, drop its body from the index but keep its title (so it still appears as a result that must be fetched to read).
- On delete/rotate: remove or refresh rows accordingly.
- `ReindexWorker` can rebuild the index (schema change, corruption) from locally available plaintext.

## 11.4 Query & UX

- Search box in the browse screen and a dedicated search screen; debounce input; run FTS on `IO`.
- Results: title + snippet (`snippet()`/`highlight()` from FTS5) + project/folder breadcrumb + sync/cache badge. Tapping opens the file (fetching+decrypting on demand if only title was indexed).
- Filters: by project/folder, by content type, by "available offline" (pinned/cached). Sort by relevance or recency.
- Clearly distinguish "found in cached content" from "title match only — open to search inside".

## 11.5 Privacy

- The index contains decrypted content; it is protected exactly like the DB (SQLCipher + Keystore + optional biometric, [17](17-security.md)).
- No query text or results ever leave the device. There is no server round-trip for search.
