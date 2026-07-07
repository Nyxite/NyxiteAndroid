# 20 — Roadmap (Android)

Phase order mirrors the server ([server 15](https://github.com/Nyxite/NyxiteServer)) so the client and backend land each capability together. v1.0.0 is the complete E2EE client (Phases 0–6); phases are build order, not separate products. **E2EE is foundational from Phase 0** and never retrofitted.

Before Phase 1 logic is committed, run the **early spikes** ([18 §18.8](18-build-ci-testing.md)): yrs (UniFFI) integration + conformance, Tink HPKE suite match, SignalR-on-Android, ink latency, Argon2id params.

## Phase 0 — Foundations
**Deliver**: app shell + navigation; **account-scoped DI + per-account SQLCipher DB/keys/cache** as the multi-account foundation ([01 §1.8](01-architecture.md),[04 §4.1](04-local-data-model.md),[14 §14.7](14-authentication.md)); native authentication (password+TOTP, passkeys) with enterprise Keycloak/OIDC + PKCE pluggable ([14](14-authentication.md)); identity keypair generation + device enrollment + recovery-key flow ([07](07-key-and-device-management.md)); key directory publish/lookup; Keystore/StrongBox + SQLCipher DB ([04](04-local-data-model.md),[17](17-security.md)); structure CRUD with **encrypted names** decrypted on-device ([05](05-api-client.md)); the **post-quantum hybrid** crypto engine (HPKE X25519 + ML-KEM-768, signatures Ed25519 + ML-DSA-65, NIST level 3 — [06 §6.2](06-cryptography.md)) with conformance vectors, including selecting the hybrid-capable PQC library ([02 §2.7](02-tech-stack-and-libraries.md), [18 §18.8](18-build-ci-testing.md)).
**Done when**: a user can log in, enroll the device, set a recovery key, recover on a second device, and browse/create the encrypted project/folder/file structure offline-first; crypto KATs + cross-client vectors pass.

## Phase 1 — Notes that sync (single user)
**Deliver**: markdown + plaintext editors with view/edit modes ([10](10-editors.md)); encrypted CRDT backbone with offline catch-up (relay optional here) ([08 §8.4](08-sync-engine.md),[09](09-realtime-collaboration.md)); ciphertext blob sync + on-demand download; **user-driven keep-on-device** retention at file/folder/project (client-local) + the server sync policies (`server-default`/`excluded`) ([16](16-offline-and-storage-policies.md)); on-device FTS search ([11](11-search.md)).
**Done when**: a single user edits notes on the phone, they sync to another device through the encrypted relay/REST, offline edits reconcile, and local search works over the cached subset.

## Phase 2 — Collaboration & sharing
**Deliver**: live multi-client relay collaboration with presence/awareness ([09](09-realtime-collaboration.md)); account shares (HPKE-wrapped FKs) + link/guest shares (URL-fragment keys) ([13](13-sharing.md)); guest mode; rotation-based revocation ([07 §7.6](07-key-and-device-management.md)); version history with **client-side diffs** + restore ([12](12-version-history.md)).
**Done when**: two users (and an anonymous guest via link) co-edit a document live; revoking a share cuts off access and rotates the key; history diffs and restore work entirely on-device. CRDT conformance with web/desktop passes.

## Phase 3 — Handwriting
**Deliver**: S-Pen ink editor with pressure/tilt + low-latency rendering ([10 §10.4](10-editors.md)); the shared encrypted ink vector format ([10 §10.5](10-editors.md)); LWW/version-vector ink sync as encrypted blobs ([08 §8.5](08-sync-engine.md)).
**Done when**: handwritten notes capture faithfully, store encrypted, sync via LWW (losers retained in history), and round-trip with the desktop format.

## Phase 4 — Polish & config
**Deliver**: rich per-user/per-file client-encrypted settings ([05](05-api-client.md)); device/key management UI (revoke device, re-issue recovery, identity rotation) ([07](07-key-and-device-management.md)); **multi-account switcher UI** (add/switch/remove accounts, per-account instance host) over the Phase-0 account scoping ([14 §14.7](14-authentication.md),[15 §15.1](15-ui-and-navigation.md)); storage/budget controls; security settings (app lock, secure window); accessibility pass ([15](15-ui-and-navigation.md),[17](17-security.md)).
**Done when**: settings/config are complete and encrypted; security and accessibility meet the bar; the app is store-ready.

## Phase 5 — Format expansion (optional in v1.0.0 scope)
**Deliver**: office docs, source-code text types (CRDT + syntax view), and images as encrypted blobs; chunked/resumable upload for large binaries ([05 §5.6](05-api-client.md),[16 §16.5](16-offline-and-storage-policies.md)). Any processing (thumbnails/extraction) is client-side.

## Phase 6 — Advanced hardening (optional)
**Deliver**: key transparency / safety-number verification UI for the key directory ([13 §13.6](13-sharing.md)); optional metadata-graph hiding support if the server adds it.

## Group sharing (enterprise/family) — build Phase 4.4 (v1.0.0)
Maps to the **master build plan's Phase 4.4** ([implementation/phase-4.4-groups.md](https://github.com/Nyxite/Nyxite), steps **P4.4-AND-1/2**), landing in **v1.0.0 after key transparency (build Phase 4.3)** — its **hard dependency**: group enrollment wraps only to **transparency-log-verified** public keys, so key transparency is pulled into v1.0.0 ahead of groups (this brings forward the directory-transparency work the Android roadmap otherwise surfaces in Phase 6, [13 §13.6](13-sharing.md)). Builds on the Phase 2 sharing foundation (HPKE wrap + rotation-based revocation).
**Deliver**: on-device **group keygen**, **transparency-verified enrollment**, unwrap/wrap of group keys and DEKs, and **group-management UI** (P4.4-AND-1, [06 §6.10](06-cryptography.md), [07 §7.10](07-key-and-device-management.md), [13 §13.7](13-sharing.md)); the **reader-group attachment** cascade with **auto-wrap-on-create** (enterprise "manager reads all") and the **`GroupKeyRotationWorker`** for scope-scoped rotation on member removal (P4.4-AND-2, [16 §16.8](16-offline-and-storage-policies.md)); honest revocation UI ("already-decrypted content can't be recalled"); recovery restores group access automatically.
**Done when**: a **family** group reads a shared folder (O(1) enrollment — one grant blob per member, no file re-wrapped); an **enterprise** project auto-wraps new files to the attached *managers* group (a manager reads, another worker can't); enrollment is transparency-checked; removing a member soft-deletes the grant instantly and a remaining member rotates the affected scope's group key (`409` concurrent loser, `412` in-flight old-key wrap then re-seal); recovering the identity key restores group access; server inspection shows only opaque grants/DEK-to-group wraps/membership rows.

> **Multi-account / instance switching is in v1.0.0** (foundation in Phase 0, switcher UI in Phase 4), not deferred — see [14 §14.7](14-authentication.md).

## Cross-cutting / later
- Samsung Notes `.sdoc` import (separate, non-trivial migration item — proprietary ink format).
- Resilience to a Redis-backplane multi-node relay (transparent to the client; hub contract unchanged).

## Versioning
SemVer for the app; pre-1.0 milestone tags track phases. The API is consumed at `/api/v1`; the crypto frame and CRDT wire protocol are pinned by conformance vectors so primitives/keys can rotate without a format break ([06 §6.3](06-cryptography.md),[18 §18.6](18-build-ci-testing.md)).
