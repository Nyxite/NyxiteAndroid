# 07 — Key & Device Management

Covers the identity keypair, device enrollment, the recovery key, FK rotation, and how all of this is protected on-device by the Android Keystore/StrongBox. This is the Phase-0 foundation; nothing later retrofits it.

## 7.1 Key hierarchy (client view)

```
Android Keystore / StrongBox key (hardware-backed, non-exportable)
   └─ wraps → DB master key (SQLCipher) and the Identity Key Store
                 └─ holds → Identity private keys: X25519 (HPKE) + Ed25519 (sign)
                              └─ unwraps → File Keys (FK, per file)
Recovery key (user-held phrase) ──Argon2id──► wrapping key ──► escrow of identity private key (server-opaque)
Device key (per device) ──► used to approve/enroll new devices
```

The Keystore key is the on-device root of trust: it is generated in hardware, never leaves it, and (optionally) requires user authentication (biometric/device credential) to use ([17](17-security.md)). Everything sensitive at rest is wrapped under it.

## 7.2 On-device key storage (`core-keystore`)

- Generate an **`AndroidKeyStore`** AES key (`setIsStrongBoxBacked(true)` when `FEATURE_STRONGBOX_KEYSTORE` is present; fall back gracefully). Use it to wrap:
  - the **DB master key** (SQLCipher passphrase),
  - the **Identity Key Store** blob (the X25519+Ed25519 private keys, themselves serialized and AES-GCM-encrypted).
- Configure `setUserAuthenticationRequired` **per the user's security setting; default on with a 10-minute validity window** (configurable 1–60 min, or per-use auth via `BiometricPrompt` → `CryptoObject`) for the unlock operation ([17](17-security.md), [19 §19.2](19-open-questions.md)).
- The unlocked identity private keys live only in the in-memory `UserSession` ([01 §1.8](01-architecture.md)); on lock/logout the session is cleared and buffers zeroized.
- **StrongBox caveat**: StrongBox has small key/throughput limits — use it for the wrapping key only, not for bulk content; do bulk AES-GCM in software with FK material in memory.

## 7.3 Device enrollment

### First device / first sign-in
1. User authenticates with the Nyxite server (native password+TOTP or passkey by default; enterprise Keycloak OIDC optional) → access token ([14](14-authentication.md)).
2. App checks the key directory (`GET /keys/directory?userId=me`): if the user has **no identity keypair yet** (brand-new account), generate X25519 + Ed25519, store privates in the Keystore-wrapped identity store, and **publish publics** via `PUT /keys`.
3. Generate a **device keypair**; register the device via `POST /devices { label, pubkey }` → `{ deviceId, status: "pending", pairingCode, qrPayload }`.
4. Generate the **recovery key** and escrow ([§7.4](#74-recovery-key)). Force the user through the recovery-key flow before completing setup.

### Additional device (user already has an identity keypair elsewhere)
This device has the account token but **not** the identity private key. Two paths:

- **(A) Device-to-device approval.** New device registers (`POST /devices { label, pubkey }` → `status: "pending"` + `pairingCode`/`qrPayload`) and displays the QR (primary) + 6–8 digit numeric code (fallback). An already-enrolled device approves via `POST /devices/{id}/approve { wrappedIdentityKey }`, where `wrappedIdentityKey = HPKE-seal(newDevicePubkey, identity bundle)`; the server relays the opaque blob. The new device fetches it once via `GET /devices/me/enrollment` → `{ wrappedIdentityKey }` (**single-use**; the server deletes the blob after the fetch) and unwraps with its device private key. Interactive confirmation required on the enrolled device.
- **(B) Recovery-key unwrap.** User enters the recovery phrase; the app fetches the escrow blob (`GET /recovery` + `kdf_params`), derives the wrapping key (Argon2id), unwraps the identity private key, then stores it Keystore-wrapped and proceeds. This is the path when no other device is available.

Until enrolled with the identity private key, the device can authenticate and see structure (encrypted) but **cannot decrypt content**; the UI shows a clear "enroll this device" state.

## 7.4 Recovery key

- Generated as a **BIP39 24-word phrase** (256-bit, checksummed, vetted word list) **shown once**. The app must make the user record it (copy, write down, confirm by re-entry) and warn unmistakably: **losing all devices and this phrase = permanent, unrecoverable data loss** (no server/admin escrow exists).
- The app derives a wrapping key via **Argon2id** (m = 64 MiB, t = 3, p = 1, tunable, tuned on hardware — [06 §6.8](06-cryptography.md)) and **seals the identity bundle (X25519 priv ‖ Ed25519 priv) with AES-256-GCM** under that derived key (DECISION: AES-GCM, **not** HPKE — symmetric key, [06 §6.4](06-cryptography.md)). The recovery-blob shape is `{ version, kdf:{alg:"argon2id", m, t, p, salt(16)}, nonce(12), ciphertext, tag(16) }` with **AAD = `userId ‖ version`**. It uploads the **opaque** escrow + non-secret `kdf_params` via `PUT /recovery`. The server stores it but cannot open it.
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
