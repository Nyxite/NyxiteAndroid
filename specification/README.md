# Nyxite Android — Specification (v1.0.0)

This folder is the detailed, implementation-level specification for the **Nyxite Android client** — the native Kotlin + Jetpack Compose application that lets a user read, write, hand-draw, organize, sync, collaborate on, share, and search end-to-end-encrypted notes and documents from an Android phone or tablet (with first-class S-Pen support).

It expands the architectural planning documents in the central [`Nyxite`](https://github.com/Nyxite/Nyxite) repository and the [`server` specification](https://github.com/Nyxite/NyxiteServer) into a concrete client build specification covering the full v1.0.0 product across all roadmap phases.

It is written to be **self-contained enough to build the app from**: it names the libraries, the module graph, the local schema, the API/relay calls, the crypto primitives and how they map to Android APIs, the editor and sync internals, the UI surface, and the build/test/CI setup.

## Guiding principle: privacy first (full E2EE)

**Nyxite is end-to-end encrypted everywhere.** Encryption, decryption, content addressing, CRDT merge, search indexing, and diffing all happen **on the device**. The server only ever sees ciphertext, opaque IDs, the structure graph, ACL grants, wrapped-key blobs, sizes and timestamps — it never holds a content key and cannot read notes, ink, names, or collaboration traffic. The Android client is therefore not a thin viewer: it is a **full cryptographic peer** that holds the user's keys, performs all crypto locally, and treats the server as a blind relay/store.

The client-side burden is real and deliberate: search and offline reach are bounded by mobile storage/battery, so the device indexes and caches its **local subset** (keep-on-device + recently opened), not necessarily the whole corpus. The desktop remains the full-corpus surface.

## Source of truth & convention

- The central `Nyxite` repo is authoritative for product decisions; the `server/specification` set is authoritative for the wire protocol, schema, crypto model, and API. This spec **consumes** those and must not contradict them. Where it picks a concrete Android-side mechanism the server spec left open, it is marked **[P]** (Proposed), exactly as the server spec uses the tag.
- **[OD-n]** references a numbered item in the master `docs/OPEN-DECISIONS.md`.
- Open questions specific to Android are tracked in [19-open-questions.md](19-open-questions.md) and link back to the master open-decisions list.

## Documents

| # | Document | Covers |
|---|----------|--------|
| 00 | [overview.md](00-overview.md) | Purpose, scope, target devices, actors, glossary, phase map |
| 01 | [architecture.md](01-architecture.md) | Layered/Clean architecture, MVI, data flow, threading model |
| 02 | [tech-stack-and-libraries.md](02-tech-stack-and-libraries.md) | Concrete library choices with versions and rationale |
| 03 | [project-structure.md](03-project-structure.md) | Gradle module graph, package layout, naming |
| 04 | [local-data-model.md](04-local-data-model.md) | Room schema, encrypted-at-rest DB, sync state, FTS |
| 05 | [api-client.md](05-api-client.md) | REST client, DTO mapping, error/retry, idempotency, TLS |
| 06 | [cryptography.md](06-cryptography.md) | AEAD, hybrid HPKE (X25519+ML-KEM-768), hybrid signatures (Ed25519+ML-DSA-65), BLAKE3, Argon2id, framing, library mapping |
| 07 | [key-and-device-management.md](07-key-and-device-management.md) | Identity keypair, device enrollment, recovery key, Keystore/StrongBox |
| 08 | [sync-engine.md](08-sync-engine.md) | Manifest/delta sync, policies, CRDT/LWW split, WorkManager |
| 09 | [realtime-collaboration.md](09-realtime-collaboration.md) | SignalR relay, yrs (UniFFI) merge, awareness, guests, snapshots |
| 10 | [editors.md](10-editors.md) | Markdown, plaintext, and S-Pen ink editors; view/edit modes |
| 11 | [search.md](11-search.md) | On-device FTS over the local subset; indexing lifecycle |
| 12 | [version-history.md](12-version-history.md) | Snapshot fetch, client-side diff, restore |
| 13 | [sharing.md](13-sharing.md) | Account shares (HPKE), link shares (URL fragment), revocation |
| 14 | [authentication.md](14-authentication.md) | Native auth (password+TOTP, passkeys) by default; enterprise Keycloak/OIDC+PKCE pluggable; token storage, refresh |
| 15 | [ui-and-navigation.md](15-ui-and-navigation.md) | Screens, navigation graph, Compose/Material 3, accessibility |
| 16 | [offline-and-storage-policies.md](16-offline-and-storage-policies.md) | User-driven keep-on-device (file/folder/project), convenience cache, excluded, battery/network constraints |
| 17 | [security.md](17-security.md) | Threat model on device, at-rest protection, screenshots, biometrics |
| 18 | [build-ci-testing.md](18-build-ci-testing.md) | Gradle, signing, CI, test strategy, CRDT conformance |
| 19 | [open-questions.md](19-open-questions.md) | Android-specific open items with **recommended resolutions** + validation spikes (incl. ratified: minSdk 36, multi-account, package id) |
| 20 | [roadmap.md](20-roadmap.md) | Android phase mapping aligned to the server roadmap |

## Status

Specification for a greenfield build. No Android code exists yet; the `android` repo currently holds only `FEATURES.md` and `LICENSE.md`. This document set defines what to build.

## License

PolyForm Noncommercial License 1.0.0 — see the repo `LICENSE.md`.
