# 16 — Offline & Local Storage

Each file is encrypted under its **own key** and fetched independently, so local storage is just a set of **per-file local copies the user controls** — not an opaque, auto-growing cache. The user decides what lives on this device; the default is conservative (only what's opened or explicitly kept), and everything kept on-device stays **encrypted at rest** ([17](17-security.md)).

## 16.1 What is stored where

| Data | Location | Lifetime |
|------|----------|----------|
| Structure, names, metadata, sync state, FTS titles | SQLCipher DB | Persistent (structure is small; the whole tree is always available) |
| **Keep-on-device** files (text + ink), decrypted for use, **encrypted at rest** | App-private files / DB, Keystore-gated | Kept until the user turns keep-on-device off ([§16.2](#162-keep-on-device-the-core-control)) |
| Opened-but-not-kept files | Small optional convenience store | Until closed, or per the convenience-cache setting ([§16.3](#163-on-demand-fetch--optional-convenience-cache)) |
| Fetched version snapshots | Convenience store | Evictable; re-fetchable |
| Decrypted working copy of the open file | Memory + temp | Session only |
| Keys/tokens | Keystore / secret storage | Per [07](07-key-and-device-management.md),[14](14-authentication.md) |

All of the above are **per-account** ([14 §14.7](14-authentication.md)): each account has its own SQLCipher DB, file store, FTS index, and key/token stores, isolated by `accountId`; removing an account frees all of its local storage.

Use `noBackupFilesDir` for content/keys so Android auto-backup never exfiltrates sensitive bytes; disable `allowBackup` or use a strict backup-rules XML excluding the DBs, file stores, and key stores ([17](17-security.md)).

## 16.2 Keep-on-device (the core control)

The user explicitly chooses what to download and keep on this device, at **three granularities**, with cascading:

- **File** — a per-file "keep on this device" toggle.
- **Folder** — keep the whole folder; applies to all files in it and **becomes the default for new files** added to it.
- **Project** — keep the whole project; cascades to all folders/files and to anything added later.

Cascade rules:
- Setting keep-on-device on a folder/project marks every descendant kept and sets the inherited default; an individual file can still be **overridden** (e.g. keep a project but exclude one large file, or keep one file inside an otherwise-not-kept folder).
- A file's effective state = its own explicit setting if present, else the nearest ancestor's setting, else the account default (**not kept**).
- New files inherit their parent's effective setting.

Behavior of a **kept** file: proactively downloaded, decrypted, **indexed for search** ([11](11-search.md)), and available fully offline. It remains encrypted at rest; "keep on device" controls *availability*, not whether it's encrypted.

This is a **per-device** choice (each of the user's devices keeps its own selection). It maps onto the server sync policy ([08 §8.2](08-sync-engine.md)): kept ⇒ treated as `pinned-local` on this device; not-kept ⇒ `server-default` (fetched on demand). The separate **`excluded`** choice ("device-only, never upload to the server") remains available per file for content the user never wants to leave the device. *(Open item: confirm whether the server's `sync_policy` is per-device or shared across a user's devices; if shared, this per-device selection is tracked client-side — [19 §19.5](19-open-questions.md).)*

Persisted as a `keepOnDevice` setting on files and an inherited default on folders/projects ([04 §4.2](04-local-data-model.md)).

## 16.3 On-demand fetch & optional convenience cache

- Files that are **not** kept-on-device are fetched and decrypted **on open**, then discarded when closed — nothing accumulates silently.
- An optional **convenience cache** (default **on**, user-toggleable, with a small optional size cap) may retain *recently opened* not-kept files so reopening is instant. It is purely an optimization: evicting an item only means it's re-downloaded next time (the server always holds the full encrypted copy). It is **never** used for kept files (those are guaranteed) and never holds `excluded` content.
- On eviction of a not-kept file's plaintext, drop its FTS **body** but keep its **title** so it stays discoverable; reopening re-indexes it ([11 §11.3](11-search.md)).
- A **Storage settings** screen shows usage (kept vs. convenience cache vs. index), lets the user toggle/limit or clear the convenience cache, and surfaces keep-on-device toggles at file/folder/project level.

## 16.4 Battery & network constraints

- `BlobPrefetchWorker` (downloading kept-on-device files) defaults to **unmetered Wi-Fi + battery-not-low**, configurable (e.g. "download kept files on Wi-Fi only" / "also on cellular").
- Delta sync of **structure** runs expedited on foreground/connectivity-regained and periodically in the background ([08 §8.7](08-sync-engine.md)); content download honors the keep-on-device selection and the lock window ([19 §19.2](19-open-questions.md)).
- Awareness/relay traffic is throttled ([09 §9.7](09-realtime-collaboration.md)); the foreground collaboration service runs only during active editing.
- Respect Doze/App Standby; use WorkManager (not raw alarms) so the OS can batch work.

## 16.5 Large files & memory

- Stream encryption/decryption and BLAKE3 hashing for large ink/binary blobs to bound memory ([06 §6.6](06-cryptography.md)); avoid loading whole multi-MB blobs into a single buffer.
- Enforce the 100 MB inline ciphertext limit client-side ([05 §5.6](05-api-client.md)); chunked upload for larger is a Phase-5 item.

## 16.6 First-run & low-storage behavior

- On first run / new device, sync structure + names quickly (small), so the whole tree is browsable immediately; then download kept-on-device content per the constraints above, and fetch everything else on open.
- Under low device storage, pause not-kept prefetch and the convenience cache, warn the user, and prioritize the open file and kept content. Kept content is never silently dropped; if storage is critically low, prompt the user to reduce their keep-on-device selection rather than evicting it behind their back.

## 16.7 Plaintext export / external-editor interop — not on Android

Storing files **decrypted on shared storage** for editing in other apps is intentionally **a desktop feature, not an Android one**. On Android, content stays inside the encrypted boundary; the app does not write plaintext copies to shared storage. (Standard per-item "share/export" via the system share sheet for a single file remains possible where it makes sense, e.g. exporting a rendered note, but there is no general plaintext working-copy mechanism here.)
