# 17 — Security (Device-Side)

The server-side threat model is covered by [server 13](https://github.com/Nyxite/NyxiteServer) (zero-knowledge: DB/blob theft, malicious operator, curious admin all yield no content). This document covers the **device-side** threats the Android client must defend, since on a mobile device the plaintext and keys *do* live locally.

## 17.1 Threats considered

| Threat | Mitigation |
|--------|------------|
| Lost/stolen unlocked device | App lock (biometric/device credential), secure window, short auto-lock timeout. |
| Lost/stolen device at rest | Keystore/StrongBox-wrapped DB master + identity keys; SQLCipher; cache in `noBackupFilesDir`. |
| Malicious/curious server or operator | E2EE: only ciphertext leaves the device; BLAKE3 verify on download; hybrid Ed25519 + ML-DSA-65 verify on directory entries/updates. |
| Harvest-now-decrypt-later (future quantum adversary) | Every asymmetric seam is **hybrid classical + PQC** at v1.0.0 — HPKE key-wrap uses X25519 + ML-KEM-768; signatures use Ed25519 + ML-DSA-65 (NIST level 3, [06 §6.2](06-cryptography.md)) — so the server's indefinitely-stored wrapped keys stay confidential unless **both** halves break. |
| Tampering/withholding relay | Content-address verification + signature checks; updates that don't verify are rejected. |
| Backup exfiltration | `allowBackup=false` or strict backup rules excluding DB/cache/keys. |
| Screenshot/screen-record/overview thumbnail | `FLAG_SECURE` on sensitive windows (configurable). |
| Clipboard leakage | Mark sensitive copies as such; avoid auto-copying keys; clear share links from clipboard after a timeout where feasible. |
| Other apps / IPC | No exported components handling content; deep links validated; no content in logs/intents. |
| Memory scraping | Zeroize key buffers where possible; prefer Keystore-backed handles; hold unwrapped keys only in the `UserSession`. |

## 17.2 Key & data protection

- **Root of trust**: a hardware-backed `AndroidKeyStore` key (StrongBox when available) wraps the DB master key and the identity-key store ([07 §7.2](07-key-and-device-management.md)). Configure `setUserAuthenticationRequired` per the user's app-lock setting — **default on with a 10-minute validity window** (configurable 1–60 min, or per-use), consistent with [07 §7.2](07-key-and-device-management.md) and [§17.3](#173-app-lock) — using `BiometricPrompt` + `CryptoObject` to unlock.
- **DB at rest**: SQLCipher with the Keystore-protected passphrase; covers structure, decrypted names, metadata, and the FTS index. With multi-account ([14 §14.7](14-authentication.md)) there is **one DB and one master key per account**, so a compromise scoped to one account's key cannot read another account's data; account removal destroys that account's DB, cache, index, tokens, and Keystore-wrapped keys.
- **Blob cache**: ciphertext is safe as-is; decrypted pinned plaintext lives in app-private storage and is acceptable because the DB/keys protecting access are themselves gated — but treat it as sensitive (no backup, evictable, cleared on "forget device").
- **Tokens**: the server's access/refresh tokens in `EncryptedSharedPreferences`/Keystore-wrapped, never in Room/logs ([14](14-authentication.md)).
- **Recovery key & fragment keys**: never persisted in plaintext; recovery phrase shown once with copy/confirm and hard warnings ([07 §7.4](07-key-and-device-management.md)).

## 17.3 App lock

- Optional but recommended on by default: biometric/device-credential prompt on cold start and after a configurable idle timeout, before the DB/identity key is unlocked. This ties to the Keystore key's **validity window (default 10 min)** that also governs unattended background content sync ([07 §7.9](07-key-and-device-management.md), [19 §19.2](19-open-questions.md)).
- A locked app shows no content (and ideally a secured placeholder in the overview/recents).

## 17.4 Network security

- TLS validation against the public chain; optional **certificate/public-key pinning** (OkHttp `CertificatePinner`) with documented rotation ([05 §5.1](05-api-client.md)). Provide a `network_security_config.xml` (no cleartext, pinning entries).
- Never trust the server to have validated content; verify locally ([06](06-cryptography.md),[08 §8.9](08-sync-engine.md)).

## 17.5 Logging & telemetry

- A scrubbing `Logger` facade: never log plaintext, keys, tokens, fragments, recovery phrases, or full share URLs. Ciphertext sizes/IDs are acceptable for diagnostics.
- No third-party content-touching analytics. Any crash reporting is opt-in and PII-stripped ([02 §2.9](02-tech-stack-and-libraries.md)).

## 17.6 Secure UI

- `FLAG_SECURE` (configurable) on editor/viewer/recovery/key screens to block screenshots and hide from the recents thumbnail.
- Hide content under app lock; clear sensitive Compose state on lock.
- Validate all deep-link inputs (token format, fragment decode) before use; reject malformed links.

## 17.7 Build & supply chain

- R8/proguard with keep rules for crypto/CRDT/serialization; do not obfuscate away security checks.
- Gradle dependency verification (checksums/signatures), pinned versions, dependency scanning in CI ([18](18-build-ci-testing.md)).
- Reproducible-ish release builds; signed with a securely stored upload/app-signing key ([18 §18.4](18-build-ci-testing.md)).

## 17.8 Honest limits

- Already-downloaded content can't be recalled after a share revocation (rotation only protects future content, [13 §13.5](13-sharing.md)).
- A rooted/compromised device can defeat at-rest protections; the app raises the bar (Keystore/StrongBox, biometric) but cannot fully protect against a privileged on-device attacker.
- v1.0.0 directory trust is TLS + hybrid Ed25519 + ML-DSA-65 signatures, not full key transparency ([13 §13.6](13-sharing.md)); deferred to Phase 6.

## 17.9 Support plane — the one consensual non-E2EE exception

In-app bug reporting ([15 §15.7](15-ui-and-navigation.md)) runs on the project's **single, deliberate exception to zero-knowledge**: a **consensual, non-E2EE support plane** that a user enters only by an explicit action, provably **disjoint from the content plane** (SUP-1). It is safe because it does not weaken content E2EE:

- A report carries **no content key and no content-plane ciphertext** — only the free text, the redacted screenshot, and a user-reviewed diagnostic envelope; the content-plane zero-knowledge guarantee is **untouched**. The load-bearing protections here are the explicit non-E2EE + "goes to the Nyxite maintainer" notice + GDPR shown before send, and the user's own redaction — **not** encryption (reports are stored server-readable by the maintainer, SUP-1/SUP-8).
- **Screenshot redaction is destructive and client-side (SUP-2):** black-box + blur are **flattened into the pixels (re-encoded PNG, EXIF stripped) before upload**; the original image and any redaction mask are **never sent** — no peel-back layer. Capturing the current view for a report is a deliberate self-capture the user initiates, distinct from the `FLAG_SECURE` screenshot-blocking above.
- The client **never contacts the helpdesk directly**; it relays through its own `NyxiteServer` as an authenticating relay (SUP-7). Detail: master feature [support.md](https://github.com/Nyxite/Nyxite), [NyxiteSupport `specification/02`](https://github.com/Nyxite/NyxiteSupport), [OPEN-DECISIONS SUP-1–SUP-13](https://github.com/Nyxite/Nyxite).
