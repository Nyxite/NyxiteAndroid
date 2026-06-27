# 06 — Cryptography

This is the most consequential module (`core-crypto`). It must be **bit-compatible with the server's framing and the other clients' implementations** (Yjs/web, ydotnet/desktop) — a mismatch means data that round-trips on one client is unreadable on another. Everything here mirrors [server 07](https://github.com/Nyxite/server).

## 6.1 Posture

The Android device is a full cryptographic peer. It generates file keys, encrypts/decrypts all content, wraps/unwraps keys, computes content addresses, signs and verifies. The server is blind: it stores ciphertext, wrapped keys, public keys, opaque IDs, sizes, timestamps — and nothing that lets it read content.

## 6.2 Algorithm table (must match server exactly)

| Purpose | Algorithm | Android mapping |
|---------|-----------|-----------------|
| Content / CRDT / snapshot encryption | **AES-256-GCM** (96-bit nonce, 128-bit tag) | Tink `AesGcm` / JCE `AES/GCM/NoPadding` |
| Public-key wrap (file-key to a member, device enrollment to a device pubkey) | **HPKE**: DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM — RFC 9180 IDs `KEM=0x0020`, `KDF=0x0001`, `AEAD=0x0002` | Tink HPKE (`HybridEncrypt`/`HybridDecrypt`) — **verify suite IDs** ([§6.4](#64-hpke-wrapping)) |
| Symmetric wrap (recovery blob) | **AES-256-GCM** under an Argon2id-derived key | JCE/Tink AES-GCM ([§6.4](#64-hpke-wrapping), [07 §7.4](07-key-and-device-management.md)) |
| Identity key agreement | **X25519** | Tink / `XDH` |
| Signing (updates, key-directory entries) | **Ed25519** | Tink `PublicKeySign`/`PublicKeyVerify` |
| Recovery-key derivation | **Argon2id → wrapping key** (m = 64 MiB, t = 3, p = 1; tunable, stored in `recovery_blobs.kdf_params`) | argon2-jvm |
| Plaintext hashing (content address) | **BLAKE3-256** | BLAKE3 JVM lib |

These values are **pinned to the server's canonical ledger** and must be byte-identical across clients; the client tracks the server's `07` and fails CI on any drift ([18 §18.5](18-build-ci-testing.md)).

**System rule — HPKE vs AES-256-GCM**: use **HPKE wherever the target is a public key** (file-key wrap to members, device enrollment to a device pubkey); use **AES-256-GCM wherever the key is symmetric** (all content + the recovery blob).

## 6.3 Encrypted object framing

Every ciphertext object (blob, snapshot, CRDT update, encrypted name/title, encrypted settings/metadata) uses the shared frame:

```
magic(4) | version(1) | key_id(16) | nonce(12) | ciphertext(...) | gcm_tag(16)
```

- `magic` — fixed 4-byte Nyxite identifier: ASCII **`"NYXC"`** (`0x4E 0x59 0x58 0x43`).
- `version` — 1-byte frame/crypto-agility version, currently **`0x01`**; the client refuses to *misread* a newer version it doesn't understand and surfaces an "update required" state.
- `key_id` — 16-byte identifier (uuid) of the **specific file key** used. The server cannot resolve it to a usable key; the client maps it to a local FK handle. `key_id` is 1:1 with the integer `keyGeneration` rotation counter ([04 §4.2](04-local-data-model.md)) and is the stable frame/crdt/version reference.
- `nonce` — 96-bit, **unique per (key, message)**. Use a CSPRNG; never reuse a nonce under the same key (a hard invariant for GCM). For high-volume CRDT updates under one FK, ensure nonce uniqueness via random 96-bit nonces (collision-negligible) — documented and tested.
- **AAD** = `magic ‖ version ‖ key_id ‖ file_id(16) ‖ object_kind(1)`, binding the frame to its file and object kind. Decryption supplies the same AAD; a mismatch fails authentication, preventing cross-context reuse of ciphertext. The `object_kind` enum (1 byte): `blob = 1`, `snapshot = 2`, `crdt = 3`, `name = 4`, `metadata = 5`, `awareness = 6`, `settings = 7`.

`CryptoEngine` exposes:
```kotlin
fun seal(plaintext: ByteArray, fk: FileKeyHandle, kind: ObjectKind, fileId: Uuid): ByteArray   // → frame
fun open(frame: ByteArray, fk: FileKeyHandle, kind: ObjectKind, fileId: Uuid): ByteArray        // → plaintext
```

## 6.4 HPKE wrapping (account shares & key recovery)

> Naming note: this section also covers the **symmetric** recovery wrap (AES-256-GCM); the rule is HPKE for public-key targets, AES-256-GCM for symmetric keys (§6.2).

- To share a file to a user (account share), wrap an FK to the owner, or enroll a new device, the client fetches the recipient's **X25519 public key** from the directory ([13](13-sharing.md)) and HPKE-seals the payload to it. The result is the `wrapped_key`/`wrappedIdentityKey` blob stored server-side; only the recipient's device can `HybridDecrypt` it with the identity private key.
- HPKE **suite must equal DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM** — RFC 9180 IDs `KEM=0x0020`, `KDF=0x0001`, `AEAD=0x0002`. Tink's HPKE templates must be configured to exactly this KEM/KDF/AEAD; a **conformance test wraps with Android-Tink and unwraps with the server's/desktop's HPKE (and vice-versa)** using fixed vectors ([18](18-build-ci-testing.md)). If Tink's defaults differ, configure explicit `HpkeParameters`.
- **Recovery escrow uses AES-256-GCM, not HPKE** (DECISION — resolves the prior HPKE-vs-AES indecision). The recovery blob is the identity bundle (X25519 priv ‖ Ed25519 priv) sealed with **AES-256-GCM under the Argon2id-derived wrapping key** ([07 §7.4](07-key-and-device-management.md)). This follows the system rule (§6.2): HPKE only where the target is a public key; AES-256-GCM where the key is symmetric.

## 6.5 File keys

- An FK is a **256-bit AES-GCM key generated by a CSPRNG** on the device when a file is created.
- It encrypts that file's bytes, CRDT updates, snapshots, and encrypted name. It is **never sent unwrapped**; it is stored on the server only as `wrapped_key` rows (one per member) and/or carried in a link fragment.
- **No convergent encryption** — the FK is random, not derived from content (resists confirmation-of-file attacks). Intra-file dedup still works because identical plaintext under the same FK yields identical ciphertext at the same content address.
- In memory, an unwrapped FK is held as a `FileKeyHandle` (ideally a Keystore-wrapped or zeroizable buffer); it is never persisted in plaintext ([04 §4.2](04-local-data-model.md), [17](17-security.md)).

## 6.6 Content addressing

- `address = BLAKE3-256(plaintext)`, computed **on the client**. The ciphertext (framed) is stored under that address; the server treats the address as opaque and enforces write-once.
- On download, the client decrypts, recomputes BLAKE3 over the plaintext, and **verifies it matches the claimed address** before trusting the blob (defends against a tampering/withholding relay returning the wrong bytes).
- BLAKE3 is chosen for speed on large blobs (ink/binary, snapshots); benchmark the chosen JVM lib on arm64 and stream-hash large inputs to bound memory ([16](16-offline-and-storage-policies.md)).

## 6.7 Signing & verifying (tamper detection)

- The client **signs** its key-directory entry (its published X25519/Ed25519 public keys) and, where the protocol calls for it, CRDT updates / share grants with **Ed25519**, so peers can detect a relay that swaps keys or injects updates.
- The client **verifies** Ed25519 signatures on directory entries it fetches before wrapping a share to them, and on relayed updates where signed. (Full key-transparency/safety-numbers is deferred to Phase 6, [13 §13.6](13-sharing.md); v1.0.0 trust is TLS + signature checks.)

## 6.8 Recovery-key derivation

- The recovery key is a high-entropy phrase shown once. The wrapping key is derived via **Argon2id** with parameters **m = 64 MiB, t = 3, p = 1** (tunable; the actual values are stored non-secret in `recovery_blobs.kdf_params`). The client reads those params for unwrap and uses them when (re)generating the escrow so any device can recover ([07 §7.4](07-key-and-device-management.md)).
- The derived key then seals the escrow with **AES-256-GCM** (not HPKE — symmetric key, per §6.4). The recovery-blob shape is `{ version, kdf:{alg:"argon2id", m, t, p, salt(16)}, nonce(12), ciphertext, tag(16) }` with **AAD = `userId ‖ version`** ([07 §7.4](07-key-and-device-management.md)).
- Memory-hard parameters stay tuned for mobile (a hardware spike confirms the chosen `(m, t, p)` complete in a few seconds on mid-range devices without OOM, recorded in `kdf_params`).

## 6.9 Implementation rules

- **One code path** for framing (`seal`/`open`) used by every content type and every transport (REST blob, relay update, snapshot, name, settings). No ad-hoc encryption elsewhere.
- **No homemade crypto.** Only the vetted libraries in [02 §2.7](02-tech-stack-and-libraries.md), behind the `CryptoEngine` interface for agility.
- **Constant-time** comparisons for tags/tokens (use library primitives).
- **Zeroize** key material buffers after use where the JVM allows; prefer Keystore-backed handles for long-lived keys ([07](07-key-and-device-management.md)).
- **Known-answer tests** for AES-GCM framing, HPKE wrap/unwrap, Ed25519, X25519, BLAKE3, and Argon2id, plus **cross-client conformance vectors** shared with server/desktop/web ([18 §18.5](18-build-ci-testing.md)). This is the single most important test surface in the app.
