# 10 — Editors

Three content forms, each with a distinct **view mode** and **edit mode**: markdown, plain text, and handwritten ink. The ink editor is the reason the client is native ([00 §0.1](00-overview.md)).

## 10.1 Shared editor scaffold

- A `feature-editor-*` screen is driven by an `EditorViewModel` exposing `EditorUiState` (mode, decrypted content, sync/collab state, peers/awareness) and intents (`EnterEditMode`, `ApplyEdit`, `ToggleView`, `Snapshot`, `Pin`, `Share`, `Back`).
- Opening a file: `OpenFileForEdit` resolves the FK ([07](07-key-and-device-management.md)), bootstraps content (snapshot + CRDT log for text; latest blob for ink), and (for text) joins the relay room ([09](09-realtime-collaboration.md)).
- Saving is **continuous** for CRDT text (every edit is an update); for ink it is an explicit/auto `PUT` of the encrypted page ([08 §8.5](08-sync-engine.md)).
- View ↔ edit toggle is instant and preserves scroll/caret. View mode is the default for shared/received files.

## 10.2 Markdown editor

- **Edit mode**: a Compose text field over the CRDT-backed document. Local keystrokes apply to the Yrs doc → encrypted update → relay; inbound updates re-render. Provide a lightweight formatting toolbar (bold/italic/heading/list/link/code/checkbox) that inserts markdown syntax, and optional live inline styling.
- **View mode**: rendered markdown via Markwon (`AndroidView`) or a Compose markdown renderer ([02 §2.2](02-tech-stack-and-libraries.md)) — headings, lists, tables, code blocks, task lists, links, images (images only when the referenced blob is available/decrypted). No remote network fetch from markdown content (privacy): only embedded/attached encrypted blobs render.
- **Collaboration overlays**: remote carets/selections from awareness ([09 §9.7](09-realtime-collaboration.md)); presence chips in the app bar.
- **Large documents**: virtualize rendering; debounce the markdown parse in view mode.

## 10.3 Plain-text editor

Same CRDT backbone and collaboration as markdown, without rendering. Monospace optional; used now for `plaintext` and later for `sourcecode` (Phase 5) where syntax highlighting can be added in view mode.

## 10.4 Ink editor (S-Pen)

The differentiator. Goal: **low-latency, pressure/tilt-aware** handwriting that round-trips faithfully and stores in a shared encrypted vector format.

### Capture
- Use **Jetpack Ink** (`androidx.ink`) for stroke authoring/rendering, **`input-motionprediction`** to reduce perceived latency, and the **low-latency front-buffered surface** (`graphics-core`) so wet ink appears under the pen with minimal delay.
- Read stylus data from `MotionEvent`: position, `AXIS_PRESSURE`, `AXIS_TILT`, `AXIS_ORIENTATION`, and **historical samples** (`getHistoricalAxisValue`) to capture every sub-frame point. Distinguish stylus vs finger (`getToolType`) to support palm rejection and finger-pan/zoom while the pen draws.
- Support eraser (S-Pen button / inverted stylus), undo/redo, pen/highlighter/eraser tools, color and width, and pressure→width mapping.

### Page model
- A file is a sequence of **pages**; each page holds **strokes**. A stroke is a polyline of timestamped sample points `(x, y, pressure, tilt, orientation, t)` plus tool/brush attributes (color, base width, blend).
- Editing operations: add stroke, erase stroke(s), move/transform selection, insert/delete page. v1.0.0 ink sync is **LWW per page/file** (not CRDT) — concurrent edits resolve by last-write-wins with both versions retained in history ([08 §8.5](08-sync-engine.md)).

### Rendering
- Render committed strokes from the page model; render the in-progress stroke on the low-latency surface, then commit. Smooth/segment strokes (e.g. Catmull-Rom / the Ink engine's smoothing) for natural curves. Honor pressure/tilt in width/opacity.

## 10.5 Ink storage format (shared, versioned, encryptable)

- Define a **Nyxite ink vector format** (`[P]`) — a compact, versioned, deterministic serialization (e.g. CBOR or a length-prefixed binary) of pages → strokes → samples + brush metadata. It must be **byte-stable** so the BLAKE3 content address is reproducible, and **shared with desktop** so an ink file drawn on Android opens on desktop and vice-versa ([master `docs/SPECIFICATION.md`](https://github.com/Nyxite/Nyxite)).
- The serialized page/file is **sealed with the FK** ([06 §6.3](06-cryptography.md)) and stored as an encrypted blob; the version-vector for LWW rides in encrypted `metadata_enc` ([04](04-local-data-model.md)).
- The format carries a `version` for forward evolution (new brush types, recognition data later). Spec the format in its own document once the desktop team co-designs it; this client must conform exactly.
- **Out of scope v1.0.0**: handwriting recognition / searchable ink text, Samsung `.sdoc` import (separate migration item) — but reserve a place in the format for recognized-text layers so ink can later feed the search index ([11](11-search.md)).

## 10.6 Performance & input quality

- Target 90–120 Hz where the panel supports it; keep the ink render path off the main-thread-blocking work; predict motion to hide latency.
- Batch sample persistence; do not seal/encrypt per sample — seal per committed stroke-set / page save.
- Provide haptics-off, palm rejection, and zoom/pan that don't interfere with the pen.

## 10.7 Accessibility & cross-cutting

- Text editors: scalable fonts, TalkBack labels, selection/clipboard, find-in-document (local).
- Ink: provide alternatives where possible; ensure controls are reachable without a stylus.
- All editors honor the **screenshot/secure-window** and lock settings ([17](17-security.md)).
