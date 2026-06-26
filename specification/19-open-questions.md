# 19 — Open Questions & Recommended Resolutions (Android)

This document analyzes each Android-specific open item and gives a **recommended resolution + how to implement/validate + a fallback**, so the team can ratify and move. Items that are genuinely the **server's** to decide remain "track & confirm" ([§19.6](#196-server-owned-protocol-items-track--confirm)). Project-wide decisions stay canonical in the master [`docs/OPEN-DECISIONS.md`](https://github.com/Nyxite/Nyxite); this file should be folded into that tracker's Android section to avoid drift.

## 19.0 Decisions already ratified (this revision)

| Item | Decision | Where applied |
|------|----------|---------------|
| `minSdk` / `targetSdk` / `compileSdk` | **36** (matches the owner's `Fitness-Tracker` baseline) | [00 §0.4](00-overview.md), [02 §2.1](02-tech-stack-and-libraries.md) |
| Multi-account | **Yes, from v1.0.0** — per-account isolated DB/keys/store/index; instance switching | [14 §14.7](14-authentication.md), [01 §1.8](01-architecture.md), [04 §4.1](04-local-data-model.md), [15 §15.1](15-ui-and-navigation.md), [16 §16.1](16-offline-and-storage-policies.md), [17 §17.2](17-security.md) |
| Application ID / root package | **`app.nyxite.android`** | [03 §3.2](03-project-structure.md) |
| Local storage model | **User-driven keep-on-device** (file/folder/project, cascading); no auto-budgeted cache; **no plaintext export on Android** (desktop feature) | [16](16-offline-and-storage-policies.md), [04 §4.2](04-local-data-model.md), [08 §8.2](08-sync-engine.md), [11](11-search.md), [15 §15.2](15-ui-and-navigation.md) |
| Text CRDT engine | **ykt**, with a **yffi wrapper as the agreed dev-time backup** if the spike fails | [09 §9.10](09-realtime-collaboration.md), [§19.1](#191-ykt--crdt-binding-maturity--needs-a-spike-clear-fallback) |
| Key lock model | **Biometric/credential with a 10-min validity window** (configurable); StrongBox when present; device-to-device approval via **QR + numeric code**; **BIP39 24-word** recovery phrase | [07](07-key-and-device-management.md), [§19.2](#192-on-device-key-storage-lock-model--enrollment-ux--recommend-a-concrete-model) |
| Argon2id params | **m = 64 MiB, t = 3, p = 1** (tune on hardware; store in `kdf_params`) | [06 §6.8](06-cryptography.md), [07 §7.4](07-key-and-device-management.md) |
| Realtime client | **Official `com.microsoft.signalr` Java client** + RxJava→coroutines bridge | [09 §9.2](09-realtime-collaboration.md), [§19.7](#197-signalr-on-android--recommend-official-client-pre-planned-fallback) |
| Ink format | **Deterministic CBOR**, co-designed with desktop, byte-stable for content addressing | [10 §10.5](10-editors.md), [§19.4](#194-ink-capture--shared-encrypted-format--recommend-format-shape-co-design-with-desktop) |
| Distribution | **Play Store + signed direct APK**; per-account instance host | [18 §18.4](18-build-ci-testing.md), [§19.8](#198-distribution--instance-configuration--recommend-both-channels) |

---

## 19.1 ykt / CRDT binding maturity — *needs a spike; clear fallback*

**Risk**: ykt is the least battle-tested Yrs binding and now carries the entire client-side merge ([09 §9.10](09-realtime-collaboration.md)). This is the top schedule risk.

**Recommendation**: run a **time-boxed spike (≤1 week) before any Phase-1 text-editing code is committed**, with a hard go/no-go gate.

**How to fix / validate**
1. Add a `core-crdt` spike module. Pull the shared Yrs wire-protocol conformance vectors ([18 §18.6](18-build-ci-testing.md)).
2. Assert: (a) byte-identical merged state vs. Yjs/ydotnet on the vectors; (b) byte-identical *encoded updates* for the same edit sequence; (c) state-vector reconstruction from snapshot + log matches.
3. Benchmark on a mid-range device: large doc (e.g. 200k chars), high-frequency edits (sustained typing + remote updates), memory ceiling.
4. Confirm packaging: NDK ABIs (`arm64-v8a`, `x86_64` for emulators), APK size impact, init cost.

**Fallback (pre-planned)**: if ykt fails interop or perf, build a thin **UniFFI (or JNI) wrapper over `yffi`** (the canonical Rust `y-crdt` FFI) exposing exactly the `CrdtEngine` surface ([09 §9.1](09-realtime-collaboration.md)). This is more work but removes binding-maturity risk by using the reference implementation. Budget for it as a contingency in the Phase-1 estimate.

**Decision gate**: ykt passes → use it; otherwise → yffi wrapper. Either way the `CrdtEngine` interface is unchanged, so the rest of the app is insulated.

---

## 19.2 On-device key storage, lock model & enrollment UX — *recommend a concrete model*

**Recommendation**

- **Hardware**: use `AndroidKeyStore` with **StrongBox when `FEATURE_STRONGBOX_KEYSTORE` is present**, falling back to TEE-backed Keystore otherwise (StrongBox *hardware* is device-dependent even at `minSdk 36`) ([07 §7.2](07-key-and-device-management.md)). StrongBox protects only the wrapping key; bulk AES-GCM runs in software with FK material in memory.
- **Lock / background-sync model** (resolves the biometric-vs-background tension): the Keystore wrapping key uses **`setUserAuthenticationRequired(true)` with a time-bound validity window** (recommended default **10 min**, user-configurable 1–60 min, or "every use" for the paranoid). Within the window, `WorkManager` content sync can unwrap FKs and run unattended. Outside it, **structure/metadata delta-sync still runs** (it needs no content key), but **content download/decrypt/index and snapshotting pause until the next unlock**. Unwrapped identity keys and FKs live only in the in-memory account `UserSession` ([01 §1.8](01-architecture.md)); they are evicted when the window lapses or the app locks.
- **Device-to-device approval transport**: **QR code as primary, 6–8 digit numeric code as fallback**; the approving device shows the new device's label + key fingerprint and requires an explicit confirm ([07 §7.3](07-key-and-device-management.md)). Avoid Nearby/BLE in v1.0.0 (extra permissions/complexity); revisit later.
- **Recovery phrase**: **BIP39 24-word** (256-bit) — familiar, checksummed, has vetted word lists and validation. Show once, require scrubbed re-entry to confirm, hard non-dismissable warning about no escrow ([07 §7.4](07-key-and-device-management.md)).

**How to validate**: a key-handling spike on real hardware covering StrongBox presence/limits, biometric validity-window behavior across OEMs, and WorkManager runs straddling the lock boundary.

**Fallback**: if validity-window-bound keys behave inconsistently across OEMs, fall back to "content sync only while the app is in the foreground/unlocked" and rely on the foreground `CollabService` window for active work.

---

## 19.3 Argon2id parameters for mobile — *recommend starting params + tune*

**Recommendation**: target a **~1–2 s derivation on a mid-range device without OOM**. Start from **m = 64 MiB, t = 3, p = 1** (Argon2id), then tune on hardware. Persist the chosen `(memory, iterations, parallelism, salt, version)` in `recovery_blobs.kdf_params` so *any* device (including weaker ones) can reproduce the derivation on recovery ([06 §6.8](06-cryptography.md), [07 §7.4](07-key-and-device-management.md)).

**How to validate**: micro-benchmark on a low/mid/high device trio; pick params meeting the time target on the **slowest** while staying well under its per-app memory budget (64 MiB is safe; avoid ≥256 MiB which risks OOM/jank on low-end). Run the derivation off the main thread with a progress indicator.

**Fallback**: if 64 MiB causes pressure on the weakest target, drop to 32 MiB with t = 4 to keep cost roughly constant.

---

## 19.4 Ink capture & shared encrypted format — *recommend format shape; co-design with desktop*

**Recommendation**: adopt **Jetpack Ink + motion-prediction + low-latency front-buffer** for capture/render ([10 §10.4](10-editors.md)), and define the storage format as a **versioned, deterministic CBOR** document, **co-designed with desktop** so files round-trip ([10 §10.5](10-editors.md)).

**Proposed format sketch (`[P]`, to ratify with desktop)**
```
InkDoc      = { v: u16, pages: [Page] }
Page        = { w, h, bg?, strokes: [Stroke] }
Stroke      = { tool, color, baseWidth, blend, samples: [Sample] }
Sample      = [ x, y, pressure, tilt, orientation, tRelMs ]   # fixed-order array, quantized ints
```
- **Determinism for content addressing**: fixed key/array ordering, fixed integer quantization (e.g. positions in 1/32 px, pressure/tilt in fixed scales), no floats with platform-variable rounding — so the same drawing serializes to the same bytes and the same BLAKE3 address on both platforms ([06 §6.6](06-cryptography.md)).
- Seal per page/save with the FK; LWW + version-vector in encrypted `metadata_enc` ([08 §8.5](08-sync-engine.md)).
- Carry a `v` field and reserve an optional **recognized-text layer** so handwriting search can be added later without a format break ([11](11-search.md)).

**How to fix**: write a short joint "Ink Format" spec owned with desktop; add round-trip + stable-address conformance tests ([18 §18.5](18-build-ci-testing.md)).

**Out of scope v1.0.0**: handwriting recognition, Samsung `.sdoc` import (separate migration item).

---

## 19.5 Local retention model & indexing — *resolved: user-driven keep-on-device*

**Decision** (per owner direction): local storage is **explicit, user-driven keep-on-device** at file/folder/project granularity with cascade — **not** an automatic budgeted cache. Plaintext export to shared storage is **out of scope on Android** (a desktop feature). See [16](16-offline-and-storage-policies.md).

**Resulting defaults (all user-overridable)**

| Knob | Default |
|------|---------|
| What's kept locally | only files the user marks **keep-on-device** (at file/folder/project, cascading) ([16 §16.2](16-offline-and-storage-policies.md)) |
| Not-kept files | fetched on open; optional **convenience cache** (default on, small optional cap) retains recently opened ones; evicting just means re-download ([16 §16.3](16-offline-and-storage-policies.md)) |
| On eviction | drop the FTS **body**, keep the **title** (still discoverable; re-fetch to read) ([11 §11.3](11-search.md)) |
| Kept-content prefetch | **Wi-Fi (unmetered) + battery-not-low**; cellular optional ([16 §16.4](16-offline-and-storage-policies.md)) |
| Index scope | titles for all known files; bodies for kept + currently-cached files only |
| Reindex | incremental on decrypt; full rebuild only on schema change/corruption |

**Still to confirm with the server**: whether `sync_policy` is **per-device or shared** across a user's devices. Keep-on-device is inherently per-device; if the server's policy field is shared, the per-device selection is tracked **client-side only** (the field then conveys only the cross-device "prefer offline" hint). Open a tracking item against the server spec ([08 §8.2](08-sync-engine.md)).

**How to validate**: measure index size/battery for a representative kept set on a mid device.

---

## 19.6 Server-owned protocol items (track & confirm)

These are **server `[P]` decisions** the client must match exactly once ratified; the "fix" is to **lock each via shared conformance vectors** ([18 §18.6](18-build-ci-testing.md)) and fail CI on any drift — not to decide them unilaterally on Android.

- Exact `magic` / frame `version` bytes and AAD construction ([06 §6.3](06-cryptography.md)).
- Final algorithm choices and **HPKE suite IDs** (must equal X25519 + HKDF-SHA256 + AES-256-GCM) ([06 §6.2](06-cryptography.md)).
- Recovery-escrow scheme (HPKE vs. AES-GCM under the Argon2id-derived key) ([07 §7.4](07-key-and-device-management.md)).
- Exact `POST /sync/changes` and `GET /sync/manifest` payload shapes and `ref` formats ([08 §8.3](08-sync-engine.md)).
- Snapshot/compaction trigger thresholds and the server prune window ([09 §9.6](09-realtime-collaboration.md)).
- Share-token session lifetime and scope ([14 §14.5](14-authentication.md)).

**Action**: open a tracking item per bullet against the server spec; add a failing-by-default conformance test for each that turns green when the server pins the value.

---

## 19.7 SignalR on Android — *recommend official client; pre-planned fallback*

**Recommendation**: use the official **`com.microsoft.signalr`** Java client wrapped behind `RelayClient`, bridging its RxJava API to coroutines (`kotlinx-coroutines-rx3`) ([09 §9.2](09-realtime-collaboration.md)).

**How to validate (spike)**: connect to the dev server's `RelayHub`; verify binary `EncryptedUpdate` payloads, group join/leave, reconnection with backoff, and **share-token upgrade for guests**; measure battery/keepalive behavior under the foreground `CollabService`.

**Fallback (pre-planned)**: if the official client is unsuitable on Android, implement a minimal raw-WebSocket client speaking the same hub wire protocol (or use the REST encrypted-update fallback more aggressively, [08 §8.4](08-sync-engine.md)).

---

## 19.8 Distribution & instance configuration — *recommend both channels*

**Recommendation**

- **Distribute via Play Store *and* signed direct APK** — the server is self-hosted, so some users sideload to reach a private instance ([18 §18.4](18-build-ci-testing.md)).
- **Instance configuration**: a setup screen takes a base host; the app derives API base (`/api/v1`), OIDC authority, and public-share base from it (overridable for non-standard layouts). Since multi-account is in v1.0.0 ([14 §14.7](14-authentication.md)), **each account stores its own instance host**, enabling personal + shared instances side by side.
- Document the Cloudflare Origin CA caveat only for admins hitting origin directly ([05 §5.1](05-api-client.md)); normal users go through the public `nyxite.app` chain.

---

## 19.9 Summary of what still needs action

| # | Item | Decided | Remaining work | Owner | Gate |
|---|------|---------|----------------|-------|------|
| 19.1 | CRDT engine | ykt; yffi backup | Validation spike (interop + perf) | Android | Before Phase 1 text editing |
| 19.2 | Key/lock/enrollment | 10-min window; QR+code; BIP39-24 | Hardware spike (StrongBox/biometric across OEMs) | Android | Phase 0 |
| 19.3 | Argon2id params | m=64 MiB, t=3, p=1 | Tune on device trio | Android | Phase 0 |
| 19.4 | Ink format | Deterministic CBOR | Joint desktop spec + round-trip tests | Android + Desktop | Before Phase 3 |
| 19.5 | Local retention | Keep-on-device (no auto-budget); no plaintext export | Confirm server `sync_policy` per-device vs shared | Android + Server | Phase 1 |
| 19.6 | Protocol `[P]` values | — | Track + conformance lock | Server → Android | Per phase |
| 19.7 | Realtime client | Official SignalR client | Spike (reconnect, guest token) | Android | Before Phase 2 |
| 19.8 | Distribution | Play + signed APK | Instance-host setup UX | Owner | Phase 4 |

Everything else from the original open list (`minSdk`, multi-account, package id) is **resolved** in [§19.0](#190-decisions-already-ratified-this-revision).
