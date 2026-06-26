# 14 — Authentication

Account authentication is **Keycloak OIDC with TOTP**; it yields an API token but **no content key** — decryption is governed entirely by the on-device identity keys ([07](07-key-and-device-management.md)). The two layers are deliberately separate ([server 08](https://github.com/Nyxite/server)).

## 14.1 OIDC flow

- **Authorization Code + PKCE** via **AppAuth-Android** against the Keycloak realm. Config: `Authority` (issuer URL), `ClientId`, redirect URI (App Link or custom scheme), scopes (`openid profile email` + any Nyxite scope). These are runtime-configurable for self-hosted instances.
- Use a **Custom Tab** for the authorization request (not a WebView) for security and SSO. Keycloak handles credentials, registration, password reset, and **TOTP enrollment/challenge** in that flow.
- On callback, exchange the code for **access + refresh tokens**. Validate `iss`, `aud`, `exp`, and signature against Keycloak **JWKS**; read `sub`, name/email, and `roles` (`user`/`admin`). 2FA is reflected in `amr`/`acr` claims.

## 14.2 TOTP / 2FA

- TOTP is enforced by Keycloak within the OIDC flow; the app does not implement TOTP itself. If a protected API call returns `403 2fa_required`, route the user back through a step-up auth ([05 §5.4](05-api-client.md)).

## 14.3 Token storage & refresh

- Store **access + refresh tokens** in secret storage: `EncryptedSharedPreferences` or a Keystore-wrapped blob — **never** in Room or logs ([04 §4.6](04-local-data-model.md),[17](17-security.md)).
- `AuthInterceptor` attaches the bearer; on `401 token_expired`, perform a single silent refresh (AppAuth `performTokenRequest`) and retry; on refresh failure, surface re-login.
- Logout clears tokens and locks the `UserSession` (zeroizes the in-memory identity key) but **does not** delete enrolled keys/local data unless the user chooses "forget this device" ([07](07-key-and-device-management.md)).

## 14.4 Relationship to device enrollment

After a successful login, the app checks device enrollment + identity-key availability ([07 §7.3](07-key-and-device-management.md)):
- New account → generate identity keypair, enroll device, set recovery key.
- Existing account, new device → device-to-device approval or recovery-key unwrap.
- Enrolled device → unlock the identity key (biometric/device credential) into the `UserSession`.

Until the identity key is present, the app can show structure (encrypted names will be unreadable) but cannot decrypt content; it presents an enrollment screen.

## 14.5 Share-token sessions (guests)

For link shares, the app obtains a **short-lived, relay-scoped share session token** from `GET /share/{token}` (no Keycloak login) to authorize relay/ciphertext access; the decryption key is the URL fragment ([13 §13.3](13-sharing.md),[09 §9.8](09-realtime-collaboration.md)). Guest sessions are bounded by token lifetime + share validity and run without the full key/device subsystem.

## 14.6 App lock

Independently of OIDC, the app supports an **app lock** (biometric/device-credential) that gates unwrapping the DB master key and identity key on launch/after timeout ([17](17-security.md)). This protects local data even while the OIDC session is still valid.

## 14.7 Multi-account (v1.0.0)

The app supports **multiple accounts and instance switching from v1.0.0**. Each account is a fully isolated tenant on the device:

- **Identity & DI scoping**: an account-scoped Hilt component keyed by `accountId` owns that account's `UserSession`, OIDC tokens, identity-key handle, repositories, and clients. Switching accounts tears down the previous component (zeroizing its in-memory keys) and activates the target's ([01 §1.8](01-architecture.md)).
- **Storage isolation**: each account gets its **own SQLCipher database** (e.g. `nyxite-{accountId}.db`) with its **own DB master key**, its own `BlobCache` subdirectory, its own FTS index, and its own secret store for tokens. No content, names, search index, or keys are ever shared across accounts on the device ([04 §4.2](04-local-data-model.md), [16 §16.1](16-offline-and-storage-policies.md), [17 §17.2](17-security.md)).
- **Per-account auth**: each account may target a **different instance host** (OIDC authority + API base + share base), so a user can hold, say, a personal self-hosted instance and a shared one simultaneously ([§14.1](#141-oidc-flow)).
- **Active account**: a single "active account" drives the UI at a time; an **account switcher** in the UI lets the user add, switch, and remove accounts ([15 §15.1](15-ui-and-navigation.md)). Background sync may run for multiple accounts subject to the unlock/lock model ([19 §19.2](19-open-questions.md)).
- **Guest sessions** (link shares, [14 §14.5](#145-share-token-sessions-guests)) are independent of accounts and may be opened without any account signed in.

**Lock interaction**: app lock gates *all* accounts; per-account identity-key unwrap still happens on first use after unlock. Removing an account deletes its DB, cache, index, tokens, and Keystore-wrapped keys ("forget account"), independent of other accounts.
