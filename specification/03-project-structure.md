# 03 — Project Structure

## 3.1 Gradle module graph

Multi-module to enforce the layering in [01](01-architecture.md) at compile time (a UI module physically cannot import a network client; a network module physically cannot import crypto/domain-content types) and to keep builds fast.

```
NyxiteAndroid/
├── settings.gradle.kts
├── gradle/libs.versions.toml          # single version catalog
├── build-logic/                       # convention plugins (android-library, compose, hilt, kotlin)
│
├── app/                               # application module: DI graph, navigation host, manifest, services
│
├── core/
│   ├── core-model/                    # pure Kotlin domain entities, enums, value types (no Android)
│   ├── core-common/                   # Result types, DispatcherProvider, time, base64url, logging facade
│   ├── core-crypto/                   # CryptoEngine API + Tink/hybrid-PQC(ML-KEM-768,ML-DSA-65)/BLAKE3/Argon2 impl; framing; addressing
│   ├── core-crdt/                     # CrdtEngine API + yrs (UniFFI) impl; snapshots, state vectors
│   ├── core-keystore/                 # KeyStoreVault: Android Keystore/StrongBox wrapping
│   ├── core-database/                 # Room + SQLCipher: entities, DAOs, FTS, migrations
│   ├── core-network/                  # OkHttp/Retrofit ApiClient, SignalR RelayClient, DTOs (ciphertext-only)
│   ├── core-datastore/                # Proto DataStore prefs + secret storage
│   └── core-ui/                       # Compose theme, Material3, design tokens, shared components, ink canvas widgets
│
├── domain/                            # use cases + repository interfaces (depends on core-model only)
│
├── data/                              # repository implementations (depends on domain + all core-*)
│   ├── data-structure/               # projects/folders/files CRUD + tree
│   ├── data-file/                    # content read/write, blob cache, addressing
│   ├── data-sync/                    # manifest/delta engine, WorkManager workers, state machine
│   ├── data-collab/                  # relay session orchestration, awareness, snapshot triggers
│   ├── data-keys/                    # identity/device/recovery, key directory, rotation
│   ├── data-share/                   # account + link shares, revocation
│   ├── data-search/                  # FTS indexer/queries
│   └── data-version/                 # version listing, diff, restore
│
└── feature/                          # Compose screens + ViewModels (depend on domain + core-ui)
    ├── feature-auth/                 # login, TOTP handoff, device enrollment, recovery
    ├── feature-browse/               # project/folder/file navigation
    ├── feature-editor-text/          # markdown + plaintext editor (view/edit)
    ├── feature-editor-ink/           # S-Pen ink editor
    ├── feature-collab/               # presence/awareness overlays
    ├── feature-share/                # create/manage shares, open link, guest mode
    ├── feature-history/              # version history + diff + restore
    ├── feature-search/               # search UI
    └── feature-settings/             # config, keep-on-device, storage usage, accounts, security
```

### Dependency rules (enforced by convention plugins + architecture test)

- `feature-*` → `domain`, `core-ui`, `core-common`, `core-model`. **Never** → `data-*`, `core-network`, `core-database`, `core-crypto`.
- `data-*` → `domain`, `core-*` (all). They are the only modules wiring crypto + network + db together.
- `domain` → `core-model`, `core-common` only. No Android, no IO.
- `core-network` must **not** depend on `core-crypto`, `core-crdt`, or `core-model` content types (it speaks bytes/DTOs only).
- `app` → everything (composition root, Hilt graph, navigation host).

## 3.2 Package naming

Root package: `app.nyxite.android` (matches `nyxite.app`, reversed). Per-module sub-packages mirror the module name, e.g. `app.nyxite.android.core.crypto`, `app.nyxite.android.feature.editor.text`, `app.nyxite.android.data.sync`.

Application ID: `app.nyxite.android` (release). Debug/internal builds use a suffix (`.debug`) so they coexist on a device ([18](18-build-ci-testing.md)).

## 3.3 Naming conventions (code)

- Match the master `docs/NAMING.md`: product is **Nyxite**; primary domain `nyxite.app`.
- Classes: `PascalCase`; the public API of each `core-*` engine is an interface (`CryptoEngine`, `CrdtEngine`, `RelayClient`) with an impl named `*Impl` or `Tink*`/`Yrs*`/`SignalR*` to signal the backing tech.
- Use cases: verb-first (`OpenFileForEdit`), `operator fun invoke`.
- DTOs (network): suffix `Dto`; map to/from domain entities in `data-*` mappers — DTOs never escape `core-network`/`data-*`.
- Room entities: suffix `Entity`; never exposed above the data layer.
- Compose: screen composable `XScreen`, stateful host `XRoute`, state `XUiState`, intent `XIntent`, effect `XEffect`, view model `XViewModel`.

## 3.4 Convention plugins (`build-logic`)

Provide reusable Gradle plugins so each module's build file is a few lines:

- `nyxite.android.library` — `com.android.library` + Kotlin + common compile options + lint.
- `nyxite.android.library.compose` — adds Compose + Material3 + compiler config.
- `nyxite.android.hilt` — KSP + Hilt.
- `nyxite.android.feature` — composes the compose + hilt + navigation deps for feature modules.
- `nyxite.jvm.library` — pure-Kotlin (for `domain`, `core-model`, `core-common`) so they compile/test on the JVM.

## 3.5 Resource & asset strategy

- Icons ship from the master [`Nyxite/icons/android`](https://github.com/Nyxite/Nyxite) set (`mipmap-*`, adaptive `ic_launcher_foreground`, Play Store icon). Wire them in `app`.
- **Design-system color resources are generated, not hand-edited.** `res/values/colors.xml` and `res/values-night/colors.xml` (plus any Compose `Color` constants) are **build outputs** of the CI token-build pipeline that reads `NyxiteDesign`'s `nyxite-tokens.json`; the `MaterialTheme` color scheme in `core-ui` consumes them ([02 §2.2](02-tech-stack-and-libraries.md), [OPEN-DECISIONS DS](https://github.com/Nyxite/Nyxite/blob/main/docs/OPEN-DECISIONS.md)). Never edit these by hand. Fonts (Manrope, Source Serif 4) ship as **bundled font resources** in-app — never via Android downloadable fonts or any external CDN ([15 §15.4](15-ui-and-navigation.md)).
- Strings centralized for future localization; no user content in resources.
- Conformance test vectors (CRDT wire, crypto KATs) live under `core-crdt/src/test/resources` and `core-crypto/src/test/resources` ([18](18-build-ci-testing.md)).

## 3.6 Repository files

The `android` repo keeps, per the org convention ([master `docs/LICENSING-AND-REPOS.md`](https://github.com/Nyxite/Nyxite)): `README.md`, `FEATURES.md`, `LICENSE.md` (PolyForm Noncommercial 1.0.0 with filled copyright), this `specification/` folder, and the Gradle project.
