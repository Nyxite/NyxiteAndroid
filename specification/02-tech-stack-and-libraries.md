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
| Markdown render | **Markwon** (Android views via `AndroidView`) | View-mode rendering ([10](10-editors.md)); chosen for v1.0.0 as the mature, battle-tested option. A Compose-native renderer is a later swap once that ecosystem matures. |

> Design tokens (shared source of truth): colors, typography, spacing, radius, shadow, and motion are **not hand-defined here** — they derive from the platform-agnostic `nyxite-tokens.json` in the shared [`NyxiteDesign`](https://github.com/Nyxite/NyxiteDesign) system. A CI token-build pipeline generates the Android artifacts (`res/values/colors.xml` + `res/values-night/colors.xml`, optionally Compose `Color` constants; [03 §3.5](03-project-structure.md)) that the `MaterialTheme` color scheme consumes, so this client never drifts from the brand (deep-purple accent, Manrope UI + Source Serif 4 document type; [15 §15.4](15-ui-and-navigation.md)). See the master tracker's Live decision [OPEN-DECISIONS DS](https://github.com/Nyxite/Nyxite/blob/main/docs/OPEN-DECISIONS.md).

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
| Auth (native, default) | **Direct API** + **Credential Manager** (`androidx.credentials`) | Password+TOTP submitted to the Nyxite server over TLS, and passkeys (WebAuthn); the default login path ([14 §14.1](14-authentication.md)). |
| OIDC/auth (enterprise option) | **AppAuth-Android** (`net.openid:appauth`) | Authorization Code + PKCE for the **enterprise Keycloak** tier only, resolving to the server's internal token; not used by native login ([14 §14.1](14-authentication.md)). |

> SignalR note: the Java client depends on RxJava and uses its own JSON. Wrap it behind `RelayClient` and bridge RxJava `Single`/`Completable` to coroutines (`kotlinx-coroutines-rx3`). Validate early as part of the relay spike ([19](19-open-questions.md)).

## 2.7 Cryptography

All primitives must match the server's algorithm table ([server 07 §7.3](https://github.com/Nyxite/NyxiteServer); reproduced in [06 §6.2](06-cryptography.md)).

| Purpose | Library | Notes |
|---------|---------|-------|
| AEAD (AES-256-GCM), classical X25519 / Ed25519 halves | **Google Tink (Android)** | Handles the AES-GCM content path and the **classical** halves of the hybrid asymmetric suite; integrates with Android Keystore for key wrapping. |
| **Hybrid HPKE (X25519 + ML-KEM-768) and hybrid signatures (Ed25519 + ML-DSA-65)** — NIST level 3 | **⚠ FOLLOW-UP / DEPENDENCY — not yet chosen.** Needs a **hybrid-capable HPKE + hybrid-signature (PQC) library** on Android/Kotlin/JNI. **Tink does NOT provide ML-KEM or ML-DSA**, so this is a required new dependency. | Post-quantum hybrid ships at **v1.0.0** ([06 §6.2](06-cryptography.md)), so it cannot be deferred. Candidate paths (choose at impl time, **do not assume either yet**): expose ML-KEM-768/ML-DSA-65 through the **existing Rust core over UniFFI** (per [#12](19-open-questions.md) the app already wraps `yrs`/`yffi` via UniFFI — a Rust PQC crate could ride the same JNI bridge), **or** a JVM PQC library. Whatever is chosen must produce the **exact hybrid suite `X25519MLKEM768`** and interop byte-for-byte with the server/desktop/web via shared conformance vectors ([18 §18.5](18-build-ci-testing.md), [06 §6.4](06-cryptography.md)). |
| BLAKE3-256 (content addressing) | **A maintained BLAKE3 JVM/Kotlin lib** (e.g. a JNI or pure-Kotlin BLAKE3) | Confirmed dependency (approach settled); not in Tink. The specific artifact is chosen at impl time — pick one with test vectors and benchmark for large blobs. |
| Argon2id (recovery-key derivation) | **argon2-jvm** (`de.mkammerer:argon2-jvm`, libsodium JNI) or equivalent | Confirmed dependency (approach settled). Parameters come from `recovery_blobs.kdf_params`; verify constant-time and arm64 binaries. |
| Hardware-backed key storage | **Android Keystore** (`AndroidKeyStore` provider) + **StrongBox** when available | Wraps the DB master key and the identity-key store ([07](07-key-and-device-management.md)). |
| Biometric unlock | **`androidx.biometric`** | Gate app/key unlock with device credential/biometric ([17](17-security.md)). |

> Crypto-agility: the encrypted frame carries `version` and `key_id`/generation and every wrap carries an `alg_id` (v1.0.0 pins the hybrid suite `X25519MLKEM768`), so primitives can be rotated by re-wrapping only the small keys without touching content ([06 §6.2](06-cryptography.md), [§6.3](06-cryptography.md)). Wrap every primitive behind the `CryptoEngine` interface so the hybrid-PQC library above can be swapped without touching repositories.

## 2.8 CRDT

| Concern | Choice | Risk |
|---------|--------|------|
| Text CRDT | **yrs (UniFFI-generated Kotlin bindings)** | Android wraps the **reference Rust `yrs`/`yffi` core** (the same core desktop's ydotnet builds on) through **Mozilla UniFFI** — generated Kotlin bindings, no hand-written unsafe JNI glue, Kotlin-Multiplatform-capable. Using the reference core keeps binding-maturity risk low (the previously-planned standalone Kotlin binding was dropped as officially inactive). Cost: the UniFFI/JNA runtime + codegen. A **short integration + conformance-vector spike** validates wire-protocol interop with Yjs/ydotnet ([09 §9.7](09-realtime-collaboration.md), [18](18-build-ci-testing.md), [19](19-open-questions.md)). |

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
