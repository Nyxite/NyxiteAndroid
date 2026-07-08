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
- `SupportGraph` — `ReportBug`, `MyTickets` (present **only when the server advertises `support.enabled`**; see [§15.7](#157-bug-reporting--support)).

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

- Material 3 color scheme **generated from `NyxiteDesign`'s `nyxite-tokens.json`** (deep-purple accent — #4C1D95 light / #8B5CF6 dark — via generated `colors.xml` + `values-night` consumed by `MaterialTheme`; [02 §2.2](02-tech-stack-and-libraries.md), [03 §3.5](03-project-structure.md)); dynamic color optional. Dark theme first-class (notes app, night use; fits the "Nyx" night branding).
- App icon and adaptive foreground from `Nyxite/icons/android` ([03 §3.5](03-project-structure.md)).
- **Implements the shared NyxiteDesign system** ([OPEN-DECISIONS DS](https://github.com/Nyxite/Nyxite/blob/main/docs/OPEN-DECISIONS.md)): **Layer A** = the tokens + standard Material 3 composables; **Layer B** = the app shell/editor (doc-type switcher, toolbar densities, canvas). Several Layer-B shell primitives map directly onto stock Compose M3 — `NavigationRail`, `SegmentedButton`, assist/filter chips, `NavigationBar` (bottom tab), and `FloatingActionButton` — so build on those rather than reinventing them.
- Typography from the tokens — **Manrope** (UI) + **Source Serif 4** (document content) — legible for long-form reading and code; respect system font scaling. **Fonts and all assets are bundled/self-hosted in the app** (bundled font resources); **never** use Android's downloadable-fonts mechanism or any Google Fonts / external CDN at runtime — it leaks IP/timing and is incompatible with the no-Google, zero-knowledge stance ([17](17-security.md)).

## 15.5 Accessibility & input

- TalkBack labels on all controls; sufficient contrast; large-text support; touch targets ≥48dp.
- Full keyboard support (external keyboards on tablets) and stylus + finger coexistence in the ink editor ([10 §10.4](10-editors.md)).
- Respect reduced-motion and the secure-window/screenshot setting ([17](17-security.md)).

## 15.6 Empty/error/loading states

Every screen defines explicit loading, empty (no projects/files/results), offline, locked (needs unlock/enrollment), and error states; no silent blank screens.

## 15.7 Bug reporting & support

In-app bug reporting routing to the maintainer-run `NyxiteSupport` helpdesk. This runs on the project's **one deliberate, consensual non-E2EE support plane**, disjoint from the content plane — see [17 §17.9](17-security.md), the master feature [support.md](https://github.com/Nyxite/Nyxite), the [NyxiteSupport `specification/02`](https://github.com/Nyxite/NyxiteSupport), and [OPEN-DECISIONS SUP-1–SUP-9](https://github.com/Nyxite/Nyxite).

- **Capability-gated surface (SUP-9).** The **"Report a bug"** entry (in the nav drawer / `About` / help affordances) and the **"My tickets"** view appear **only when the server advertises the `support.enabled` capability flag** (v1 = the maintainer's official instance(s) only). Where the flag is absent the surfaces are **simply absent** — no disabled control, no hint.
- **Report composer.** Free-text **title + description**, plus an optional screenshot and the diagnostic envelope below.
- **Screenshot capture + destructive redaction (SUP-2).** Optional capture of the current view via **`PixelCopy`** (or `MediaProjection` where a wider grab is needed), opened in a redaction editor offering **black-box** and **blur** tools. Redaction is **destructive and client-side**: the redacted regions are **flattened into the pixels (re-encoded PNG) before upload**, EXIF/metadata stripped; the **original image and any redaction mask are never sent** — there is no peel-back layer. Redaction is manual (the user decides what to hide).
- **Consent + destination notice before send (SUP-1).** Before a report can transmit, the user must confirm a clear notice that — **unlike their files — this report is *not* end-to-end encrypted and goes to the Nyxite maintainer**, shown alongside a GDPR disclosure. A report carries **no content key and no content-plane ciphertext**.
- **User-reviewable, editable diagnostic envelope.** A non-content technical bundle shown for **review + edit** before send: app version/build, platform/OS, locale, the **current screen/route id** (a UI location, never a file/project name or any content), **scrubbed** client-side error logs / recent stack traces, and coarse connection/relay state. Nothing is attached that is not represented in this envelope.
- **"My tickets" view (SUP-3).** An account user sees their own reports' status, the operator's **public replies**, and gets **in-app notifications** of updates — a genuine two-way thread, not fire-and-forget. Guests (share/guest sessions) instead supply an email + optional name and receive replies via email + a tokenized no-login link (SUP-6).
- **Submission via the server as an authenticating relay (SUP-7).** The client **never contacts the helpdesk directly**: it submits to its own `NyxiteServer`, which authenticates the submitter and relays to `NyxiteSupport` tagged with the instance fingerprint + an opaque user reference. Submission is best-effort and off the critical path; a transport failure surfaces a retryable error and never blocks the app.
