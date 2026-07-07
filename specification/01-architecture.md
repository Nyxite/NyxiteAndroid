# 01 — Architecture

## 1.1 Shape: offline-first, layered, unidirectional

The app is **offline-first**: the local encrypted database is the source of truth the UI renders from; the network is a background reconciler. The user can read, edit, draw, organize, and search with no connectivity; changes queue and sync when the relay/REST is reachable.

It follows a **Clean-architecture layering** with a **unidirectional data flow (MVI)** in the presentation layer:

```
┌──────────────────────────────────────────────────────────────────┐
│ Presentation (Jetpack Compose, Material 3)                         │
│   Screens ── ViewModel (MVI: State + Intent + Effect) ── Navigation│
└───────────────▲───────────────────────────────────────┬──────────┘
                │ StateFlow<UiState>          Intent      │
┌───────────────┴───────────────────────────────────────▼──────────┐
│ Domain (pure Kotlin, no Android/IO deps)                          │
│   Use cases ·  Entities ·  Repository interfaces · policy logic   │
└───────────────▲───────────────────────────────────────┬──────────┘
                │                                         │
┌───────────────┴───────────────────────────────────────▼──────────┐
│ Data                                                              │
│  Repositories (impl) ── orchestrate:                              │
│   • LocalStore (Room/SQLCipher)   ← single source of truth        │
│   • ApiClient (Retrofit/OkHttp)   ← REST over ciphertext          │
│   • RelayClient (SignalR)         ← encrypted CRDT relay          │
│   • CryptoEngine (Tink + hybrid-PQC lib + BLAKE3 + Argon2) ← all encryption │
│   • CrdtEngine (yrs via UniFFI)   ← text merge                    │
│   • KeyStoreVault (Android Keystore/StrongBox) ← key material     │
│   • BlobCache (filesystem)        ← cached ciphertext/plaintext   │
└──────────────────────────────────────────────────────────────────┘
```

The **domain layer is platform-free** so it can be unit-tested on the JVM and, if desired later, shared via Kotlin Multiplatform with other clients. It mirrors the server's discipline of keeping `Domain`/`Contracts`/`Crypto` free of host concerns ([server 01](https://github.com/Nyxite/NyxiteServer)).

## 1.2 The cardinal rule: plaintext never crosses the network boundary

There is exactly one place where plaintext becomes ciphertext and vice-versa: the **CryptoEngine**, invoked by repositories. Enforced by construction:

- The `ApiClient` and `RelayClient` types accept and return **opaque byte arrays / framed blobs only** — they have no method that takes a domain object and no reference to `CryptoEngine`. They cannot serialize plaintext by accident.
- Repositories are the only components that hold both a `CryptoEngine` and a network client. Every outbound content payload passes through `CryptoEngine.seal(...)`; every inbound one through `CryptoEngine.open(...)`.
- The local DB is encrypted at rest (SQLCipher) and the blob cache is either stored as ciphertext or, for decrypted/pinned plaintext, inside app-private storage gated by the device-lock-bound master key ([17](17-security.md)).

A lint/architecture test (`konsist` or a custom `ArchUnit`-style JVM test, [18](18-build-ci-testing.md)) fails the build if a network module imports the crypto or domain-content types, or if a DTO carrying a plaintext field reaches a network client.

## 1.3 Presentation: MVI

Each screen has a `ViewModel` exposing:

- `StateFlow<XUiState>` — immutable, fully describes what to render (loading/empty/error/content, decrypted names/content already resolved).
- `fun onIntent(intent: XIntent)` — the only mutation entry point (e.g. `OpenFile`, `EditText`, `StrokeAdded`, `PinLocal`, `CreateShareLink`).
- `Flow<XEffect>` (channel) — one-shot side effects (navigation, snackbars, share-sheet, biometric prompt).

ViewModels depend on **use cases**, never on data sources directly. Decryption happens in the data layer; the UI receives already-plaintext state. State holders survive configuration changes; ink editing state that is expensive to rebuild is kept in the ViewModel and/or a `rememberSaveable` snapshot.

## 1.4 Domain: use cases & repository interfaces

Representative use cases (each a single-responsibility class with an `operator fun invoke`):

- Structure: `ObserveProjectTree`, `CreateFolder`, `MoveFile`, `RenameFile`, `SetSyncPolicy`, `SoftDeleteFile`.
- Content: `OpenFileForRead`, `OpenFileForEdit`, `ApplyTextEdit`, `AppendInkStroke`, `SaveInkPage`.
- Sync: `RunDeltaSync`, `PullManifest`, `DownloadBlob`, `UploadBlob`, `ReconcileFile`.
- Collaboration: `JoinDocument`, `SubmitUpdate`, `BroadcastAwareness`, `LeaveDocument`, `SnapshotDocument`.
- Keys/devices: `EnrollDevice`, `ApproveDevice`, `GenerateRecoveryKey`, `RecoverFromKey`, `RotateFileKey`, `PublishPublicKeys`.
- Sharing: `CreateAccountShare`, `CreateLinkShare`, `OpenShareLink`, `RevokeShare`, `ListShares`.
- History/search: `ListVersions`, `DiffVersions`, `RestoreVersion`, `Search`, `Reindex`.

Repository interfaces (`FileRepository`, `StructureRepository`, `KeyRepository`, `ShareRepository`, `SyncRepository`, `CollabRepository`, `SearchRepository`, `VersionRepository`) live in domain; implementations live in data.

## 1.5 Data layer components

| Component | Responsibility | Backed by |
|-----------|----------------|-----------|
| `LocalStore` | Single source of truth for structure, sync state, cached plaintext metadata, FTS index | Room over SQLCipher ([04](04-local-data-model.md)) |
| `ApiClient` | Typed REST over `/api/v1`, ciphertext bodies, problem+json errors, idempotency | Retrofit + OkHttp + kotlinx.serialization ([05](05-api-client.md)) |
| `RelayClient` | SignalR `RelayHub` connection, join/submit/awareness, reconnect | microsoft-signalr Java client ([09](09-realtime-collaboration.md)) |
| `CryptoEngine` | seal/open framed objects, hybrid HPKE wrap/unwrap, hybrid sign/verify, content address, recovery KDF | Tink (AES-GCM + classical halves) + hybrid-PQC lib (ML-KEM-768 / ML-DSA-65, [02 §2.7](02-tech-stack-and-libraries.md)) + BLAKE3 + Argon2 ([06](06-cryptography.md)) |
| `CrdtEngine` | Apply/encode Yrs updates, state vectors, snapshots | yrs via UniFFI ([09](09-realtime-collaboration.md)) |
| `KeyStoreVault` | Wrap/unwrap the DB master key & identity-key store under a Keystore-held key | Android Keystore / StrongBox ([07](07-key-and-device-management.md)) |
| `BlobCache` | Store/evict cached ciphertext and decrypted blobs (ink/binary) | App-private filesystem ([16](16-offline-and-storage-policies.md)) |
| `AuthManager` | the server's access/refresh tokens (native login by default; AppAuth on the enterprise OIDC path), refresh, share-token minting | Credential Manager / direct API; AppAuth (enterprise) ([14](14-authentication.md)) |

## 1.6 Background work & threading

- **Threading**: Kotlin coroutines + `Flow`. A small set of dispatchers via an injected `DispatcherProvider`: `Main` (UI), `Default` (CRDT merge, diff, crypto-bound CPU), `IO` (DB, network, file). Crypto on `Default`; never on `Main`.
- **Active editing/collaboration**: while a document is open, a **foreground service** (`CollabService`, type `dataSync`) maintains the SignalR connection and shows a low-priority notification, so the relay survives brief backgrounding.
- **Periodic/opportunistic sync**: `WorkManager` jobs — `DeltaSyncWorker` (constraint: network; periodic + expedited on app foreground), `BlobPrefetchWorker` (keep-on-device content; constraint: unmetered/charging configurable), `ReindexWorker`, `KeyRotationWorker`. See [08](08-sync-engine.md) and [16](16-offline-and-storage-policies.md).
- **Snapshotting**: a client trigger (N updates / time / graceful leave) enqueues `SnapshotWorker` to compact the local Yrs doc into an encrypted snapshot and upload it ([09 §5.6](09-realtime-collaboration.md), [12](12-version-history.md)).

## 1.7 Error & connectivity model

- The UI always renders from `LocalStore`; network failures degrade gracefully to "offline / pending changes N".
- A per-file **sync state machine** — canonical set `Synced`, `PendingPush`, `PendingPull`, `Downloading`, `Uploading`, `Conflicted`, `Rotating`, `Excluded`, `Error(code)` (identical to [04 §4.4](04-local-data-model.md) and [08 §8.8](08-sync-engine.md)) — is persisted and surfaced as small badges ([08](08-sync-engine.md)).
- Server `problem+json` codes map to typed `NyxiteApiError` (e.g. `key_generation_stale` → trigger refetch+rotate, `share_revoked`/`link_expired` → mark dead, `excluded_content` → never retry upload, `429` → backoff with `Retry-After`). See [05 §5.4](05-api-client.md).

## 1.8 Dependency injection

**Hilt** wires the graph. Scopes: `@Singleton` for stateless engines and process-wide infrastructure; `@ViewModelScoped` for per-screen state; and an **account-scoped component** (custom Hilt component keyed by `accountId`) for everything tenant-specific.

Because the app is **multi-account from v1.0.0** ([14 §14.7](14-authentication.md)), per-account state must not leak across accounts. The account-scoped component owns that account's **`UserSession`** (the unlocked identity-key handle, created after login + key-unlock and cleared on lock/logout/switch), its server tokens, its repositories, and its account-scoped data sources (the account's SQLCipher DB, `BlobCache` subtree, FTS index). The active account's component is created on switch-in and torn down — zeroizing in-memory key material — on switch-out. No use case can touch decrypted key material before unlock, and no account can read another's data. See [04 §4.2](04-local-data-model.md), [07](07-key-and-device-management.md), and [17](17-security.md).
