# 08 — Sync Engine

Reconciles the device's local state ([04](04-local-data-model.md)) with the server for every readable file, honoring per-file policy and the right model per content type — **all over ciphertext** ([server 06](https://github.com/Nyxite/NyxiteServer)). The server moves bytes, sequences encrypted updates, and tracks versions/policies; the client does all crypto and merge.

## 8.1 Content-type → sync model

| `contentType` | Phase | Sync model | Channel |
|---|---|---|---|
| `markdown`, `plaintext`, `sourcecode` | 1 / 5 | **CRDT** (client-merged, Yrs) | Encrypted relay ([09](09-realtime-collaboration.md)) + REST `crdt/log` fallback |
| `ink` | 3 | **LWW / version-vector** | `PUT/GET /files/{id}/blob` (ciphertext) |
| `office`, `image`, `binary` | 5 | **LWW / version-vector** | blob, chunked for large |

`contentType` is immutable after creation and selects the decoder/editor and the sync path.

## 8.2 Sync policies

The server `syncPolicy` enum is **only `{ server-default, excluded }`** (DECISION — `pinned-local` is removed everywhere; offline pinning is the separate client-local `keepOnDevice` field, below).

| Policy | Server stores? | Client fetches? | Keeps plaintext offline? |
|--------|---|---|---|
| `server-default` | yes (ciphertext) | on-demand (open) or proactively if kept-on-device | depends on `keepOnDevice` |
| `excluded` | **no** | never | device-only (never uploaded) |

Transitions: `excluded → server-default` triggers an initial ciphertext upload from this device; `server-default → excluded` stops syncing and drops local plaintext, and the server schedules content purge. Uploading content for an `excluded` file is rejected (`409 excluded_content`) — never retried ([05](05-api-client.md)).

**Local retention is separate from server policy.** The user's **keep-on-device** selection ([16 §16.2](16-offline-and-storage-policies.md)) is the **client-local, per-device** control over which `server-default` files are proactively downloaded and kept offline (set at file/folder/project with cascade; values `keep`/`dontKeep`/`inherit`). **The server never sees `keepOnDevice`** — a kept file is just a `server-default` file this device chooses to prefetch+decrypt+index; a not-kept one is fetched on open. `excluded` remains the distinct "never upload to the server at all" choice ([19 §19.5](19-open-questions.md)).

## 8.3 Two-tier reconciliation

### Manifest (periodic / on project open)
`GET /sync/manifest?projectId=` returns per-file structural/opaque records: `fileId`, `contentType`, `syncPolicy`, `currentVersionSeq`, `contentHash` (opaque), `keyGeneration`, `updatedAt`, `deletedAt`. The client diffs against `LocalStore` to decide pull/push/delete and to drive the search index ([11](11-search.md)). No names, no content.

### Delta (incremental, cursor-based)
`POST /sync/changes` with `{ since: cursor, projectId, localChanges[] }` → `{ serverChanges[], nextCursor }`. Change `kind` ∈ `structure | blob | crdt | delete | keyrotate`. The opaque `nextCursor` (base64url) is persisted verbatim (`SyncCursorEntity.cursor`, [04](04-local-data-model.md)), **never parsed by the client**, and resumable across interruptions. The client sends its own pending local changes from the outbox and applies server changes:

- `structure` → upsert project/folder/file metadata (decrypt `nameEnc`).
- `blob` → mark file `PendingPull`; fetch ciphertext on demand or proactively (pinned).
- `crdt` → fetch encrypted updates after local cursor (or rely on live relay).
- `delete` → propagate `deletedAt`.
- `keyrotate` → refetch wrapped key for the new generation ([07 §7.6](07-key-and-device-management.md)).

## 8.4 Text sync (CRDT)

- **Live**: via the relay ([09](09-realtime-collaboration.md)).
- **Offline / catch-up**:
  1. `GET /files/{id}/crdt/log?since=<localSeq>` → encrypted updates.
  2. `GET /files/{id}/snapshot` → latest encrypted snapshot (bootstrap).
  3. Decrypt snapshot + updates; reconstruct the local Yrs **state vector**; merge (order-independent).
  4. Submit local encrypted updates from the outbox (`POST /files/{id}/crdt/log`) when the relay is unavailable.
- Convergence is guaranteed by the CRDT regardless of server ordering; the server's `seq` is only a durable cursor/snapshot watermark.
- **Reconciliation under rotation (offline catch-up).** Each outbound `CrdtUpdateEntity` row records the `keyId` it was sealed under. On reconnect, the outbox is submitted in order; the server assigns a `seq` and acks each — the client matches the **server-assigned `seq` back to the outbox row by the update's stable client id** and sets `acked = true` with the stored `seq`. If a key rotation committed while offline, updates sealed under the **old** `keyId` are rejected `412 key_generation_stale`; `KeyRotationWorker` re-seals the pending update under the new FK and resubmits. Resubmission is **idempotent via the update's stable client id**, so a re-seal never double-applies.

## 8.5 Ink/binary sync (LWW / version-vector)

- **Version-vector shape**: the client keeps a per-file `{ deviceId -> counter }` map in `metadataJson` (encrypted in `metadata_enc` when synced; the server cannot read it). **Increment rule**: on each local *committed* ink edit, increment this device's own component by 1. **Comparison rule** (component-wise across the union of device ids, missing = 0): `A == B` if all components equal; `A` is an **ancestor** of `B` if every component of `A` ≤ `B` and at least one is strictly less (and vice-versa for descendant); otherwise the two are **concurrent** (a real conflict).
- **Upload**: the client sends the parent version's `seq` as **`If-Match`** on the ink/binary `PUT /files/{id}/blob`. If the server head `!= parentSeq` it is a concurrent write: the server applies **last-write-wins by server-received time** and responds **`409 conflict`** with the winning version metadata. The **losing bytes are retained as a sibling `FileVersionEntity` row** (not head), surfaced in history (no data loss); the tie-break is last-write-wins by server time.
- **Conflict reconcile**: on `409`, the client records both versions (winner as head, loser as sibling), classifies them via the version-vector (equal/ancestor/concurrent), surfaces a non-destructive "two versions exist" affordance in history ([12](12-version-history.md)) for concurrent edits, and lets the user keep/merge manually (ink has no automatic merge).
- **Download**: metadata eagerly; ciphertext on-demand (`server-default`, not kept) or proactively (kept-on-device). Use `If-None-Match: contentHash` to skip unchanged blobs.

## 8.6 Deletes

Soft-delete via `deletedAt`, propagated through manifest/delta; devices converge. `excluded` files never reached the server, so deleting them is local-only. Hard delete/purge is admin-only and not initiated from the normal client.

## 8.7 Scheduling on Android

- **`DeltaSyncWorker`** (WorkManager): expedited on app foreground & on connectivity regained; periodic (e.g. 15 min, tunable) with `NetworkType.CONNECTED`. Drains the outbox, runs delta sync per active project.
- **`BlobPrefetchWorker`**: fetches/decrypts keep-on-device content; constraints configurable (unmetered/charging) to respect the storage/battery budget ([16](16-offline-and-storage-policies.md)).
- **`SnapshotWorker`**: compacts open Yrs docs to encrypted snapshots on trigger ([09 §9.6](09-realtime-collaboration.md)).
- **`KeyRotationWorker`**: performs background FK rotation after revocation ([07 §7.6](07-key-and-device-management.md)).
- **`ReindexWorker`**: rebuilds/updates the FTS index ([11](11-search.md)).
- Active editing holds a **foreground service** so live sync survives brief backgrounding ([01 §1.6](01-architecture.md)).
- All workers are coalesced/unique by name; backoff is exponential with jitter; they respect `Retry-After` from `429`.

## 8.8 Per-file sync state machine

The canonical state set (identical to [01 §1.7](01-architecture.md) and [04 §4.4](04-local-data-model.md)) is `Synced`, `PendingPush`, `PendingPull`, `Downloading`, `Uploading`, `Conflicted`, `Rotating`, `Excluded`, `Error(code)`; the typical flow is `Synced → PendingPush/PendingPull → Uploading/Downloading → Synced`, with `Conflicted` (LWW), `Rotating`, `Excluded`, and `Error(code)` as terminal/branch states. Persisted on `FileEntity` ([04 §4.4](04-local-data-model.md)) and shown as compact badges; tapping a badge explains the state and offers actions (retry, view conflict, etc.).

## 8.9 Integrity & trust

- After each download, decrypt and **verify the BLAKE3 address** before trusting bytes ([06 §6.6](06-cryptography.md)).
- The client never trusts the server to have validated content; only structure/ACL/write-once are server-guaranteed.
