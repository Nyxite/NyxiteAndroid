# 13 — Sharing & Access Control

Two-layer model ([server 09](https://github.com/Nyxite/NyxiteServer)): a **server-enforced ACL** (who may reach the ciphertext/relay) plus a **cryptographic layer** (who can decrypt). The client manages both: it calls the share API for the ACL and performs the key wrapping for the crypto layer. Two share kinds: **account shares** (HPKE-wrapped FK) and **link shares** (FK in the URL fragment).

## 13.1 Account share (user grant)

1. The sharer enters/looks up the grantee (by email/userId). The client fetches the grantee's **hybrid public keys** from the directory: `GET /keys/directory?email=` (cache as `DirectoryKeyEntity`, [04](04-local-data-model.md)); verify the entry's **hybrid Ed25519 + ML-DSA-65** self-signature ([06 §6.7](06-cryptography.md)).
2. The client **HPKE-wraps the file key** to the grantee's **hybrid X25519 + ML-KEM-768** public key (§6.2).
3. `POST /shares` with `{ targetType, targetId, kind: user_grant, granteeId, permission: read|write }` and `POST /files/{id}/keys` (or the combined create-share payload) carrying the **wrapped key**.
4. The grantee's device later `GET /files/{id}/keys` → unwraps with its identity private key → can decrypt.
5. For a folder/project target, the relevant per-file keys are wrapped to the grantee (batched/lazily) so the subtree decrypts.

The server stores only the opaque wrapped blob; it never sees the FK.

### Server contract pinned in Phase 2.2

The server side of sharing is built, and these points of its contract were pinned with it ([server 09 §9.4a](https://github.com/Nyxite/NyxiteServer), [server 04 §4.8](https://github.com/Nyxite/NyxiteServer)).
The fixtures `sharing.json` of P2.2-CORE-1 hold one request and response of each, and no request in them carries the fragment.

- Wraps to a member use HPKE with `info = "nyxite object key v1"` and `aad = object_id(16) ‖ key_id(16)`, because the AAD binds a wrap to its object and key generation and the recipient's client must open what any other client sealed.
- An account share carries the target's own key in `POST /shares` `wrappedKeys`, the current generation at least.
  The subtree fan-out `POST /files/keys:batch` takes folder keys as well as file keys, each grant with exactly one of `fileId` and `folderId`, because every folder has its own key.
  A grant is `applied` also when it was stored before, so the resumable queue is drained without an `Idempotency-Key`.
- The project owner shares, changes shares and hands out keys.
  A grantee removes its own share with `DELETE /shares/{id}`, and a write share covers content only, so a grantee's rename, move, create or delete answers `403 acl_denied`.
- `GET /shares` without a target lists the live account shares granted to the caller, the "shared with me" view.
- A link targets one file, because its fragment carries one file key.
  The token is 32 random bytes as 43 base64url characters, and `linkTokenHash` is the SHA-256 of its ASCII characters.
- `GET /share/{token}` answers JSON with the file's ciphertext name, the current `keyId` the fragment key must match, and a guest token.
  `POST /share/{token}/session` renews the guest token, `GET /share/{token}/blob` and `POST /share/{token}/socket-ticket` need it as bearer.
  The guest relay `/share/{token}/ws` is WebSockets only and takes a single-use ticket per connection.
- An unknown token is `404`, a revoked or expired one `410 share_revoked` or `410 link_expired`, and an open guest connection is closed with that code by the relay's re-check.
  Every `/share/{token}*` request counts against 30 per minute per IP, `POST /shares` against 30 per minute per user, and the client backs off on `429` by `Retry-After`.
- `POST /files/{id}/keys/rotate` and the staged head `PUT /files/{id}/keys/rotate/head` are served since Phase 2.3, and `sharing.json` pins both requests (see the revocation section).

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
2. **Forward-secrecy rotation**: the client of a remaining member with write access **rotates the file key** so future content is unreadable to the removed party ([07 §7.6](07-key-and-device-management.md)): new FK, re-encrypt head + new snapshot, re-wrap to remaining members, `POST /files/{id}/keys/rotate`. Runs in `KeyRotationWorker`; the file shows `Rotating`.
- The UI is honest: already-downloaded content can't be recalled; rotation protects only future edits.


### Server contract pinned in Phase 2.3

The server side of both layers is built ([server 09 §9.6](https://github.com/Nyxite/NyxiteServer)), and the fixtures `sharing.json` of P2.2-CORE-1 hold the staging request, the commit and their answers.

- `DELETE /shares/{id}` cuts the removed member off at fetch, join and submit at once, and its open relay rooms end with `OnError(not_found)`, or `share_revoked` for a link guest, without waiting for the periodic re-check.
  A logout or a revoked session or device closes that session's relay connections the same way.
- Any remaining member with write access rotates, and a reader's rotation attempt is `403 acl_denied` (decided by the user on 2026-11-15, see the next subsection).
- The rotation is write-then-swap: the client stages the new head with `PUT /files/{id}/keys/rotate/head` and the commit names it in `newHeadRef`, its base64url BLAKE3-256 content hash, so no member ever sees a head it cannot open.
  `newHeadRef` is required when the file has a head or its log ever took an update, unless the file is excluded from sync, which rotates without one.
  A text head must cover every update of the log, and a head, snapshot or update that moved since staging is `409 version_conflict`, after which the client stages again.
- The commit carries `nameEnc` and `metadataEnc` sealed with the new key, because listings carry only the wrap of the current generation.
- `wrappedKeys` must wrap the new key to exactly the accounts that reach the file, the owner included, otherwise `400`, so the removed member gets no wrap of the new key.
- The commit needs an `Idempotency-Key`, and its replay answers `204` again.
- From the commit on, every update, snapshot and blob sealed with an older key is `412 key_generation_stale`, over REST and as `OnError(fileId, "key_generation_stale", ...)` on the relay, and the client re-seals and resubmits it with its stable client id.
- Older versions and wraps stay readable to the members who had them, and the file's links are revoked or kept frozen as the next subsection describes.

### Rotation by any writer, links and retries (decided 2026-11-15)

The user decided these rules for the second layer of revocation on 2026-11-15, and the server enforces its side of them ([server 09 §9.6](https://github.com/Nyxite/NyxiteServer)).

Who rotates and when:

- Any remaining member with write access rotates, and a reader never does, so a revocation does not wait for the owner to come online.
- The client keeps a durable rotation task per file in `RotationTaskEntity` ([04](04-local-data-model.md)), because a revocation must not be forgotten when the app closes or the device goes offline.
  The task is created when this account, as owner, revokes a share that reached the file, when a file it can write arrives with `rotationPending: true` (file listing, `structure` upsert of the change feed or `GET /files/{id}/keys/rotate/members`), and when one of the owner's own shares expired, because the server marks no expiry.
- Whichever eligible device of any eligible account comes online first performs the task in `KeyRotationWorker`.
  Every other device drops its task once the file's `keyGeneration` moves past the generation the task was created at or `rotationPending` turns false.
- A grantee that removes its own share creates no task, because it gave its access up and the server marks nothing.
- The file shows "revocation not complete" while a task exists, so the owner sees that the second layer is still due.

Performing a rotation:

1. `GET /files/{id}/keys/rotate/members` names the accounts to wrap to, as account ids only, and the client looks their directory keys up as for a share ([§13.1](#131-account-share-user-grant)).
   The list serves the wraps only and is neither shown nor kept beyond the task, because managing shares stays with the owner.
2. Stage the head under the new key and commit with the link choice below ([07](07-key-and-device-management.md)).
3. `409 conflict` means another writer's rotation won: drop the task, refetch the keys and re-seal pending writes under the new generation.
   `400` after a share changed means the member set moved: read the members again, wrap and retry.
   `409 version_conflict` means the document moved: retry under "Busy documents" below.
   `403 acl_denied` means this account lost write access meanwhile: drop the task and let another writer perform it.

Links on rotation:

- Whoever performs the rotation chooses per rotation in a dialog that states both outcomes in plain words and preselects the first, and the owner's revoke dialog offers the same choice up front.
  - "Revoke links and create new ones" (default, `links: revoke`): every link of the file stops working at once and answers as revoked.
    When this account may create links, which is the owner, the client offers right after the commit to create new links with the same permission and expiry under the new key and shows the new URLs to pass on.
    Another writer's client says that the owner can create new links.
  - "Keep links, showing this version" (`links: keep`): every link keeps working, read only, and keeps showing the file as it is at this rotation, without any later change.
    For a text file the client first stores a snapshot under the old key that covers every update, because the server refuses to keep links otherwise with `409 version_conflict`.
- A task that runs without the user present uses the choice stored in the task, and the default `revoke` when none was given, because a kept link is a deliberate decision.
- The share panel shows a frozen link (`frozenGeneration` set) as "frozen at the version of <date>", read only, with the actions revoke and replace with a new link, and it cannot be made writable again (`409 conflict`).
- A guest whose bootstrap answers `frozen: true` opens the frozen head and name read only, joins no relay room, because the server answers `link_frozen`, and shows "This link shows the file as it was on <date>".
  A revoked link shows "This link was revoked".

Busy documents:

- A text rotation is refused while the log moves, so in a busy document the client stages only after a short pause in incoming updates and retries with exponential backoff and full jitter.
- Every limit is a setting with a default, kept per device in Settings ([15](15-ui-and-navigation.md)), because a large team and a slow connection need other values than a single user:

| Setting | Default | Meaning |
| --- | --- | --- |
| `rotation.quietPeriod` | 3 s | No incoming update for this long before the client stages, so the stage likely still covers the log at the commit |
| `rotation.maxQuietWait` | 30 s | Longest wait for that pause, after which the client stages anyway |
| `rotation.backoffInitial` | 2 s | Wait before the first retry after a `409 version_conflict` |
| `rotation.backoffMax` | 60 s | Cap of the doubling wait |
| `rotation.maxAttempts` | 8 | Attempts in a row before the task pauses |
| `rotation.pauseAfterAttempts` | 5 min | Pause after that many attempts, after which the task starts over |

- A value that is not positive falls back to its default, because a zero wait would hammer the server and a zero attempt count would never rotate.
- The task never gives up while it exists, and after each pause the user is told that the revocation is still not complete, because silently dropping it would leave the removed member able to read new content.
- The client keeps sending its own edits under the old key during a rotation, because the server accepts them until the commit, and re-seals what meets `412` after it.
- Holding back every member's edits while a rotation is staged was rejected for now, because it needs a server-side pause the alpha does not have, and the retries are to be measured once clients exist.

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
