# 04 — Local Data Model

The local database is the **offline-first source of truth** for the UI ([01 §1.1](01-architecture.md)). It mirrors the server's structure graph, holds decrypted-on-device names/metadata (because the DB itself is encrypted at rest), tracks per-file sync state, caches CRDT/version pointers, and backs the full-text index.

## 4.1 Storage engine & at-rest encryption

- **Room** over **SQLCipher** (`SupportFactory` with a passphrase). The app is **multi-account from v1.0.0** ([14 §14.7](14-authentication.md)), so there is **one SQLCipher database per account** (`nyxite-{accountId}.db`), each with its **own 256-bit DB master key** generated on first sign-in of that account and stored **wrapped by a hardware-backed Keystore key** (StrongBox when available), unwrapped only after app unlock ([07](07-key-and-device-management.md), [17](17-security.md)). Account databases are never cross-queried.
- A tiny separate **account registry** is a **minimal standalone SQLCipher DB** (encrypted, itself Keystore-wrapped, consistent with the per-account DBs — not Proto DataStore) so the app can show the account switcher and pick a DB to open *before* any account DB is unlocked. Minimal schema, one row per known account: `accountId` (PK), `host` (instance base host), `displayName`, `lastUsedAt` (epoch millis), `dbFileName` (the `nyxite-{accountId}.db` file to open). It holds no content, names, or keys.
- Because the whole DB is encrypted at rest, decrypted **names**, small **metadata**, and the **FTS index** may be stored as plaintext *inside* the DB. They are still protected by SQLCipher + Keystore + (optional) biometric gate. Identity **private keys** are **not** kept in the DB — they live in the dedicated key store ([07](07-key-and-device-management.md)).
- Large content blobs (ink/binary ciphertext, decrypted ink for pinned files, cached snapshots) live on the filesystem in `BlobCache`, not in the DB ([16](16-offline-and-storage-policies.md)).

## 4.2 Entities (Room) — mirroring the server domain

IDs are server-issued **UUIDv7** strings; created-on-device rows generate a UUIDv7 client-side and reconcile. All `*_enc`/name fields are decrypted on read and stored decrypted in this (encrypted) DB; the **plaintext never leaves the device**.

### ProjectEntity
| Column | Type | Notes |
|--------|------|-------|
| `id` | TEXT PK | UUIDv7 |
| `ownerId` | TEXT | |
| `name` | TEXT | decrypted project name |
| `metadataJson` | TEXT? | decrypted metadata |
| `keepOnDeviceDefault` | TEXT? | per-device keep-on-device default for this project (`keep`/`dontKeep`/null=account default); cascades to descendants ([16 §16.2](16-offline-and-storage-policies.md)) |
| `createdAt`,`updatedAt` | INTEGER | epoch millis |
| `deletedAt` | INTEGER? | soft delete |

### FolderEntity
`id` PK, `projectId`, `parentFolderId?` (null = project root), `name`, `metadataJson?`, `keepOnDeviceDefault` (per-device `keep`/`dontKeep`/null=inherit), timestamps, `deletedAt?`. Index on `projectId`, `parentFolderId`. Acyclic parent chain enforced client-side. `keepOnDeviceDefault` cascades to contained folders/files and seeds new descendants ([16 §16.2](16-offline-and-storage-policies.md)).

### FileEntity
| Column | Type | Notes |
|--------|------|-------|
| `id` | TEXT PK | UUIDv7 |
| `projectId`,`folderId?`,`ownerId` | TEXT | folder null = project root |
| `name` | TEXT | decrypted file name |
| `contentType` | TEXT | enum: `markdown`,`plaintext`,`ink`,`sourcecode`,`office`,`image` ([08](08-sync-engine.md)) — immutable |
| `syncPolicy` | TEXT | server-side policy enum: `server-default`,`excluded` only ([08 §8.2](08-sync-engine.md)). The server never sees `keepOnDevice` (offline pinning is the separate client-local field below). |
| `keepOnDevice` | TEXT? | **client-local, per-device** offline-pinning setting: `keep`/`dontKeep`/null=`inherit` from folder/project ([16 §16.2](16-offline-and-storage-policies.md)); never sent to the server |
| `currentVersionSeq` | INTEGER? | head pointer |
| `crdtDocId` | TEXT? | **client-allocated UUIDv7 at file creation** (required for text types, null otherwise); sent in the create-file request |
| `keyGeneration` | INTEGER | monotonic FK rotation counter (1:1 with the current `key_id`); drives `412 key_generation_stale` handling. The `key_id` (uuid) is the stable frame/crdt/version reference for a specific file-key; `keyGeneration` is the rotation generation. |
| `metadataJson` | TEXT? | decrypted per-file metadata. For ink/binary it carries the per-file LWW **version-vector**: a `{ deviceId -> counter }` map (see [08 §8.5](08-sync-engine.md)), incremented on each local committed ink edit and compared component-wise to classify equal/ancestor/concurrent. |
| `createdAt`,`updatedAt`,`deletedAt?` | INTEGER | |
| `contentHash` | BLOB? | BLAKE3 of head plaintext (cache) |
| `syncState` | TEXT | local state machine ([§4.4](#44-sync-state)) |
| `cacheState` | TEXT | `none`,`metadataOnly`,`ciphertextCached`,`plaintextCached` |
| `lastOpenedAt` | INTEGER? | for convenience-cache eviction of not-kept files ([16 §16.3](16-offline-and-storage-policies.md)) |

Indexes: `projectId`, `folderId`, `ownerId`, `syncState`, `keepOnDevice`, `(keepOnDevice, lastOpenedAt)`. Effective keep-on-device is resolved by walking `file.keepOnDevice` → nearest ancestor `keepOnDeviceDefault` → account default (not kept).

### FileKeyEntity (locally unwrapped/wrapped cache)
`id` (TEXT PK, **UUIDv7 surrogate key** — mirrors the server C1 fix), `fileId`, `keyId`, `generation`, `memberId?`, `shareId?`, `wrappedKey` BLOB (as fetched from server, for re-upload/rotation), and a transient unwrapped handle that is **never persisted in plaintext** — the unwrapped FK is held only in memory / re-derived on demand via the identity key.

- **Invariant (CHECK)**: exactly one of `memberId` / `shareId` is non-null (a wrapped key targets either an account member or a link share, never both).
- **Indices**: unique `(fileId, keyId, memberId)` scoped to `memberId IS NOT NULL`, and unique `(fileId, keyId, shareId)` scoped to `shareId IS NOT NULL`.

### FileVersionEntity
`id` PK, `fileId`, `seq`, `contentHash` BLOB, `blobRef` TEXT, `sizeCipher`, `keyId`, `authorId?`, `createdAt`. Unique `(fileId, seq)`. Index `(fileId, seq DESC)`.

### CrdtUpdateEntity (local log mirror / outbox)
`id` autogen, `crdtDocId`, `seq?` (null until server-assigned for outgoing), `updateEnc` BLOB, `keyId`, `authorId?`, `createdAt`, `direction` (`inbound`/`outbound`), `acked` BOOL. Backs offline catch-up and the submit outbox ([09](09-realtime-collaboration.md)).

### ShareEntity
`id` PK, target (`fileId?`/`folderId?`/`projectId?`), `kind` (`user_grant`/`link`), `granteeId?`, `linkTokenHash?` BLOB, `permission` (`read`/`write`), `createdBy`, `createdAt`, `expiresAt?`, `revokedAt?`. The **link token + fragment key are never stored** server-side; locally the app may keep a created link (token + fragment) only if the user chose to, in encrypted prefs, clearly marked.

### DeviceEntity
`id` PK, `label`, `pubkey` BLOB, `enrolledAt`, `revokedAt?`, `isThisDevice` BOOL.

### DirectoryKeyEntity (cached public keys of other users)
`userId` (or `email`), `keyId`, `x25519` BLOB, `ed25519` BLOB, `generation`, `fetchedAt`. Used to wrap shares; refresh-on-use ([13](13-sharing.md)).

### Outbox / pending operations
`PendingOpEntity`: `id`, `kind` (`structure`,`blobUpload`,`crdtSubmit`,`delete`,`keyRotate`,`shareCreate`,`shareRevoke`), `targetId`, `payloadRef`, `idempotencyKey`, `attempts`, `nextAttemptAt`, `lastError?`. Drives reliable, retryable, idempotent sync ([05 §5.5](05-api-client.md),[08](08-sync-engine.md)).

### SyncCursorEntity
`scope` (e.g. `project:{id}` or `global`) PK, `cursor` TEXT, `updatedAt`. Stores the opaque delta-sync cursor ([08 §8.3](08-sync-engine.md)).

### AccountEntity / SessionEntity
The owning account of *this* database: `userId`, `keycloakSub`, `displayName`, `email`, `role`, `instanceHost`, token-expiry hints (tokens themselves are in secret storage, [14](14-authentication.md)). Because each account has its own DB, this is effectively a single row identifying the tenant; the cross-account list lives in the separate account registry ([§4.1](#41-storage-engine--at-rest-encryption)).

## 4.3 Full-text search (FTS5)

A contentless/external-content **FTS5** virtual table `FileContentFts(fileId UNINDEXED, title, body)` indexes decrypted titles and text content of files present in the local subset ([11](11-search.md)). Maintained incrementally by `data-search` as content is decrypted/updated; never uploaded. Ink files index their recognized/typed text only if/when handwriting recognition exists (not v1.0.0) — otherwise only their title.

## 4.4 Sync state

`FileEntity.syncState` is a persisted enum driving badges and the engine ([08](08-sync-engine.md)):

`Synced` · `PendingPush` · `PendingPull` · `Downloading` · `Uploading` · `Conflicted` (LWW loser retained) · `Rotating` (key rotation in progress) · `Excluded` · `Error(code)`.

## 4.5 Migrations & schema versioning

- Room schema is **versioned**; export schemas to `core-database/schemas` and assert migrations in instrumented tests ([18](18-build-ci-testing.md)).
- Forward-only migrations, matching the server's forward-only discipline.
- The DB stores a `schemaMeta` row with app version + crypto-frame version expectations so a client can detect and refuse to misread newer encrypted frames ([06 §6.3](06-cryptography.md)).

## 4.6 What is **not** stored

- No server-side search index (none exists).
- No plaintext outside the encrypted DB / Keystore-gated blob cache.
- No identity private key or recovery key in Room (they live in `core-keystore`, [07](07-key-and-device-management.md)).
- No bearer/refresh tokens in Room (secret storage, [14](14-authentication.md)).
