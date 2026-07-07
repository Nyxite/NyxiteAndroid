# 00 — Overview

## 0.1 Purpose

The Nyxite Android client is a **native, end-to-end-encrypted notes and documents app**. It lets a user, from a phone or tablet:

- Browse the project → folder → file hierarchy (names decrypted on-device).
- Read and edit **markdown**, **plain text**, and **handwritten ink** files, with distinct **view** and **edit** modes.
- Sync content across devices with per-file policies, **encrypting before upload and decrypting after download** — the server only moves ciphertext.
- Collaborate live with other users and anonymous guests via a **client-side CRDT merge** over an encrypted relay.
- Search its **local subset** of decrypted content.
- Browse **version history** with client-computed diffs, and restore.
- Create and consume **share links** (key in URL fragment) and **account shares** (file key wrapped to a recipient's public key via HPKE).

It is chosen as a **native Kotlin/Compose** app (not a cross-platform wrapper) specifically for **S-Pen stylus pressure/tilt** capture and **low-latency ink** rendering, which the platform exposes natively.

## 0.2 What makes this client different from a normal sync app

Because Nyxite is zero-knowledge, the Android app carries responsibilities a typical mobile client offloads to a backend:

- It **generates and holds the user's keys**; it performs **all** AES-256-GCM encryption/decryption and HPKE wrap/unwrap locally.
- It **computes content addresses** (BLAKE3 of plaintext) itself; the server stores the ciphertext under the client-supplied address and cannot verify it.
- It **runs the CRDT engine** (yrs via UniFFI) and merges locally; the server never merges.
- It **builds and queries its own full-text search index**; there is no server search.
- It **computes diffs** between decrypted snapshots; there is no server diff.
- It **drives key rotation** on share revocation for forward secrecy.

The server gives it: authenticated transport, durable ordered storage of ciphertext, an encrypted relay, an ACL gate, a key directory (public keys), and structure metadata. Everything cryptographic is the client's job. See [06-cryptography.md](06-cryptography.md) and [07-key-and-device-management.md](07-key-and-device-management.md).

## 0.3 Scope of v1.0.0

v1.0.0 is the **complete E2EE Android client** spanning Phases 0–6 of the master roadmap, built in the same phase order as the server (see [20-roadmap.md](20-roadmap.md)). The key/device/recovery subsystem is foundational (Phase 0) and is never retrofitted later.

Explicitly **in scope**: markdown + plaintext + ink editing, the server sync policies (`server-default`/`excluded`) plus client-local keep-on-device, encrypted relay collaboration with guests, account + link sharing, rotation-based revocation, version history with client diffs/restore, on-device search, native authentication (password+TOTP or passkeys) with enterprise Keycloak/OIDC as a pluggable option, device enrollment and recovery-key flows, on-device key storage in the Android Keystore/StrongBox, and **multiple accounts / instance switching** (per-account isolated storage and keys, [14 §14.7](14-authentication.md)).

Explicitly **out of scope for v1.0.0** (deferred, matching server Phase 5–6 and the separate migration item): office-document and source-code content types, image attachments, chunked upload for very large binaries, key-transparency/safety-number verification, metadata-graph hiding, and Samsung Notes `.sdoc` import. These are noted where they touch the architecture so seams exist for them.

## 0.4 Target devices & platform baseline

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| `minSdk` | **36** (Android 16) | Matches the owner's existing Android baseline (the `Fitness-Tracker` project). Guarantees modern Keystore/biometric/scoped-storage and the newest crypto providers; narrows the device matrix to current S-Pen hardware, which is acceptable for this app. |
| `targetSdk` / `compileSdk` | **36** (API 36.1) | Aligned to the same baseline; Play Store requirement; scoped storage; predictive back. |
| Form factors | Phone **and** tablet/foldable; landscape | Tablets + S-Pen (Galaxy Tab/Note/S-series) are the primary ink target. |
| Stylus | S-Pen and any `SOURCE_STYLUS` device | Pressure (`AXIS_PRESSURE`), tilt (`AXIS_TILT`), orientation (`AXIS_ORIENTATION`). |
| Orientation | Both; adaptive layout (list/detail panes on wide screens) | Tablet productivity. |
| Offline | First-class | E2EE + local cache means the app is usable with no network. |

## 0.5 Actors (as seen by the Android client)

| Actor | On Android |
|-------|-----------|
| **User** | Signs in to the Nyxite server (native password+TOTP or passkey by default; enterprise Keycloak/OIDC optional), enrolls the device, holds the identity keypair, owns/edits/shares files. |
| **Guest** | This device acting on a link share: no Nyxite account, file key taken from the URL fragment, relay access via a short-lived share token. The app can both *open* an incoming link and *create* link shares. |
| **Peer** | Another user/guest editing the same document; seen through presence/awareness over the relay. |
| **Server** | Blind relay/store. The client treats every byte it sends as ciphertext the server cannot read. |
| **Keycloak** | Optional **enterprise** external IdP (OIDC SSO); not the default — native auth is server-owned. |

## 0.6 Glossary (client-facing)

- **FK (file key)** — per-file AES-256-GCM 256-bit key, generated on-device, stored on the server only *wrapped*.
- **Identity keypair** — per-user **hybrid X25519 + ML-KEM-768** (HPKE/key-agreement) + **hybrid Ed25519 + ML-DSA-65** (signing), NIST level 3 ([06 §6.2](06-cryptography.md)); private parts never leave the device.
- **Device key** — per-device keypair used for device-to-device enrollment approval.
- **Recovery key** — high-entropy user-held secret (phrase) that, via Argon2id, wraps an escrow of the identity private key; the only recovery path.
- **Wrapped key** — an FK encrypted to a member's **hybrid X25519 + ML-KEM-768** public key via HPKE (account share).
- **Fragment key** — an FK carried in a share link's URL fragment (`#k=…`), never sent to the server (link/guest share).
- **Content address** — BLAKE3-256 hash of the *plaintext*, used as the blob's storage key; computed on-device.
- **Encrypted frame** — the on-the-wire/at-rest container: `magic(4)|version(1)|key_id(16)|nonce(12)|ciphertext|tag(16)` with AAD binding `file_id` + object kind ([06](06-cryptography.md)).
- **Key generation** — integer that bumps on FK rotation; clients must use the current generation.
- **Sync policy** — per-file server policy: `server-default` | `excluded`. (Offline pinning is the separate **client-local** `keepOnDevice` field, never sent to the server — [16 §16.2](16-offline-and-storage-policies.md).)
- **CRDT / Yrs / UniFFI** — the Yjs-family CRDT; the Android client merges text documents through **UniFFI-generated Kotlin bindings over the reference Rust `yrs`/`yffi` core**.

## 0.7 Phase map (Android)

| Phase | Android deliverable |
|-------|---------------------|
| 0 | App shell, native login (password+TOTP, passkeys) with enterprise Keycloak/OIDC pluggable, device enrollment, identity keys in Keystore, recovery-key UX, structure browsing with encrypted names, local encrypted DB. |
| 1 | Markdown + plaintext editing on the encrypted CRDT (single user offline-first), blob sync, server sync policies (server-default/excluded) + client-local keep-on-device, on-demand download, view/edit modes, on-device search. |
| 2 | Live relay collaboration (client-side merge), account + link sharing, guest mode, rotation-based revocation, version history with client diffs + restore. |
| 3 | S-Pen ink capture + vector stroke format, LWW/version-vector ink sync (encrypted blobs). |
| 4 | Rich per-user/per-file config, settings, audit surfacing where applicable, polish. |
| 5 | (Optional) office/source/image content types as encrypted blobs; chunked upload. |
| 6 | (Optional) key-transparency/safety-number verification UI. |

See [20-roadmap.md](20-roadmap.md) for detail and acceptance criteria.
