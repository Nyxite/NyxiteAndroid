# 02 — Tech Stack & Libraries

Concrete choices with rationale. Versions are **[P]** floors at time of writing; pin exact versions in the Gradle version catalog ([18](18-build-ci-testing.md)) and keep them current. Prefer the smallest well-maintained library that meets the need; every dependency in a zero-knowledge client is attack surface.

## 2.1 Language, build, baseline

| Concern | Choice | Notes |
|---------|--------|-------|
| Language | **Kotlin 2.0+** | K2 compiler; coroutines; `kotlinx` ecosystem. |
| JVM target | **17** | Required by recent AGP/Compose. |
| Build | **Gradle 8.x** + **AGP 8.x**, Kotlin DSL, **version catalog** (`libs.versions.toml`) | One catalog across modules. |
| Min/target SDK | `minSdk 36`, `target/compileSdk 36` | Matches the owner's `Fitness-Tracker` baseline; see [00 §0.4](00-overview.md). |
| Modularization | Multi-module (see [03](03-project-structure.md)) | Build speed + architectural enforcement. |

## 2.2 UI

| Concern | Choice | Rationale |
|---------|--------|-----------|
| UI toolkit | **Jetpack Compose** (BOM-managed) | Declarative, fits MVI; required for the custom ink canvas. |
| Design system | **Material 3** (`androidx.compose.material3`) + dynamic color | Modern, adaptive. |
| Adaptive layouts | **`androidx.compose.material3.adaptive`** (list/detail) + window size classes | Phone/tablet/foldable panes. |
| Navigation | **Navigation-Compose 2.8 type-safe** (`@Serializable` routes) | Compile-checked args; deep links for share URLs ([13](13-sharing.md),[15](15-ui-and-navigation.md)). |
| Image loading | **Coil 3** | Thumbnails/avatars (decoded from decrypted bytes only). |
| Markdown render | **Markwon** (Android views via `AndroidView`) *or* a Compose markdown renderer | View-mode rendering ([10](10-editors.md)); evaluate both, prefer a Compose-native renderer if mature. |

## 2.3 Ink / stylus (the differentiator)

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Ink engine | **Jetpack Ink** (`androidx.ink:ink-*`) | Official low-latency stroke authoring/rendering, pressure/tilt aware. |
| Motion prediction | **`androidx.input:input-motionprediction`** | Reduces perceived stylus latency. |
| Low-latency surface | **`androidx.graphics:graphics-core`** (low-latency front-buffer) | Sub-frame ink as the pen moves. |
| Raw stylus data | `MotionEvent` axes: `AXIS_PRESSURE`, `AXIS_TILT`, `AXIS_ORIENTATION`, `getHistorical*` | Captures S-Pen pressure/tilt and batched samples ([10 §10.4](10-editors.md)). |

The on-disk/on-wire **ink stroke format** is a Nyxite-defined, versioned, encryptable vector format shared with desktop ([10 §10.5](10-editors.md)); Jetpack Ink is the capture/render engine, not the storage format.

## 2.4 Concurrency & DI

| Concern | Choice |
|---------|--------|
| Async | **Kotlin Coroutines + Flow** |
| DI | **Hilt** (Dagger) |
| Background jobs | **WorkManager** (+ Hilt integration) |
| Foreground service | Standard `Service` (type `dataSync`) for active collaboration ([01 §1.6](01-architecture.md)) |

## 2.5 Local persistence

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Relational store | **Room** | Type-safe DAO, Flow queries, migrations. |
| At-rest encryption | **SQLCipher for Android** (`net.zetetic:sqlcipher-android`) via Room's `SupportFactory` | Whole-DB encryption; passphrase = Keystore-protected master key ([07](07-key-and-device-management.md),[17](17-security.md)). |
| Full-text search | **SQLite FTS5** (SQLCipher bundles FTS5) | On-device search index ([11](11-search.md)). |
| Key/value prefs | **DataStore (Proto)** for non-secret prefs; **EncryptedSharedPreferences**/Keystore-wrapped for secrets | Settings vs. secrets split. |
| Blob/file cache | App-private filesystem under `filesDir`/`noBackupFilesDir` | Cached ciphertext + decrypted ink/binary ([16](16-offline-and-storage-policies.md)). |

## 2.6 Networking

| Concern | Choice | Rationale |
|---------|--------|-----------|
| HTTP client | **OkHttp** | Interceptors (auth, retry, idempotency), connection pooling, TLS config/pinning. |
| REST binding | **Retrofit** | Typed `/api/v1` surface ([05](05-api-client.md)). |
| JSON | **kotlinx.serialization** | Matches Kotlin idioms; used for metadata DTOs (bodies are JSON for structure, binary for ciphertext). |
| Realtime | **SignalR Java client** (`com.microsoft.signalr`) | The server's relay is SignalR `RelayHub` ([09](09-realtime-collaboration.md)); the official Java client runs on Android (RxJava-based). |
| OIDC/auth | **AppAuth-Android** (`net.openid:appauth`) | Authorization Code + PKCE with Keycloak ([14](14-authentication.md)). |

> SignalR note: the Java client depends on RxJava and uses its own JSON. Wrap it behind `RelayClient` and bridge RxJava `Single`/`Completable` to coroutines (`kotlinx-coroutines-rx3`). Validate early as part of the relay spike ([19](19-open-questions.md)).

## 2.7 Cryptography

All primitives must match the server's algorithm table ([server 07 §7.3](https://github.com/Nyxite/server); reproduced in [06 §6.2](06-cryptography.md)).

| Purpose | Library | Notes |
|---------|---------|-------|
| AEAD (AES-256-GCM), HPKE (X25519+HKDF-SHA256+AES-256-GCM), Ed25519, X25519 | **Google Tink (Android)** | First-class HPKE and signature/hybrid primitives; integrates with Android Keystore for key wrapping. **Validate Tink's HPKE suite IDs match the server's exactly** ([06 §6.4](06-cryptography.md)). |
| BLAKE3-256 (content addressing) | **A maintained BLAKE3 JVM/Kotlin lib** (e.g. a JNI or pure-Kotlin BLAKE3) | Not in Tink; pick one with test vectors and benchmark for large blobs. **[P]** |
| Argon2id (recovery-key derivation) | **argon2-jvm** (`de.mkammerer:argon2-jvm`, libsodium JNI) or equivalent | Parameters come from `recovery_blobs.kdf_params`; verify constant-time and arm64 binaries. **[P]** |
| Hardware-backed key storage | **Android Keystore** (`AndroidKeyStore` provider) + **StrongBox** when available | Wraps the DB master key and the identity-key store ([07](07-key-and-device-management.md)). |
| Biometric unlock | **`androidx.biometric`** | Gate app/key unlock with device credential/biometric ([17](17-security.md)). |

> Crypto-agility: the encrypted frame carries `version` and `key_id`/generation, so primitives can be rotated without a format break ([06 §6.3](06-cryptography.md)). Wrap every primitive behind the `CryptoEngine` interface so a library can be swapped without touching repositories.

## 2.8 CRDT

| Concern | Choice | Risk |
|---------|--------|------|
| Text CRDT | **ykt / Yrs Kotlin binding** | The **highest-risk dependency.** It is the least battle-tested of the three Yrs bindings (Yjs web, ydotnet desktop), and now runs the full merge client-side. **Spike it before committing the architecture** — validate wire-protocol interop with Yjs/ydotnet via the shared conformance vectors ([09 §9.7](09-realtime-collaboration.md), [18](18-build-ci-testing.md), [19](19-open-questions.md)). Have a fallback plan (own JNI binding over `yffi`, or a thin Rust UniFFI wrapper) if the binding is inadequate. |

## 2.9 Observability & quality

| Concern | Choice |
|---------|--------|
| Logging | A thin `Logger` facade (e.g. `Timber`), with **content/key scrubbing** — never log plaintext, keys, tokens, or fragments ([17](17-security.md)). |
| Crash/perf | Self-hosted/opt-in only; **no third-party content-touching SDKs**. If used, strip PII; default off for privacy. **[P]** |
| Testing | JUnit5, Turbine (Flow), MockK, Robolectric, Compose UI test, Room/instrumented tests, Tink/CRDT conformance vectors ([18](18-build-ci-testing.md)). |
| Static analysis | ktlint + detekt + Android Lint + an architecture test (konsist) for layer/boundary rules ([01 §1.2](01-architecture.md)). |

## 2.10 Dependency policy

- Every dependency is reviewed for maintenance, license compatibility (PolyForm-noncommercial app, but dependencies must be redistributable), and whether it could exfiltrate content.
- No analytics/ad/attribution SDKs. No dependency that requires sending data off-device by default.
- Pin versions and checksums; enable Gradle dependency verification; scan in CI ([18](18-build-ci-testing.md)).
