# 18 — Build, CI & Testing

## 18.1 Build setup

- **Gradle 8.x** + **AGP 8.x**, Kotlin DSL, single **version catalog** (`gradle/libs.versions.toml`), **convention plugins** in `build-logic` ([03 §3.4](03-project-structure.md)).
- **KSP** for Room, Hilt, kotlinx.serialization. **Compose compiler** via the Kotlin Compose plugin.
- Build types: `debug` (app id suffix `.debug`, debuggable, test instance host), `release` (R8/shrink + resource shrink, signed). Optional `internal` flavor for QA.
- `network_security_config.xml`, strict backup rules, `allowBackup` policy ([17](17-security.md)).

## 18.2 Configuration

- Instance host (API base, public-share base, plus the OIDC authority on enterprise instances) configurable via build config + runtime settings ([05](05-api-client.md),[14](14-authentication.md)); default `nyxite.app`. No secrets in the APK; on enterprise instances the only embedded value is the OIDC public client id (PKCE, no client secret on device).

## 18.3 Module build hygiene

- Architecture/layering enforced by a **konsist** (or custom) JVM test: feature modules must not depend on data/network/crypto; network must not depend on crypto/CRDT/content models ([01 §1.2](01-architecture.md)).
- ktlint + detekt + Android Lint gate the build.

## 18.4 Signing & release

- App signing via Play App Signing; upload key stored securely (CI secret/HSM). Never commit keystores.
- Versioning: SemVer for the app; align milestone tags to roadmap phases ([20](20-roadmap.md)).
- Distribution: Play Store and/or direct APK for self-hosters (the server is self-hosted; some users will sideload). Document configuring a custom instance host.

## 18.5 Test strategy (the critical surfaces first)

| Layer | Tools | Focus |
|-------|-------|-------|
| **Crypto conformance** | JUnit + shared KAT/cross-client vectors | **AES-GCM framing, HPKE wrap/unwrap, Ed25519, X25519, BLAKE3, Argon2id** must interop byte-for-byte with server/desktop/web. Wrap on Android → unwrap on server impl and vice-versa. **Highest priority.** ([06 §6.9](06-cryptography.md)) |
| **CRDT conformance** | JUnit + shared Yrs wire vectors | the yrs (UniFFI) binding must produce identical merged state and encoded updates vs Yjs/ydotnet. Mirrors server `CrdtConformanceTests`. **Validate via the short UniFFI integration + conformance spike.** ([09 §9.10](09-realtime-collaboration.md)) |
| Domain | JUnit5, MockK | Use cases, policy logic, conflict/sync state machine — pure JVM. |
| Data | Room in-memory/instrumented, MockWebServer | DAOs, migrations (asserted), API mapping, error mapping, outbox/idempotency, delta/manifest reconcile. |
| ViewModels | Turbine, coroutines-test | MVI state/intent/effect. |
| UI | Compose UI test, screenshot tests | Screens, editor interactions, navigation, deep links. |
| Ink | Instrumented + sample MotionEvent streams | Stroke capture (pressure/tilt), serialization round-trip + stable BLAKE3 address, render. |
| E2E | Instrumented against a test server (Testcontainers-backed or staging) | Login → enroll → create → sync → collaborate → share → revoke/rotate → restore. |

## 18.6 Conformance vector sharing

- Maintain shared test-vector files (crypto KATs, HPKE pairs, CRDT update sequences) co-owned with the server/desktop/web repos; check them into `core-crypto`/`core-crdt` test resources and update in lockstep when the protocol changes. A protocol change that breaks a vector must break CI on all clients.

## 18.7 CI pipeline

- On PR: assemble debug, run ktlint/detekt/lint, unit tests, architecture test, crypto + CRDT conformance, Room migration tests. On main/tags: instrumented tests on emulators (`minSdk 36` / API 36+ images, incl. StrongBox-capable images where possible) plus real-device runs for ink/keystore, build signed release, dependency verification + vulnerability scan.
- Fail fast on any conformance regression — interop is non-negotiable in an E2EE multi-client system.

## 18.8 Early validation spikes (do these first)

1. **yrs (UniFFI) spike** — short UniFFI integration + conformance-vector spike: interop + performance with real collaboration over the reference `yrs`/`yffi` core ([19](19-open-questions.md)).
2. **Tink HPKE suite-id check** — confirm Tink HPKE matches the server's X25519+HKDF-SHA256+AES-256-GCM exactly.
3. **SignalR-on-Android spike** — the Java client + RxJava→coroutines bridge, reconnection, share-token upgrade.
4. **Ink latency spike** — Jetpack Ink + motion prediction + low-latency surface on real S-Pen hardware.
5. **Argon2id-on-mobile spike** — parameters that finish in a few seconds without OOM on mid-range devices.
