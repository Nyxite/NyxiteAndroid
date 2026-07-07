# 09 — Real-time Collaboration

Live multi-user editing of text documents over an **encrypted relay**: the server stores and forwards encrypted CRDT updates but never merges or reads them; **the Android client runs the Yrs engine (yrs via UniFFI) and merges locally** ([server 05](https://github.com/Nyxite/NyxiteServer)). The engine is the reference Rust `yrs`/`yffi` core wrapped by UniFFI-generated Kotlin bindings; a short UniFFI integration + conformance spike validates it ([19](19-open-questions.md)).

## 9.1 Components

- **`CrdtEngine`** (`core-crdt`, yrs via UniFFI): holds the per-document Yrs doc, applies/encodes updates, computes state vectors, serializes snapshots.
- **`RelayClient`** (`core-network`, SignalR): connects to the `RelayHub`, joins per-document rooms, submits/receives **encrypted** updates and awareness, manages reconnection. Speaks ciphertext only.
- **`CollabRepository`** (`data-collab`): orchestrates join → bootstrap → live loop → snapshot → leave, bridging CryptoEngine ⇄ CrdtEngine ⇄ RelayClient.
- **`CollabService`** (foreground service): keeps the connection alive during active editing.

## 9.2 Transport

- **SignalR over WebSocket**, single hub `RelayHub`, groups keyed `file:{fileId}`. Use the `com.microsoft.signalr` Java client wrapped behind `RelayClient`, bridging its RxJava API to coroutines/Flow.
- **Upgrade auth**: the server's access token (bearer) for users; a short-lived **share token** for guests ([14](14-authentication.md)). The token authorizes *relay access*; the **decryption key never comes from the server** (it is the user's unwrapped FK, or the guest's fragment key).
- **Fallback**: when the socket is unavailable, use the REST encrypted-update endpoints ([08 §8.4](08-sync-engine.md)).

## 9.3 Hub contract (client side)

Mirror the server `IRelayHub`. All payloads are ciphertext.

Server → client callbacks the client handles:
- `OnUpdates(fileId, EncryptedUpdate[])` — everything after the client's cursor (join bootstrap).
- `OnUpdate(fileId, EncryptedUpdate)` — a peer's relayed update.
- `OnAwareness(fileId, byte[] awarenessCipher)` — encrypted ephemeral presence/cursors.
- `OnPresence(fileId, PresenceDto[])` — join/leave roster (identities only).
- `OnError(fileId, code, message)`.

Client → server hub methods:
- `JoinDocument(fileId, sinceSeq)`
- `SubmitUpdate(fileId, EncryptedUpdate)`
- `SubmitAwareness(fileId, awarenessCipher)`
- `LeaveDocument(fileId)`

`EncryptedUpdate { seq, ciphertext, keyId }`.

## 9.4 Join handshake

1. Open the (authenticated) socket; `JoinDocument(fileId, localSinceSeq)`.
2. Server ACL-checks (read to receive, write to submit) and returns `OnUpdates` with all encrypted updates after the cursor, plus a pointer to the latest encrypted snapshot.
3. Client bootstraps the Yrs doc: decrypt+load snapshot, decrypt+apply updates, reconcile via the **locally reconstructed state vector** (the server computes no diff).
4. Client is added to the room; `OnPresence` delivers the roster.

## 9.5 Update flow

- **Local edit** → Yrs produces an update → `CryptoEngine.seal(update, fk, kind=CRDT, fileId)` → `SubmitUpdate`. The server assigns `seq`, persists ciphertext, broadcasts `OnUpdate` to the room.
- **Inbound** `OnUpdate` → `open` → apply to the local Yrs doc → recompute editor text → emit new UI state. CRDT merge is deterministic and order-independent.
- **Outbox**: locally generated updates are persisted (`CrdtUpdateEntity`, [04](04-local-data-model.md)) until acked, so edits survive disconnects and are submitted on reconnect or via REST fallback.
- The client must tolerate updates it can't decrypt only as a hard error to surface (should not happen within a single FK generation); on `keyrotate` it refetches keys ([07 §7.6](07-key-and-device-management.md)).

## 9.6 Snapshotting & compaction (client-driven)

The server can't compact an encrypted log. **The client periodically snapshots**: serialize the merged Yrs doc, `seal` it with the FK, upload as a content-addressed blob + create a `file_versions` row ([12](12-version-history.md)). Triggers: **≥ 200 updates since the last snapshot**, **a 5-minute time threshold**, or the **last participant leaving** the room. Run in `SnapshotWorker`; any participant may snapshot; the server may then prune updates older than the snapshot's `seq` (keeping enough for in-flight clients).

## 9.7 Awareness & presence

- **Awareness** (cursor, selection, display label/color) is **encrypted** with the FK and relayed via `SubmitAwareness`/`OnAwareness`; **never persisted**. Rendered as remote carets/selections in the editor ([10](10-editors.md)).
- **Presence roster** (`OnPresence`) shows who is in the room by account display name (from the user's account) or "guest"; no content. Rendered as avatar chips.
- Throttle awareness emission (e.g. on cursor move, coalesced ~20–30 Hz max) to limit traffic and battery.

## 9.8 Guest mode (this device joining a link share)

- Entry: the app opens a `nyxite.app/share/{token}#k=<fragmentKey>` deep link ([13 §13.3](13-sharing.md)). The **fragment key is parsed locally and never sent** to the server.
- `GET /share/{token}` bootstraps; the relay socket connects at `/share/{token}/ws` with a short-lived share session token authorizing relay access only.
- The FK comes from the fragment; the client decrypts/edits exactly as a member would.
- Read-only links: the client receives `OnUpdate`/`OnAwareness` but is rejected on `SubmitUpdate` — the editor enters view-only mode. Guest-authored updates store `author_id = null` server-side.
- A guest has no Nyxite account; the app runs in a constrained "guest session" without the full key/device subsystem.
- **Guest storage model**: a guest runs **without the per-account SQLCipher DB**. Guest content is held in an **ephemeral in-memory cache only** and is **never written to any account database or `noBackup` files**; decrypted views are transient and discarded when the guest session ends ([14 §14.5](14-authentication.md)).

## 9.9 Reconnection & lifecycle

- Exponential backoff reconnect; on reconnect, re-`JoinDocument` with the current `sinceSeq` and drain the outbox.
- On app background without the foreground service (e.g. user navigates away), `LeaveDocument` and tear down; on return, rejoin.
- Connection state surfaced in the editor (live / reconnecting / offline-editing).

## 9.10 Wire-protocol conformance (critical)

Text edited on Android must merge identically on web (Yjs) and desktop (ydotnet). The client ships a **`CrdtConformance` test suite** that replays the shared Yrs wire-protocol vectors and asserts identical merged state and identical encoded updates across bindings ([18 §18.5](18-build-ci-testing.md)). Android runs the reference Rust `yrs`/`yffi` core through UniFFI-generated Kotlin bindings, so it merges against the same core the ecosystem uses; a short UniFFI integration + conformance spike confirms interop during Phase 1 ([19](19-open-questions.md)).
