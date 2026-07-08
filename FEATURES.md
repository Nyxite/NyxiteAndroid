# Nyxite Android — Features

Kotlin + Jetpack Compose client. Native, chosen for S-Pen stylus pressure/tilt and low-latency ink.

**Privacy first / full E2EE.** Encryption, decryption, and CRDT merge happen **on the device**. The server only ever sees ciphertext. The device holds the user's keys, decrypts content for display, and re-encrypts edits before upload. Search and offline reach are bounded by mobile storage/battery, so the device indexes its **local cached subset**, not necessarily the whole corpus.

## Editing

- Markdown, handwritten ink, and plain-text editors
- View and edit modes

## Organization

- Project and folder navigation (names decrypted on-device; stored encrypted on the server)

## Sync

- Per-file sync policy controls: server-default, excluded — plus a client-local "keep on device" pin the zero-knowledge server never sees
- **Background auto-sync (first-class)** — auto-pulls server changes, configurable per project/folder/file. **No Google/FCM:** battery-friendly default is a **WorkManager poll (~15 min)**; an **opt-in foreground service** gives real-time sync over the encrypted WebSocket relay. (See [SPECIFICATION §5.4](../docs/SPECIFICATION.md).)
- Encrypted at rest on-device (the desktop-only plaintext-at-rest option does **not** apply to Android)
- Content is encrypted on-device before upload and decrypted after download (the server moves ciphertext only)

## Collaboration

- Live collaborative editing — **client-side CRDT merge** (yrs via UniFFI); the server is a blind **encrypted relay**
- Guest/link participation uses the file key from the share-link URL fragment

## Search

- **Client-side** full-text search over the device's local (cached / pinned) subset; the desktop is the full-corpus surface

## Version history

- Browse version history with **client-side diffs** (snapshots fetched and diffed on-device); restore

## Sharing

- Create and manage share links (link key in the URL fragment; account shares wrap the file key to the recipient's public key via HPKE, on-device)

## Group sharing (enterprise/family)

- Group management ([features/groups.md](groups.md)) on-device: generate a group keypair, enroll/remove members, unwrap the group key → DEKs; enrollment **verifies the member's public key against the key-transparency log** before wrapping
- Wrap a file/subtree DEK to a group public key; honor the per-project/folder **reader-group attachment** — auto-wrap new files to the attached group's public key (the enterprise "manager reads all" path)
- `GroupKeyRotationWorker` — scope-scoped group-key rotation on member removal (re-wrap to remaining, optional DEK re-seal); honest UI that already-decrypted content can't be recalled
- Recovering the identity key restores group access automatically

## Encryption & keys

- On-device AES-256-GCM content encryption; **post-quantum hybrid HPKE** (X25519 + ML-KEM-768) wrap/unwrap of file keys and **hybrid signatures** (Ed25519 + ML-DSA-65), NIST level 3, at v1.0.0
- Device enrollment and identity-key handling; recovery via the user's recovery phrase unwrapping the client-encrypted recovery blob; keys protected by the platform keystore where possible

## Authentication

- **Native login** — password + required TOTP, or **passkeys (WebAuthn)** (account auth; decryption governed by on-device keys). Enterprise Keycloak/OIDC SSO is a pluggable option. (See [SPECIFICATION §10](../docs/SPECIFICATION.md).)

## Bug reporting & support

- **"Report a bug"** — an in-app report composer, shown only when the instance has reporting enabled (a server `support.enabled` capability flag; v1 = the maintainer's official instance(s) — SUP-9).
- **Screenshot capture + destructive redaction** — optionally attach a screenshot (captured via `PixelCopy` / `MediaProjection`); a redaction editor with **black-box + blur** tools **flattens redacted regions into the pixels before upload**, so the original image and mask never leave the device (SUP-2).
- **Consent + destination notice** — before sending, a clear notice that, unlike your files, the report is **not end-to-end encrypted** and goes to the **Nyxite maintainer**, plus a GDPR disclosure (SUP-1); a **user-reviewable diagnostic envelope** (app version/build, platform, locale, current screen id — never content, scrubbed logs, connection state) is editable before send.
- **"My tickets"** — track your own reports' status and support replies with in-app notifications; submission goes through the server as an **authenticating relay** (the client never contacts the helpdesk directly — SUP-3/SUP-7).
- Runs on the **consensual, non-E2EE support plane** — disjoint from content, carrying no content key or content-plane ciphertext. Detailed in [Nyxite Support](support.md) / the `NyxiteSupport` repo `specification/`; decisions in [OPEN-DECISIONS.md](../docs/OPEN-DECISIONS.md) (SUP-1–SUP-9).

## Open questions

See [../docs/OPEN-DECISIONS.md](../docs/OPEN-DECISIONS.md). Android-specific:

- yrs (UniFFI) binding — the Android CRDT engine wraps the reference Rust `yrs`/`yffi` core via UniFFI-generated Kotlin bindings; validate with a short integration + conformance spike (the previously-planned standalone Kotlin binding was dropped as officially inactive)
- On-device key storage (Android Keystore / StrongBox), device enrollment UX, and recovery-phrase entry/unwrap handling on mobile
- Ink capture (S-Pen pressure/tilt) and encrypted storage-format parity with desktop
- Client-local pin and client-side indexing behavior under mobile storage and battery constraints
