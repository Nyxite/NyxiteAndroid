# 13 — Sharing & Access Control

Two-layer model ([server 09](https://github.com/Nyxite/server)): a **server-enforced ACL** (who may reach the ciphertext/relay) plus a **cryptographic layer** (who can decrypt). The client manages both: it calls the share API for the ACL and performs the key wrapping for the crypto layer. Two share kinds: **account shares** (HPKE-wrapped FK) and **link shares** (FK in the URL fragment).

## 13.1 Account share (user grant)

1. The sharer enters/looks up the grantee (by email/userId). The client fetches the grantee's **public keys** from the directory: `GET /keys/directory?email=` (cache as `DirectoryKeyEntity`, [04](04-local-data-model.md)); verify the entry's Ed25519 self-signature ([06 §6.7](06-cryptography.md)).
2. The client **HPKE-wraps the file key** to the grantee's X25519 public key.
3. `POST /shares` with `{ targetType, targetId, kind: user_grant, granteeId, permission: read|write }` and `POST /files/{id}/keys` (or the combined create-share payload) carrying the **wrapped key**.
4. The grantee's device later `GET /files/{id}/keys` → unwraps with its identity private key → can decrypt.
5. For a folder/project target, the relevant per-file keys are wrapped to the grantee (batched/lazily) so the subtree decrypts.

The server stores only the opaque wrapped blob; it never sees the FK.

## 13.2 Link share (anonymous / guest)

1. The client mints a high-entropy link **token** (≥128-bit) and uses the FK as the **fragment key** (≥256-bit).
2. It builds the URL: `https://nyxite.app/share/{token}#k=<base64url(FK)>`. The fragment after `#` is **never sent to the server**.
3. `POST /shares` with `{ kind: link, permission, expiresAt?, linkTokenHash }` — only the **hash** of the token is stored; the FK is not stored at all.
4. The client offers the URL via the Android **share sheet**. The app warns that anyone with the link can access the file (read or write per the share) until it expires or is revoked, and that the key lives in the link.

## 13.3 Opening a link on this device (guest or member)

- The app registers a deep link for `https://nyxite.app/share/*` (App Links) and a custom scheme fallback. On open, it **parses the `#k=` fragment locally** (never transmits it), resolves `GET /share/{token}`, fetches ciphertext / joins the relay with a short-lived share session token, and decrypts with the fragment key ([09 §9.8](09-realtime-collaboration.md)).
- If the user is signed in, opening a link can optionally "save to my account" by creating an account share to themselves (wrapping the FK to their own public key). Otherwise it runs as a constrained guest session.
- Read-only links open the file view-only; write links allow editing.

## 13.4 Managing shares

- A share-management sheet per file/folder/project: list active shares (`GET /shares?target=`), each with kind, grantee or "link", permission, expiry. Change permission/expiry via `PATCH /shares/{id}`; revoke via `DELETE /shares/{id}`.
- Locally created link URLs may be re-displayed only if the user chose to keep them (stored in encrypted prefs and clearly labeled); otherwise the full URL (with fragment) cannot be reconstructed by the server or app after creation.

## 13.5 Revocation (two-layer)

1. **Instant ACL cutoff**: `DELETE /shares/{id}` removes the grant; the removed member/link can no longer fetch ciphertext or join the relay — enforced at fetch/join/submit.
2. **Forward-secrecy rotation**: the client (a remaining member) **rotates the file key** so future content is unreadable to the removed party ([07 §7.6](07-key-and-device-management.md)): new FK, re-encrypt head + new snapshot, re-wrap to remaining members, `POST /files/{id}/keys/rotate`. Runs in `KeyRotationWorker`; the file shows `Rotating`.
- The UI is honest: already-downloaded content can't be recalled; rotation protects only future edits.

## 13.6 Trust & limits (v1.0.0)

- Directory trust is **TLS + Ed25519 self-signature** on entries; **key transparency / safety-number verification is deferred to Phase 6**. Until then, surface the grantee's key fingerprint so cautious users can compare out-of-band.
- The client respects server rate limits on `GET /keys/directory`, `POST /shares`, and `/share/{token}` access (`429` backoff, [05](05-api-client.md)).
- Fragment keys (≥256-bit) and link tokens (≥128-bit) are generated with a CSPRNG; the app warns that links in chat/history can leak the fragment, so prefer account shares for sensitive material and use short expiries for links.
