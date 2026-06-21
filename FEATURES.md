# Nyxite Android — Features

Kotlin + Jetpack Compose client.

## Editing

- Markdown, handwritten ink, and plain-text editors

## Organization

- Project and folder navigation

## Sync

- Per-file sync policy controls: server-default, pinned-local, excluded

## Collaboration

- Live collaborative editing

## Version history

- Browse version history and view diffs

## Sharing

- Create and manage share links

## Authentication

- Keycloak login with TOTP

## Open questions

- CRDT library: which Yjs-compatible implementation on Kotlin/JVM (for example y-crdt/yrs bindings) interoperates with the server's Ycs? (surface-specific; needs a decision)
- Ink capture (touch and stylus) and storage format parity with desktop
- Sync policy behavior under mobile storage and battery constraints
- Per-file-type sync split (CRDT text vs last-write-wins ink/binary) — client side of the spec decision
