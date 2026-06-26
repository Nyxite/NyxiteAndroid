# 17 — Security (Device-Side)

The server-side threat model is covered by [server 13](https://github.com/Nyxite/server) (zero-knowledge: DB/blob theft, malicious operator, curious admin all yield no content). This document covers the **device-side** threats the Android client must defend, since on a mobile device the plaintext and keys *do* live locally.

## 17.1 Threats considered

| Threat | Mitigation |
|--------|------------|
| Lost/stolen unlocked device | App lock (biometric/device credential), secure window, short auto-lock timeout. |
| Lost/stolen device at rest | Keystore/StrongBox-wrapped DB master + identity keys; SQLCipher; cache in `noBackupFilesDir`. |
| Malicious/curious server or operator | E2EE: only ciphertext leaves the device; BLAKE3 verify on download; Ed25519 verify on directory entries/updates. |
| Tampering/withholding relay | Content-address verification + signature checks; updates that don't verify are rejected. |
| Backup exfiltration | `allowBackup=false` or strict backup rules excluding DB/cache/keys. |
| Screenshot/screen-record/overview thumbnail | `FLAG_SECURE` on sensitive windows (configurable). |
| Clipboard leakage | Mark sensitive copies as such; avoid auto-copying keys; clear share links from clipboard after a timeout where feasible. |
| Other apps / IPC | No exported components handling content; deep links validated; no content in logs/intents. |
| Memory scraping | Zeroize key buffers where possible; prefer Keystore-backed handles; hold unwrapped keys only in the `UserSession`. |

## 17.2 Key & data protection

- **Root of trust**: a hardware-backed `AndroidKeyStore` key (StrongBox when available) wraps the DB master key and the identity-key store ([07 §7.2](07-key-and-device-management.md)). Configure `setUserAuthenticationRequired` per the user's app-lock setting, using `BiometricPrompt` + `CryptoObject` to unlock.
- **DB at rest**: SQLCipher with the Keystore-protected passphrase; covers structure, decrypted names, metadata, and the FTS index. With multi-account ([14 §14.7](14-authentication.md)) there is **one DB and one master key per account**, so a compromise scoped to one account's key cannot read another account's data; account removal destroys that account's DB, cache, index, tokens, and Keystore-wrapped keys.
- **Blob cache**: ciphertext is safe as-is; decrypted pinned plaintext lives in app-private storage and is acceptable because the DB/keys protecting access are themselves gated — but treat it as sensitive (no backup, evictable, cleared on "forget device").
- **Tokens**: OIDC tokens in `EncryptedSharedPreferences`/Keystore-wrapped, never in Room/logs ([14](14-authentication.md)).
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
- v1.0.0 directory trust is TLS + Ed25519 signatures, not full key transparency ([13 §13.6](13-sharing.md)); deferred to Phase 6.
