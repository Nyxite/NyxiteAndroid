# 14 — Authentication

Account authentication is **Nyxite-native and server-owned by default**: two **co-equal** primary methods — (a) **password + required TOTP**, and (b) **passkeys (WebAuthn)**, sufficient alone. **Enterprise Keycloak/OIDC SSO is a pluggable option, not the default.** Whichever method is used, the **Nyxite server issues its own access + refresh tokens**; the client is IdP-agnostic and simply holds the server's bearer token. Authentication yields an API token but **no content key** — decryption is governed entirely by the on-device identity keys ([07](07-key-and-device-management.md)). The two layers are deliberately separate (see [SPECIFICATION §10](https://github.com/Nyxite/Nyxite/blob/main/docs/SPECIFICATION.md), [OPEN-DECISIONS #9](https://github.com/Nyxite/Nyxite/blob/main/docs/OPEN-DECISIONS.md), [server 08](https://github.com/Nyxite/NyxiteServer)).

## 14.1 Login flows

The app is **IdP-agnostic**: every login path resolves to **one internal token** issued by the Nyxite server, attached as `Authorization: Bearer <access token>` ([05 §5.1](05-api-client.md)). Both the default native flows and the enterprise OIDC flow are supported.

**Native login (default).**

- **Password + TOTP** — submit the username/password (and, when challenged, the TOTP code) **directly to the Nyxite server over TLS** (e.g. `POST /api/v1/auth/login`). The server verifies the Argon2id password verifier and the TOTP second factor natively and returns the server's **access + refresh tokens**. The login password is **transient** and never persisted; it **never** feeds content-key derivation.
- **Passkeys (WebAuthn)** — phishing-resistant and sufficient alone, via the Android **Credential Manager** (`androidx.credentials`) performing a WebAuthn assertion against the Nyxite server's RP. The server verifies the assertion and returns the same access + refresh tokens. Passkey registration is likewise driven through the Credential Manager.
- Config is runtime-configurable per instance: `API base` (the Nyxite server) and `public-share base`. Account profile (display name, email) and `roles` (`user`/`admin`) come from the server.

**Enterprise OIDC login (optional).** SSO/OIDC is a **licensed enterprise feature** (L-3): a **community-mode** server does not advertise it, so the app offers native login only, and a lapsed license stops new OIDC logins (server [16 §16.5](https://github.com/Nyxite/NyxiteServer)). The client renders whatever methods the server advertises. For instances configured with the enterprise Keycloak tier:

- **Authorization Code + PKCE** via **AppAuth-Android** against the Keycloak realm. Config: `Authority` (issuer URL), `ClientId`, redirect URI (App Link or custom scheme), scopes (`openid profile email` + any Nyxite scope). These are runtime-configurable for self-hosted enterprise instances.
- Use a **Custom Tab** for the authorization request (not a WebView) for security and SSO. In this path Keycloak handles credentials, registration, password reset, and **TOTP enrollment/challenge**.
- On callback, the code is exchanged and **resolved to the Nyxite server's internal access + refresh tokens** (the rest of the API never sees which IdP was used). Where the client validates the OIDC assertion, it checks `iss`, `aud`, `exp`, and signature against Keycloak **JWKS** and reads `sub`, name/email, and `roles`; 2FA is reflected in `amr`/`acr` claims.

## 14.2 TOTP / 2FA

- For **native login**, TOTP is verified by the **Nyxite server**: the app collects the code and submits it with the credentials (or in a follow-up step-up call). For the **enterprise OIDC** path, TOTP is handled inside the Keycloak flow and the app does not implement TOTP itself.
- **Step-up**: if a protected API call returns `403 2fa_required`, route the user back through step-up auth — re-submit the second factor natively, or re-enter the Keycloak flow on enterprise instances ([05 §5.4](05-api-client.md)).

## 14.3 Token storage & refresh

- Store the server's **access + refresh tokens** in secret storage: `EncryptedSharedPreferences` or a Keystore-wrapped blob — **never** in Room, plaintext, or logs ([04 §4.6](04-local-data-model.md),[17](17-security.md)).
- **Token lifetimes** (match server): the **access token is ~5 min**, so refresh is routine; `AuthInterceptor` attaches the bearer; on `401 token_expired`, perform a single silent refresh against the server (native refresh-token exchange, or AppAuth `performTokenRequest` on enterprise instances) and retry; on refresh failure, surface re-login. (Guest share-session and relay-ticket lifetimes are in [§14.5](#145-share-token-sessions-guests).)
- Logout clears tokens and locks the `UserSession` (zeroizes the in-memory identity key) but **does not** delete enrolled keys/local data unless the user chooses "forget this device" ([07](07-key-and-device-management.md)).

## 14.4 Relationship to device enrollment

After a successful login, the app checks device enrollment + identity-key availability ([07 §7.3](07-key-and-device-management.md)):
- New account → generate identity keypair, enroll device, set recovery key.
- Existing account, new device → device-to-device approval or recovery-key unwrap.
- Enrolled device → unlock the identity key (biometric/device credential) into the `UserSession`.

Until the identity key is present, the app can show structure (encrypted names will be unreadable) but cannot decrypt content; it presents an enrollment screen.

**Account recovery vs content recovery.** Forgot-password (email reset) restores **login only**; it never restores content. Content access still requires the **recovery phrase** or an **enrolled device** ([07](07-key-and-device-management.md)), so an account/email compromise yields no content. Device enrollment and recovery specifics are otherwise unchanged ([07](07-key-and-device-management.md)).

## 14.5 Share-token sessions (guests)

For link shares, the app obtains a **short-lived, relay-scoped share session token** from `GET /share/{token}` (no account login) to authorize relay/ciphertext access; the decryption key is the URL fragment ([13 §13.3](13-sharing.md),[09 §9.8](09-realtime-collaboration.md)). **Lifetimes** (match server): the guest **share-session token is 15 min, renewable**; the **relay socket ticket is single-use, 60 s**. Guest sessions are bounded by token lifetime + share validity and run without the full key/device subsystem.

**Guest storage model**: a guest session runs **without the per-account SQLCipher DB**. Guest content is held in an **ephemeral in-memory cache only** and is **never written to any account database or `noBackup` files**; decrypted views are transient and discarded when the session ends ([09 §9.8](09-realtime-collaboration.md)).

## 14.6 App lock

Independently of the login session, the app supports an **app lock** (biometric/device-credential) that gates unwrapping the DB master key and identity key on launch/after timeout ([17](17-security.md)). This protects local data even while the server session is still valid.

## 14.7 Multi-account (v1.0.0)

The app supports **multiple accounts and instance switching from v1.0.0**. Each account is a fully isolated tenant on the device:

- **Identity & DI scoping**: an account-scoped Hilt component keyed by `accountId` owns that account's `UserSession`, server tokens, identity-key handle, repositories, and clients. Switching accounts tears down the previous component (zeroizing its in-memory keys) and activates the target's ([01 §1.8](01-architecture.md)).
- **Storage isolation**: each account gets its **own SQLCipher database** (e.g. `nyxite-{accountId}.db`) with its **own DB master key**, its own `BlobCache` subdirectory, its own FTS index, and its own secret store for tokens. No content, names, search index, or keys are ever shared across accounts on the device ([04 §4.2](04-local-data-model.md), [16 §16.1](16-offline-and-storage-policies.md), [17 §17.2](17-security.md)).
- **Per-account auth**: each account may target a **different instance host** (API base + share base, plus the OIDC authority on enterprise instances) and a **different login method**, so a user can hold, say, a personal self-hosted native account and an enterprise SSO account simultaneously ([§14.1](#141-login-flows)).
- **Active account**: a single "active account" drives the UI at a time; an **account switcher** in the UI lets the user add, switch, and remove accounts ([15 §15.1](15-ui-and-navigation.md)). Background sync may run for multiple accounts subject to the unlock/lock model ([19 §19.2](19-open-questions.md)).
- **Guest sessions** (link shares, [14 §14.5](#145-share-token-sessions-guests)) are independent of accounts and may be opened without any account signed in.

**Lock interaction**: app lock gates *all* accounts; per-account identity-key unwrap still happens on first use after unlock. Removing an account deletes its DB, cache, index, tokens, and Keystore-wrapped keys ("forget account"), independent of other accounts.
