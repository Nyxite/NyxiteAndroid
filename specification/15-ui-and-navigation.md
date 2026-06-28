# 15 — UI & Navigation

Jetpack Compose + Material 3, adaptive for phone/tablet/foldable, with the ink editor as the showcase. Visual identity uses the Nyxite icon/brand assets from the master repo.

## 15.1 Navigation graph

Type-safe Navigation-Compose with `@Serializable` routes. Top-level destinations:

- `AuthGraph` — `Login` (native password/passkey by default, with enterprise SSO as an optional path), `TotpStep` (native TOTP entry; delegated to the Keycloak Custom Tab on enterprise instances), `DeviceEnroll`, `DeviceApprove`, `RecoverySetup`, `RecoveryRestore`.
- `BrowseGraph` — `Projects`, `ProjectDetail(projectId)`, `Folder(folderId)` (file/folder list), `Search`.
- `EditorGraph` — `TextEditor(fileId)`, `InkEditor(fileId)` (chosen by `contentType`).
- `HistoryGraph` — `Versions(fileId)`, `DiffViewer(fileId, fromSeq, toSeq)`.
- `ShareGraph` — `ShareManage(targetType, targetId)`, `OpenLink(token)` (deep-link entry).
- `SettingsGraph` — `Settings`, `Security`, `Devices`, `Storage`, `Accounts` (add / switch / remove accounts, each possibly a different instance host), `About`.

**Account switcher**: because the app is **multi-account from v1.0.0** ([14 §14.7](14-authentication.md)), a persistent account switcher (in the top app bar / nav drawer header) shows the active account and lets the user switch or add one. Switching re-roots `BrowseGraph` to the newly active account's data and tears down the prior account's in-memory session ([01 §1.8](01-architecture.md)).

**Deep links**: `https://nyxite.app/share/{token}` (App Links) → `OpenLink`, with the `#k=` fragment parsed locally ([13](13-sharing.md)). On enterprise instances, the OIDC redirect URI is handled by AppAuth.

**Adaptive**: on `Expanded`/`Medium` width, use a list-detail scaffold (browse list + open file in the detail pane); on `Compact`, single-pane with back navigation. Predictive back supported.

## 15.2 Key screens

| Screen | Contents |
|--------|----------|
| **Projects / Folder list** | Decrypted names, folders + files, content-type icons, sync/cache badges ([08 §8.8](08-sync-engine.md)), create/move/rename/delete, **keep-on-device toggle at file/folder/project (cascading)** ([16 §16.2](16-offline-and-storage-policies.md)), exclude-from-sync, search box, pull-to-refresh (delta sync). |
| **Text editor** | View/edit toggle, formatting toolbar (markdown), presence chips + remote carets ([09](09-realtime-collaboration.md)), connection state, snapshot/history/share actions. |
| **Ink editor** | Canvas (low-latency), tool palette (pen/highlighter/eraser, color, width), pages, undo/redo, zoom/pan, palm rejection; share/history. |
| **Version history** | Timeline, author, fetch+view a version, diff two versions, restore ([12](12-version-history.md)). |
| **Share manage** | Account + link shares, create/revoke, permissions/expiry, key fingerprint ([13](13-sharing.md)). |
| **Search** | Query box, results with snippets + scope hint ([11](11-search.md)). |
| **Settings** | Keep-on-device defaults, convenience-cache toggle/limit + clear, storage usage breakdown ([16 §16.3](16-offline-and-storage-policies.md)), security (app lock, biometric, secure window), devices, recovery-key status, accounts (add/switch/remove), per-account instance host config. |
| **Onboarding/enrollment** | Login, device enroll/approve, recovery-key creation with hard warnings ([07](07-key-and-device-management.md)). |

## 15.3 Status surfacing

- Compact per-file badges for sync state (synced/pending/offline/conflict/rotating/error) and cache state (cached offline / title-only). Tapping explains and offers actions.
- Global indicators: offline banner, "N changes pending", relay connection state in editors.
- Conflicts (LWW ink/binary) surfaced as a non-destructive "two versions" prompt linking to history ([12 §12.5](12-version-history.md)).

## 15.4 Theming & brand

- Material 3 with a Nyxite color scheme; dynamic color optional. Dark theme first-class (notes app, night use; fits the "Nyx" night branding).
- App icon and adaptive foreground from `Nyxite/icons/android` ([03 §3.5](03-project-structure.md)).
- Typography legible for long-form reading and code; respect system font scaling.

## 15.5 Accessibility & input

- TalkBack labels on all controls; sufficient contrast; large-text support; touch targets ≥48dp.
- Full keyboard support (external keyboards on tablets) and stylus + finger coexistence in the ink editor ([10 §10.4](10-editors.md)).
- Respect reduced-motion and the secure-window/screenshot setting ([17](17-security.md)).

## 15.6 Empty/error/loading states

Every screen defines explicit loading, empty (no projects/files/results), offline, locked (needs unlock/enrollment), and error states; no silent blank screens.
