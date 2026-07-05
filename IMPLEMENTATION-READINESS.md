# NyxiteAndroid — Implementation Readiness Assessment

**Question:** Can NyxiteAndroid be **fully implemented across all phases (0–6)** using only (a) the `NyxiteAndroid` repo and (b) the shared `Nyxite` info repo?

**Verdict: NO — not fully.** The specification set is unusually complete and would let a competent team build **most of the on-device surface** (crypto framing, Room/SQLCipher schema, key/device/recovery, editors, UI/navigation, keep-on-device/search, version history, sharing/rotation client logic, multi-account). It is **not** sufficient to build the parts that must be **byte-for-byte interoperable with the server and the other clients**, because those contracts are (i) owned by the server spec, which is **not present** in either provided repo, and (ii) in several cases **explicitly deferred / not yet frozen / pending cross-client co-design**.

An E2EE multi-client system lives or dies on exact wire/format interop. Every place the Android spec says "must match the server exactly," "server-owned — track & confirm," "`[P]` pending desktop co-design," or "freeze during this phase" is a place where the provided information is a description, not a buildable contract.

**Hard blockers: 7** (grouped below). Plus a set of minor ambiguities and hardware-validation spikes.

Scope note: this assessment treats the referenced `server/specification/**`, the shared conformance-vector files, and the desktop ink-format doc as **out of scope** because the task restricts inputs to the two named repos, and those artifacts do not exist in either. Where the Android spec *reproduces* a server fact (e.g. the crypto algorithm table and frame layout), that fact is treated as available.

---

## What IS fully specified (buildable today)

To calibrate the gaps, these are genuinely sufficient to code against:

- **Crypto frame & primitives** — `magic "NYXC" | version 0x01 | key_id(16) | nonce(12) | ct | tag(16)`, `AAD = magic‖version‖key_id‖file_id(16)‖object_kind(1)`, `object_kind` enum (blob=1…settings=7), HPKE suite IDs (`KEM 0x0020/KDF 0x0001/AEAD 0x0002`), AES-256-GCM, Ed25519/X25519, BLAKE3-256, Argon2id `m=64/t=3/p=1`, recovery-blob shape + `AAD = userId‖version`. (06, and confirmed in `SPECIFICATION §6`.) These are **pinned**.
- **Local data model** — Room entities, columns, indices, sync-state enum, account registry, FTS5 table shape. (04) Very complete.
- **Key/device/recovery flows, Keystore/StrongBox model, biometric validity window, BIP39-24.** (07, 19.2)
- **Editors (markdown/plaintext/ink UX), UI/navigation graph, offline/keep-on-device cascade, client-side search, version-history client logic, sharing/revocation client logic, multi-account isolation, DI/threading, module graph, build/CI.** (03, 08, 10–18)
- **Relay hub *contract* at the method level** — `JoinDocument/SubmitUpdate/SubmitAwareness/LeaveDocument`, callbacks, `EncryptedUpdate{seq,ciphertext,keyId}`. (09) — the *shape* is given; the *binary encoding* is not (see Blocker 3).

---

## HARD BLOCKERS (by phase)

### Cross-cutting / Phase 0.1

**HB-1 — The server REST wire contract / OpenAPI is not available.**
- *What's missing:* `05-api-client.md` names endpoints and a few DTO fields (`nameEnc`, `metadataEnc`, `contentType`, `syncPolicy`, `crdtDocId`, `{items,nextCursor}`) and the `problem+json` code list — but the **authoritative contract is `/openapi/v1.json` and `server/specification/04-05`**, neither of which is in the provided repos. Exact request/response JSON schemas for projects/folders/files CRUD, `/files/{id}/keys*`, `/devices*`, `/recovery`, `/shares`, `/me/settings`, `/share/{token}*`, versions, and the auth endpoints (below) are not fully pinned client-side.
- *Blocks:* `core-network` (`ApiClient`) and every `data-*` repository's mapper layer — i.e. essentially every phase from P0.1-AND-1 onward. Acceptance cases that assert exact server-side shapes (P0.1-TC-5, P0.2-TC-1) can't be validated without the contract.
- *Where I looked:* Android 05; global `SPECIFICATION §13`; OPEN-DECISIONS. All defer to the server repo (external).
- *Need:* the server OpenAPI document (or `server/specification/04–08`) frozen for each phase.

**HB-2 — Native auth endpoint contracts (password+TOTP, passkey/WebAuthn, refresh, step-up).**
- *What's missing:* the model is clear (server-issued access ~5 min + refresh; IdP-agnostic bearer; `403 2fa_required` step-up), but the **exact `POST /auth/login` request/response, TOTP enrollment/challenge exchange, and the WebAuthn ceremony parameters** (RP ID, attestation/assertion options, challenge lifecycle for Credential Manager) are server-owned (server 08) and not provided. The Android chapter 14 itself flags it needs "reconciling to native-auth default" (also called out in `implementation/README` cross-cutting notes).
- *Blocks:* P0.1-AND-3 (native auth + enrollment), and the enterprise AppAuth path's `Nyxite scope`/token-resolution detail.
- *Need:* server auth endpoint schemas + the WebAuthn RP config for the instance.

**HB-3 (cross-cutting acceptance) — The shared cross-client conformance vectors do not exist yet.**
- *What's missing:* P0.1-CORE-2/-3 require authoring the shared **crypto KAT/cross-client vectors** and **CRDT wire vectors**, "checked into every repo's test resources." The Android repo contains **no `core-crypto`/`core-crdt` test-resource vectors** (no code exists yet). The frame/primitive *formats* are pinned (so the Android side is codeable), but "**byte-for-byte interop enforced by shared conformance vectors**" — the non-negotiable acceptance bar (P0.1-TC-1, and every later `*-CORE-*` fixture) — cannot be met without the co-owned vector set and the counterpart implementations to interop-test against.
- *Blocks:* the *acceptance/exit* of every phase (interop is the gating invariant), even though local code can be written.
- *Need:* the shared vector files, co-authored with server/desktop/web, plus the reference implementations to round-trip against.

### Phase 1.1 / 1.2 — Sync

**HB-4 — `GET /sync/manifest` and `POST /sync/changes` payload shapes + per-kind `ref` formats are not frozen.**
- *What's missing:* Android 08 §8.3 and 19.6 explicitly mark these **"track & confirm — server-owned"**; OPEN-DECISIONS lists "Sync payload shapes / `ref` formats … server-owned; clients code defensively … Resolves during Phase 1." P1.1-CORE-2 is literally the step that *freezes* them — that freeze is not in the provided material. The manifest record fields are enumerated, but the delta `serverChanges[]`/`localChanges[]` envelope, the `ref` encodings for `blob`/`crdt`/`keyrotate`, and the cursor contract details are not pinned.
- *Blocks:* `data-sync` (P1.1-AND-2, P1.2-AND-1) — the two-tier reconciler cannot be finalized against an unfrozen contract.
- *Where I looked:* Android 08; 19.6; global OPEN-DECISIONS "Tracked for implementation."
- *Need:* the frozen manifest/delta payloads and `ref` formats (server-owned) for Phase 1.

**HB-5 — Relay binary wire encoding is under-specified.**
- *What's missing:* the SignalR `RelayHub` **method signatures are given** (09 §9.3) but the **on-the-wire binary encoding** (P1.1-SRV-1 references "MessagePack wire encoding"; the `EncryptedUpdate` serialization, awareness-cipher framing, presence DTO layout, group-name conventions, and the guest share-token/60-s ticket handshake byte details) are server-owned and not provided. The SignalR-on-Android spike (RxJava→coroutines, reconnect, guest-token upgrade) is also still an unrun validation gate.
- *Blocks:* `RelayClient`/`data-collab` (P2.1-AND-1) and the REST CRDT fallback framing consistency.
- *Need:* the relay hub's exact serialization contract (server 05) + the validated spike.

### Phase 3.1 — Handwriting

**HB-6 — The shared ink CBOR vector format's exact field schema is not finalized.**
- *What's missing:* 10.5 states the field schema is **"`[P]` pending desktop co-design"**; 19.4 gives a *proposed sketch* only; OPEN-DECISIONS lists it under "Tracked for implementation" with "the only remaining work is desktop + Android co-design of the exact field schema plus cross-client round-trip vectors." P3.1-CORE-1 makes the single shared format document a **gating artifact "before any client serialization is committed."** Because the BLAKE3 content address must be **byte-reproducible across Android and desktop**, the exact CBOR key/array ordering and integer quantization scales (e.g. position 1/32 px, pressure/tilt fixed scales) must be identical and ratified — they are not.
- *Blocks:* P3.1-AND-2 (serialization, stable-address conformance, LWW). Ink *capture/render* (P3.1-AND-1) is buildable; **serialization/interop is not.**
- *Where I looked:* Android 10.5, 19.4; global OPEN-DECISIONS; the referenced desktop `specification/10 §10.5` is external and itself `[P]`.
- *Need:* the ratified joint ink-format document + round-trip vectors.

### Phase 4.3 / 4.4 — Key transparency & Groups

**HB-7 — Key-transparency inclusion-proof format + safety-number derivation are undefined; group scope granularity is deferred.**
- *What's missing:* P4.3-CORE-1 is a **"define"** step — the safety-number derivation algorithm and the **transparency-log inclusion/consistency-proof format** (Merkle scheme, hash construction, proof/encoding) are not specified anywhere in the provided docs; they are server-owned (server 09/13, external) and only exist as a to-be-authored vector set. Android 13.6/06.7 defer to "Phase 6/4.3." Groups (P4.4) **hard-depend** on this. Additionally, group **scope granularity (per-project vs per-time-period)** is explicitly a **deferred/server-owned sub-choice** (G-4; Android 19.10) — the client "must match whatever the server pins," which isn't pinned.
- *Blocks:* P4.3-AND-1 (verification UI can't verify an unspecified proof) and, transitively, P4.4-AND-1/-2 (transparency-checked enrollment; scope-scoped rotation). The group **crypto wrap format is pinned** (P4.4-CORE-1: `group_id|scope_id|member_id|generation|alg_id|hpke_ct`, DEK-to-group wrap) — so the envelope mechanics are buildable, but enrollment verification and scope assignment are not.
- *Need:* the transparency-log proof + safety-number spec (with vectors) and the pinned scope granularity.

---

## MINOR AMBIGUITIES / VALIDATION SPIKES (not hard blockers)

These do not block writing code but must be resolved to finish/ship the relevant phase:

- **Phase 5.1 — chunked/resumable upload contract** (part addressing, resume, idempotency, 100 MB ceiling) is a **Phase-5.1 deliverable (P5.1-CORE-1)**, server-owned, not yet pinned. Optional in v1.0.0; blocks P5.1-AND-1 / P5.3-AND-1 when pursued.
- **Phases 6.2 / 6.3 — optional hardening are open by design.** 6.2 metadata-graph-hiding encoding is a "design + vectors" step (P6.2-CORE-1); 6.3 leak-free index is an explicit **go/no-go research gate** (may be declined). Not implementable now, but intentionally so.
- **yrs (UniFFI) integration + conformance spike** (P0.1-CORE-1) — approach *decided*, but the go/no-go spike (NDK ABIs, JNA runtime, 200k-char benchmark, byte-identical encoded updates) is unrun. Risk, not missing info.
- **Hardware spikes** — StrongBox availability/limits across OEMs, biometric validity-window behavior straddling WorkManager runs, final Argon2id tuning on a device trio, S-Pen latency/pressure fidelity (P0.1-TC-8, P3.1-TC-1 are explicitly "hardware-gap" cases). Must be run on real devices; can't be closed from docs.
- **Passkey UX specifics** on Credential Manager (registration vs assertion flows) beyond the high-level description.
- **BLAKE3 / Argon2id concrete artifacts** — the spec deliberately leaves the exact JVM library "chosen at impl time." A normal engineering choice, not a blocker.
- **Internal seams left to the implementer** (`FileKeyHandle` representation, exact UniFFI `.udl`/proc-macro surface, `DispatcherProvider` wiring) — expected implementation freedom, not gaps.

---

## Phase-by-phase readiness summary

| Phase | On-device logic | Server/interop contract | Verdict |
|---|---|---|---|
| 0.1 Account & identity | Buildable (crypto framing, keystore, recovery all pinned) | **HB-1, HB-2, HB-3** (REST + auth contracts; shared vectors) | Blocked on interop |
| 0.2 Encrypted structure | Buildable | **HB-1** (CRUD DTO shapes) | Blocked on contract |
| 1.1 Notes that sync | Editors/CRDT merge buildable | **HB-4, HB-5** (sync payloads; relay encoding) | Blocked |
| 1.2 Storage & search | Fully buildable (keep-on-device, FTS5) | **HB-4** (manifest/delta) | Mostly buildable; sync blocked |
| 2.1 Live collaboration | UI buildable | **HB-5** (relay wire + spike) | Blocked |
| 2.2 Sharing | HPKE wrap + link/guest fully specified | **HB-1** (share/keys DTOs) | Mostly buildable |
| 2.3 Revocation & rotation | Rotation logic specified | **HB-1** (rotate endpoint shape) | Mostly buildable |
| 2.4 Version history | Client diff/restore fully specified | **HB-1** (versions/restore DTOs) | Mostly buildable |
| 3.1 Handwriting | Capture/render buildable | **HB-6** (ink CBOR schema) | Serialization blocked |
| 4.1 Device/key & multi-account | Fully specified | **HB-1** (rotation/re-wrap endpoints) | Mostly buildable |
| 4.2 Admin & audit | **No `-AND-` steps** (admin is server/NyxiteAdmin) | n/a for Android | N/A |
| 4.3 Key transparency | UI shell buildable | **HB-7** (proof + safety-number format) | Blocked |
| 4.4 Groups | Group envelope crypto pinned | **HB-7** (transparency dep + scope) | Blocked on 4.3 |
| 4.5 Polish & distribution | Fully buildable (settings, FLAG_SECURE, signing) | — | Buildable |
| 5.1 Office / chunked upload | Buildable | chunked-upload contract (minor) | Optional; blocked on contract |
| 5.2 Source code | Buildable (rides CRDT) | inherits HB-4/HB-5 | Optional; mostly buildable |
| 5.3 Images | Buildable (Coil) | inherits 5.1 base | Optional |
| 6.2 Metadata-graph hiding | Design-stage | design + vectors undefined | Optional; not yet designed |
| 6.3 Leak-free index | Research gate | go/no-go, may be declined | Optional; open by design |

---

## Bottom line

You could build a large, coherent fraction of NyxiteAndroid from these two repos — everything that is purely on-device is specified to an implementable level. You **cannot fully implement all phases** because the artifacts that guarantee cross-surface interop are, by the project's own design, **owned elsewhere and not yet frozen**: the server wire contracts (REST + auth + sync + relay), the shared conformance vectors, the co-designed ink CBOR schema, and the key-transparency proof/safety-number format. These are real blockers for the Android surface's *interoperable* completion, not merely polish.
