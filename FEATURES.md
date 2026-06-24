# Nyxite Android — Features

Kotlin + Jetpack Compose client. Native, chosen for S-Pen stylus pressure/tilt and low-latency ink.

**Privacy first / full E2EE.** Encryption, decryption, and CRDT merge happen **on the device**. The server only ever sees ciphertext. The device holds the user's keys, decrypts content for display, and re-encrypts edits before upload. Search and offline reach are bounded by mobile storage/battery, so the device indexes its **local cached subset**, not necessarily the whole corpus.

## Editing

- Markdown, handwritten ink, and plain-text editors
- View and edit modes

## Organization

- Project and folder navigation (names decrypted on-device; stored encrypted on the server)

## Sync

- Per-file sync policy controls: server-default, pinned-local, excluded
- Content is encrypted on-device before upload and decrypted after download (the server moves ciphertext only)

## Collaboration

- Live collaborative editing — **client-side CRDT merge** (ykt / Yrs); the server is a blind **encrypted relay**
- Guest/link participation uses the file key from the share-link URL fragment

## Search

- **Client-side** full-text search over the device's local (cached / pinned-local) subset; the desktop is the full-corpus surface

## Version history

- Browse version history with **client-side diffs** (snapshots fetched and diffed on-device); restore

## Sharing

- Create and manage share links (link key in the URL fragment; account shares wrap the file key to the recipient's public key via HPKE, on-device)

## Encryption & keys

- On-device AES-256-GCM content encryption; HPKE wrap/unwrap of file keys
- Device enrollment and identity-key handling; user-held recovery-key flow; keys protected by the platform keystore where possible

## Authentication

- Keycloak login with TOTP (account auth; decryption governed by on-device keys)

## Open questions

See [../docs/OPEN-DECISIONS.md](../docs/OPEN-DECISIONS.md). Android-specific:

- ykt binding maturity — validate early; it is the least battle-tested of the three CRDT bindings (and now runs the merge client-side)
- On-device key storage (Android Keystore / StrongBox), device enrollment UX, and recovery-key handling on mobile
- Ink capture (S-Pen pressure/tilt) and encrypted storage-format parity with desktop
- Sync policy and client-side indexing behavior under mobile storage and battery constraints
