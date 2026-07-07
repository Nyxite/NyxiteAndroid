# 07 — Key & Device Management

Covers the identity keypair, device enrollment, the recovery key, FK rotation, and how all of this is protected on-device by the Android Keystore/StrongBox. This is the Phase-0 foundation; nothing later retrofits it.

## 7.1 Key hierarchy (client view)

```
Android Keystore / StrongBox key (hardware-backed, non-exportable)
   └─ wraps → DB master key (SQLCipher) and the Identity Key Store
                 └─ holds → Identity private keys: hybrid X25519+ML-KEM-768 (HPKE) + hybrid Ed25519+ML-DSA-65 (sign)
                              └─ unwraps → File Keys (FK, per file)
Recovery key (user-held phrase) ──Argon2id──► wrapping key ──► escrow of identity private key (server-opaque)
Device key (per device) ──► used to approve/enroll new devices
```

The Keystore key is the on-device root of trust: it is generated in hardware, never leaves it, and (optionally) requires user authentication (biometric/device credential) to use ([17](17-security.md)). Everything sensitive at rest is wrapped under it.

## 7.2 On-device key storage (`core-keystore`)

- Generate an **`AndroidKeyStore`** AES key (`setIsStrongBoxBacked(true)` when `FEATURE_STRONGBOX_KEYSTORE` is present; fall back gracefully). Use it to wrap:
  - the **DB master key** (SQLCipher passphrase),
  - the **Identity Key Store** blob (the hybrid private keys — X25519+ML-KEM-768 for HPKE and Ed25519+ML-DSA-65 for signing — themselves serialized and AES-GCM-encrypted).
- Configure `setUserAuthenticationRequired` **per the user's security setting; default on with a 10-minute validity window** (configurable 1–60 min, or per-use auth via `BiometricPrompt` → `CryptoObject`) for the unlock operation ([17](17-security.md), [19 §19.2](19-open-questions.md)).
- The unlocked identity private keys live only in the in-memory `UserSession` ([01 §1.8](01-architecture.md)); on lock/logout the session is cleared and buffers zeroized.
- **StrongBox caveat**: StrongBox has small key/throughput limits — use it for the wrapping key only, not for bulk content; do bulk AES-GCM in software with FK material in memory.

## 7.3 Device enrollment

### First device / first sign-in
1. User authenticates with the Nyxite server (native password+TOTP or passkey by default; enterprise Keycloak OIDC optional) → access token ([14](14-authentication.md)).
2. App checks the key directory (`GET /keys/directory?userId=me`): if the user has **no identity keypair yet** (brand-new account), generate the **hybrid keypairs (X25519+ML-KEM-768 for HPKE, Ed25519+ML-DSA-65 for signing)**, store privates in the Keystore-wrapped identity store, and **publish publics** via `PUT /keys`.
3. Generate a **device keypair**; register the device via `POST /devices { label, pubkey }` → `{ deviceId, status: "pending", pairingCode, qrPayload }`.
4. Generate the **recovery key** and escrow ([§7.4](#74-recovery-key)). Force the user through the recovery-key flow before completing setup.

### Additional device (user already has an identity keypair elsewhere)
This device has the account token but **not** the identity private key. Two paths:

- **(A) Device-to-device approval.** New device registers (`POST /devices { label, pubkey }` → `status: "pending"` + `pairingCode`/`qrPayload`) and displays the QR (primary) + 6–8 digit numeric code (fallback). An already-enrolled device approves via `POST /devices/{id}/approve { wrappedIdentityKey }`, where `wrappedIdentityKey = HPKE-seal(newDevicePubkey, identity bundle)` under the **hybrid X25519+ML-KEM-768 HPKE** suite; the server relays the opaque blob. The new device fetches it once via `GET /devices/me/enrollment` → `{ wrappedIdentityKey }` (**single-use**; the server deletes the blob after the fetch) and unwraps with its device private key. Interactive confirmation required on the enrolled device.
- **(B) Recovery-key unwrap.** User enters the recovery phrase; the app fetches the escrow blob (`GET /recovery` + `kdf_params`), derives the wrapping key (Argon2id), unwraps the identity private key, then stores it Keystore-wrapped and proceeds. This is the path when no other device is available.

Until enrolled with the identity private key, the device can authenticate and see structure (encrypted) but **cannot decrypt content**; the UI shows a clear "enroll this device" state.

## 7.4 Recovery key

- Generated as a **BIP39 24-word phrase** (256-bit, checksummed, vetted word list) **shown once**. The app must make the user record it (copy, write down, confirm by re-entry) and warn unmistakably: **losing all devices and this phrase = permanent, unrecoverable data loss** (no server/admin escrow exists).
- The app derives a wrapping key via **Argon2id** (m = 64 MiB, t = 3, p = 1, tunable, tuned on hardware — [06 §6.8](06-cryptography.md); symmetric and **un-peppered**, no PQC dimension) and **seals the hybrid identity bundle (X25519 priv ‖ ML-KEM-768 priv ‖ Ed25519 priv ‖ ML-DSA-65 priv) with AES-256-GCM** under that derived key (DECISION: AES-GCM, **not** HPKE — symmetric key, [06 §6.4](06-cryptography.md)). The recovery-blob shape is `{ version, kdf:{alg:"argon2id", m, t, p, salt(16)}, nonce(12), ciphertext, tag(16) }` with **AAD = `userId ‖ version`**. It uploads the **opaque** escrow + non-secret `kdf_params` via `PUT /recovery`. The server stores it but cannot open it.
- Re-issuing a recovery key (rotation) re-wraps the escrow under a new phrase and re-uploads; old phrase invalidated client-side by overwrite.
- The recovery key is **never** stored on the device in plaintext; if the user opts to keep a copy, it is their responsibility outside the app.

## 7.5 File-key lifecycle

- **Create**: generate FK (CSPRNG), encrypt initial content, wrap FK to the owner's public key, `POST /files` with `OwnerWrappedKey` + initial ciphertext ([05](05-api-client.md)).
- **Open**: `GET /files/{id}/keys` → the caller's `wrapped_key`; HPKE-unwrap with the identity private key → `FileKeyHandle`; cache the wrapped form locally, hold the unwrapped handle in memory only ([04 §4.2](04-local-data-model.md)).
- **Generation check**: every content frame carries `key_id` (FK + generation). If the server returns `412 key_generation_stale`, refetch keys and re-encrypt with the current generation ([05 §5.4](05-api-client.md)).

## 7.6 Key rotation & revocation (client-driven forward secrecy)

When a share is revoked ([13](13-sharing.md)), a remaining member's client must rotate the FK so removed members cannot read **future** content:

1. Generate a new FK (new `keyId`, `generation + 1`).
2. Re-encrypt the current head (and produce a fresh snapshot) under the new FK.
3. Re-wrap the new FK to every **remaining** member's public key.
4. `POST /files/{id}/keys/rotate { newKeyId, generation, wrappedKeys[], newHeadRef }`; the server bumps `key_generation`. If another member's rotation already won, the server returns **`409`** — abandon this attempt and refetch the new generation.
5. Peers see the `keyrotate` change in delta sync ([08](08-sync-engine.md)) and refetch their wrapped key.

The server simultaneously drops the removed member's ACL grant (instant cutoff) even before rotation completes. Already-downloaded ciphertext cannot be un-seen (inherent). Rotation runs in the background via `KeyRotationWorker`; the file shows `Rotating` state ([04 §4.4](04-local-data-model.md)).

## 7.7 Device loss / compromise

- A user can **revoke a device** (`DELETE /devices/{id}`) from another device; the revoked device's enrollment is invalidated server-side.
- If a device may be **compromised**, rotate the **identity keypair** (`PUT /keys` new generation) and re-wrap FKs in the background. This is heavier (touches every file the user holds) and is a Phase-4 admin/security action with clear UX.

## 7.8 UX requirements

- A **Security/Keys** settings area shows: this device, other enrolled devices (with revoke), recovery-key status (set / re-issue), and identity-key generation.
- Enrollment and recovery flows are first-class screens in `feature-auth` ([15](15-ui-and-navigation.md)), with explicit, non-dismissable warnings about the no-escrow recovery model.
- Approving a new device requires an explicit confirmation gesture and shows the new device's label + fingerprint.

## 7.9 Ratified decisions & remaining validation

Decisions (see [19 §19.0](19-open-questions.md)): **StrongBox when present**, else TEE-backed Keystore; **biometric/credential unlock with a 10-minute validity window** (configurable 1–60 min, or every-use), so background content sync works within the window while structure sync always runs ([19 §19.2](19-open-questions.md), [16 §16.4](16-offline-and-storage-policies.md)); **Argon2id m = 64 MiB, t = 3, p = 1**; **BIP39 24-word** recovery phrase with scrubbed re-entry; **device-to-device approval via QR (primary) + numeric code (fallback)** showing the new device's label + fingerprint.

Remaining validation (hardware spike, [19 §19.2/§19.3](19-open-questions.md)): StrongBox availability/limits across target devices; biometric validity-window behavior across OEMs straddling WorkManager runs; final Argon2id tuning on a low/mid/high device trio.

## 7.10 Group keys (enterprise/family groups)

Groups add a **group keypair** between the identity key and the FK ([06 §6.10](06-cryptography.md), [13 §13.7](13-sharing.md), [features/groups.md](https://github.com/Nyxite/Nyxite)). The client is the only party that ever holds a group private key in the clear; the server stores opaque grant blobs + membership rows only (P4.4-AND-1/2).

### Group keygen & scope
- Creating a group generates a **hybrid group keypair (X25519+ML-KEM-768 + Ed25519+ML-DSA-65) on-device** (`core-crypto`), publishes the **public** halves, and self-signs the directory entry (hybrid Ed25519+ML-DSA-65) like an identity entry ([06 §6.7](06-cryptography.md)). The private half is never uploaded unwrapped.
- Keys are **scoped** per project / time-period and **flat** (no nesting): a file wraps to the group key **of its scope**, so removing a member re-wraps only the affected scope, never the group's whole history.

### Transparency-verified enrollment
- Adding a member is **O(1)**: fetch the newcomer's identity **public key**, then **verify it against the key-transparency log (build Phase 4.3)** *before* wrapping. Because one substituted key would expose the whole group's corpus (not a single file), group enrollment does **not** rely on the TLS + hybrid-Ed25519+ML-DSA-65-self-signature model alone that per-file account shares use in v1.0.0 ([13 §13.6](13-sharing.md)) — it **depends on** key transparency, which is pulled into v1.0.0 as build Phase 4.3 ahead of groups (Phase 4.4). A directory-substituted key fails the inclusion proof and enrollment is rejected **before any wrap**.
- On success the client HPKE-wraps the **group private key** to the verified public key and writes **one** append-only `group-key grant` blob (`group_id | scope_id | member_id | generation | alg_id | hpke_ct`, [06 §6.10](06-cryptography.md)). No file is touched; the one grant instantly grants every file the group's scope can read.

### Group-key grant handling
- **Open**: `GET` the caller's group-key grant → HPKE-unwrap the group private key with the identity private key → cache the wrapped form locally, hold the unwrapped group key in the in-memory `UserSession` only ([01 §1.8](01-architecture.md)), never persisted in plaintext — then unwrap file DEKs ([06 §6.10](06-cryptography.md)).
- **Grant one file to a group**: HPKE-wrap that file's FK to the group public key; one wrapped-key row. Individual one-off shares still go the account-share path ([13 §13.1](13-sharing.md)); the two **coexist**.

### `GroupKeyRotationWorker` (member removal)
When a member is removed, a remaining member's client rotates the **affected scope's** group key so the removed member reads nothing new — the Phase-2.3 rotation machinery ([§7.6](#76-key-rotation--revocation-client-driven-forward-secrecy)) applied at the group-key level, run in the background via **`GroupKeyRotationWorker`** (WorkManager, sibling to `KeyRotationWorker`):
1. Generate a new group keypair for the scope (`generation + 1`); re-wrap the new group private key to every **remaining** member (one grant blob each).
2. **Optional DEK re-seal** (full/"seal history" revocation): re-wrap the affected scope's file DEKs under the new group key — re-wraps only the small DEKs, never the file ciphertext.
3. `POST /groups/{id}/keys/rotate`; the server generation-guards per scope. A **concurrent rotation loser gets `409`** — abandon and refetch the new generation; an **in-flight old-key wrap gets `412 key_generation_stale`** — refetch and re-seal under the current generation.
- The server drops the removed member's grant instantly (soft delete, one delete) even before rotation completes; already-decrypted content can't be recalled (surfaced honestly in the UI, [13 §13.7](13-sharing.md)). Only the affected scope is touched.

### Recovery restores group access
- Because each group private key is wrapped under the member's **personal** public key, **recovering the identity key** (recovery phrase, [§7.4](#74-recovery-key)) automatically restores all group access on the new device — the group grants simply unwrap under the recovered personal key, **no special path**.
- A member who loses **all** devices **and** the recovery phrase must be **re-enrolled by a group admin** (one new grant re-wrapping the group key to their new public key), exactly like initial enrollment.
