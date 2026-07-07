# 13 — Sharing & Access Control

Two-layer model ([server 09](https://github.com/Nyxite/NyxiteServer)): a **server-enforced ACL** (who may reach the ciphertext/relay) plus a **cryptographic layer** (who can decrypt). The client manages both: it calls the share API for the ACL and performs the key wrapping for the crypto layer. Two share kinds: **account shares** (HPKE-wrapped FK) and **link shares** (FK in the URL fragment).

## 13.1 Account share (user grant)

1. The sharer enters/looks up the grantee (by email/userId). The client fetches the grantee's **hybrid public keys** from the directory: `GET /keys/directory?email=` (cache as `DirectoryKeyEntity`, [04](04-local-data-model.md)); verify the entry's **hybrid Ed25519 + ML-DSA-65** self-signature ([06 §6.7](06-cryptography.md)).
2. The client **HPKE-wraps the file key** to the grantee's **hybrid X25519 + ML-KEM-768** public key (§6.2).
3. `POST /shares` with `{ targetType, targetId, kind: user_grant, granteeId, permission: read|write }` and `POST /files/{id}/keys` (or the combined create-share payload) carrying the **wrapped key**.
4. The grantee's device later `GET /files/{id}/keys` → unwraps with its identity private key → can decrypt.
5. For a folder/project target, the relevant per-file keys are wrapped to the grantee (batched/lazily) so the subtree decrypts.

The server stores only the opaque wrapped blob; it never sees the FK.

## 13.2 Link share (anonymous / guest)

1. The client mints a high-entropy link **token** (≥128-bit) and uses the FK as the **fragment key** (≥256-bit).
2. It builds the URL: `https://nyxite.app/share/{token}#k=<base64url(FK)>`. The fragment after `#` is **never sent to the server**.
3. `POST /shares` with `{ targetType, targetId, kind: link, permission, expiresAt?, linkTokenHash }` — only the **hash** of the token is stored; the FK is not stored at all.
4. The client offers the URL via the Android **share sheet**. The app warns that anyone with the link can access the file (read or write per the share) until it expires or is revoked, and that the key lives in the link.

## 13.3 Opening a link on this device (guest or member)

- The app registers a deep link for `https://nyxite.app/share/*` (App Links) and a custom scheme fallback. On open, it **parses the `#k=` fragment locally** (never transmits it), resolves `GET /share/{token}`, fetches ciphertext / joins the relay with a short-lived share session token, and decrypts with the fragment key ([09 §9.8](09-realtime-collaboration.md)).
- If the user is signed in, opening a link can optionally "save to my account" by creating an account share to themselves (wrapping the FK to their own public key). Otherwise it runs as a constrained guest session.
- Read-only links open the file view-only; write links allow editing.

## 13.4 Managing shares

- A share-management sheet per file/folder/project: list active shares (`GET /shares?targetType={file|folder|project}&targetId={id}`), each with kind, grantee or "link", permission, expiry. Change permission/expiry via `PATCH /shares/{id}`; revoke via `DELETE /shares/{id}`.
- Locally created link URLs may be re-displayed only if the user chose to keep them (stored in encrypted prefs and clearly labeled); otherwise the full URL (with fragment) cannot be reconstructed by the server or app after creation.

## 13.5 Revocation (two-layer)

1. **Instant ACL cutoff**: `DELETE /shares/{id}` removes the grant; the removed member/link can no longer fetch ciphertext or join the relay — enforced at fetch/join/submit.
2. **Forward-secrecy rotation**: the client (a remaining member) **rotates the file key** so future content is unreadable to the removed party ([07 §7.6](07-key-and-device-management.md)): new FK, re-encrypt head + new snapshot, re-wrap to remaining members, `POST /files/{id}/keys/rotate`. Runs in `KeyRotationWorker`; the file shows `Rotating`.
- The UI is honest: already-downloaded content can't be recalled; rotation protects only future edits.

## 13.6 Trust & limits (v1.0.0)

- Directory trust is **TLS + hybrid Ed25519 + ML-DSA-65 self-signature** on entries; **key transparency / safety-number verification is deferred to Phase 6**. Until then, surface the grantee's key fingerprint so cautious users can compare out-of-band.
- The client respects server rate limits on `GET /keys/directory`, `POST /shares`, and `/share/{token}` access (`429` backoff, [05](05-api-client.md)).
- Fragment keys (≥256-bit) and link tokens (≥128-bit) are generated with a CSPRNG; the app warns that links in chat/history can leak the fragment, so prefer account shares for sensitive material and use short expiries for links.

## 13.7 Group sharing (enterprise/family groups)

A **third share kind** for team/family scale: instead of wrapping the FK to each member, files wrap to a **group public key** whose private half is wrapped once per member ([06 §6.10](06-cryptography.md), [07 §7.10](07-key-and-device-management.md), [features/groups.md](https://github.com/Nyxite/Nyxite)). Adding a reader is **one** small key-wrap (**O(1)**) instead of re-wrapping every file (**O(files)**). Two archetypes: **family** (all members read shared data) and **enterprise** (a *managers* group reads all of a team's files; workers read only their own). It **coexists** with account and link shares (§13.1–13.2) — the user picks per situation; groups add no new primitive (P4.4-AND-1/2).

### 13.7.1 Group-management UI
- A **Groups** area (in `feature-sharing`/settings) to create a group (on-device keygen, [07 §7.10](07-key-and-device-management.md)), list members, and **enroll**/**remove** members. Enrollment looks up the newcomer's public key and shows the transparency-verification result **before** any wrap (a directory-substituted key is rejected pre-wrap, [07 §7.10](07-key-and-device-management.md)); removal offers the revocation spectrum below.
- Enrolling past the server's group-size limit surfaces the reject (existence-hiding applies; over-limit is server-enforced by membership-row count, never a key/content read).

### 13.7.2 Wrapping a file / subtree to a group
- **Share to group** on a file/folder/project: HPKE-wrap the target FK(s) to the group's public key (`POST` a DEK-to-group wrapped-key row per scope/generation) and `POST /shares` with the group as principal. A folder/project target wraps the relevant per-file FKs to the group (batched/lazily), exactly as the account-share subtree path (§13.1) — a file readable by a whole group is stored **once** plus **one** DEK-to-group wrap, no duplication.

### 13.7.3 Reader-group attachment & auto-wrap on create
- A project/folder may carry a **reader-group attachment** naming a group whose public key **new files are auto-wrapped to on creation**, in addition to the author's own key — this drives the enterprise "manager reads all" path. It rides the **existing per-project/folder/file cascade** (`inherit` / a specific group / none), the same inheritance used for keep-on-device ([16 §16.2](16-offline-and-storage-policies.md), [16 §16.8](16-offline-and-storage-policies.md)) and sync policy.
- **Client-enforced at file creation** (P4.4-AND-2): when a worker creates a file under an attached scope, the client wraps the DEK to **the author key *and* the attached group's public key** ([07 §7.5](07-key-and-device-management.md)); a manager reads it via the group key, another worker has no path (cryptographically locked out). Wrapping needs only the group's public key from the directory, so the worker needs no membership in the managers group. The server stores the attachment as opaque structure metadata only.

### 13.7.4 Revocation (honest, group-scoped)
Removing a member reuses the two-layer model (§13.5) at the group-key level:
- **Instant ACL cutoff**: soft-delete the member's group-key grant (one delete); they can no longer fetch group blobs.
- **Forward-secrecy rotation**: a remaining member's client runs **`GroupKeyRotationWorker`** — new group key for the **affected scope** (`generation + 1`), re-wrap to remaining members, optional DEK re-seal; concurrent loser `409`, in-flight old-key wrap `412` then re-seal ([07 §7.10](07-key-and-device-management.md)). Only the affected scope is touched; the group shows a `Rotating` state.
- **The UI stays honest**: as with per-file revocation, already-decrypted content **can't be recalled** — rotation only guarantees the removed member reads nothing **new**. Surfaced in the removal dialog and docs, exactly as §13.5.
