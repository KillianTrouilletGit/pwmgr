# Cryptography Specification

Version: **1** (vault format `version = 1`)

This document is the authoritative specification of the vault's cryptographic design. The code under `:shared:crypto` and `:shared:core` MUST conform to this document; if they diverge, this document is correct and the code is wrong.

---

## 1. Threat model

- The vault file is treated as **public**: it is stored on an untrusted cloud (Google Drive).
- An attacker is assumed to have:
  - The full ciphertext of the vault.
  - Knowledge of the file format and all algorithm parameters (Kerckhoffs's principle).
  - The ability to tamper with the file in flight or at rest.
- An attacker does **not** have:
  - The master password.
  - Access to the device's secure hardware (Android Keystore / Windows TPM) while it is locked.
- Out of scope:
  - Compromise of the running device (memory scraping, malicious OS).
  - Side channels that require local execution (timing, EM, acoustic).
  - Coerced disclosure of the master password.

Goals:
- **Confidentiality** of all entry data against an attacker holding the file.
- **Integrity / authenticity**: any byte-level modification to the file MUST cause decryption to fail.
- **Forward secrecy of past versions** is *not* a goal (the vault is a single mutable blob).

---

## 2. Primitives

| Purpose | Algorithm | Parameters |
|---|---|---|
| Password-based key derivation | **Argon2id** | m = 65536 KiB (64 MiB), t = 3, p = 4, salt = 32 random bytes, output = 32 bytes |
| Authenticated encryption | **AES-256-GCM** | key = 32 bytes, nonce = 12 bytes (96 bits), tag = 16 bytes (128 bits) |
| Random number generation | OS CSPRNG | `SecureRandom` on JVM/Android (must be a strong source — `getInstanceStrong` not required, but `SecureRandom()` is fine on modern Android/JVM) |
| Encoding | Base64 (RFC 4648, standard alphabet, no line wrap) | Used for all binary fields on the JSON wire |

No other algorithms are permitted in format version 1. SHA, HMAC, HKDF etc. are intentionally absent — AEAD is doing all integrity work.

### 2.1 Why these choices

- **Argon2id** is the OWASP-recommended password hash. Memory-hardness defeats GPU/ASIC brute-force. The chosen parameters are a balance between mobile feasibility (~64 MiB RAM on Android is fine) and resistance.
- **AES-256-GCM** is FIPS-approved, hardware-accelerated on essentially all modern x86/ARM, and provides authenticated encryption with associated data (AEAD) in a single primitive. The 96-bit nonce is the standard for GCM; we generate it randomly per encryption (acceptable safety margin given we encrypt a small number of times per key — see §6).

---

## 3. Key hierarchy

```
master password (user input)
   │
   │  Argon2id(salt = kdf.salt, params = kdf.{memKiB,iter,par})
   ▼
Master Key (MK)   — 32 bytes, never persisted
   │
   │  AES-256-GCM-Decrypt(nonce = wrap.nonce, ct = wrap.ct, aad = headerAAD)
   ▼
Vault Key (VK)    — 32 bytes, lives in memory while unlocked
   │
   │  AES-256-GCM-Decrypt(nonce = payload.nonce, ct = payload.ct, aad = headerAAD)
   ▼
Entries plaintext (UTF-8 JSON array of VaultEntry)
```

Three distinct keys exist conceptually; only one (VK) is ever held in memory longer than a single function call. MK is derived, used to unwrap VK, and discarded immediately.

### 3.1 Biometric / convenience unlock (optional, per-device)

When the user enables biometric (Android) or convenience (Windows) unlock on a device, VK is wrapped under a platform-specific hardware key. The wrap blob lives locally only — never in the vault file, never synced.

#### Android — true biometric

1. An AES-256 key is generated in Android Keystore via `KeyGenParameterSpec` with:
   - `setUserAuthenticationRequired(true)`
   - `setUserAuthenticationParameters(0, BIOMETRIC_STRONG)`
   - `setInvalidatedByBiometricEnrollment(true)`
2. VK is encrypted with this key under AES-256-GCM. The IV and ciphertext are persisted in a small JSON file (`biometric.wrap`) next to the vault.
3. To unlock: BiometricPrompt with a Cipher CryptoObject. The OS releases the key only after a successful biometric match. The unlocked Cipher then decrypts the wrap → VK.
4. Re-enrolling a fingerprint on the device automatically invalidates the keystore key. The next unlock attempt fails with `KeyPermanentlyInvalidatedException`; we delete the stale wrap and the user must re-enroll biometric unlock with their master password.

#### Windows — DPAPI convenience unlock (v1 compromise)

The plan originally targeted Windows Hello via WinRT's `KeyCredentialManager`. We deferred this to a later phase: WinRT bindings from the JVM aren't first-class, and the integration risk wasn't worth the v1 ROI. The pragmatic fallback (documented in the plan) is **DPAPI**:

1. VK is encrypted with `CryptProtectData` using per-user scope (not `CRYPTPROTECT_LOCAL_MACHINE`) and a fixed entropy string (`"pwmgr-biometric-wrap-v1"` as bytes).
2. The DPAPI blob is stored in `%LOCALAPPDATA%\PwMgr\hbk.dpapi`.
3. To unlock: `CryptUnprotectData` is called — silently, no Hello prompt fires. The user gets convenience unlock that survives reboot but is bound to their Windows user account.

**Honest comparison:**

| Property | Android | Windows v1 |
|---|---|---|
| Hardware-backed key | ✅ Keystore / StrongBox | ✅ DPAPI master key (per-user, DPAPI-managed) |
| Biometric prompt required at unlock | ✅ BiometricPrompt | ❌ Silent |
| Re-enrolled biometric invalidates wrap | ✅ | ❌ N/A |
| Resistant to a different OS user on the same machine | ✅ | ✅ |
| Resistant to malware running as the same user | ⚠️ key never leaves secure element, but a process can request authentication | ❌ malware can call `CryptUnprotectData` itself |
| UI labeling | "Biometric unlock" | "Convenience unlock" |

The Settings screen labels the Windows option clearly to avoid claiming biometric protection we don't deliver. A future phase (Windows Hello via WinRT) can upgrade this without breaking the on-disk format — only the wrap mechanism changes.

#### Disabling

Either platform: delete the wrap file and (where possible) the hardware key entry. Master password remains the source of truth — biometric/convenience unlock is purely a UX layer over it.

**The biometric wrap is a convenience cache.** Losing it (factory reset, biometric re-enrollment) just forces a master-password unlock; it never causes data loss because VK is also recoverable from `wrap` in the vault file.

---

## 4. Vault file format

A vault file is UTF-8-encoded JSON. The top-level object has exactly these fields:

```jsonc
{
  "version": 1,
  "kdf":     { "algo": "argon2id", "salt": "<b64-32B>", "memKiB": 65536, "iter": 3, "par": 4 },
  "wrap":    { "alg": "AES-256-GCM", "nonce": "<b64-12B>", "ct": "<b64-(32+16)B>" },
  "payload": { "alg": "AES-256-GCM", "nonce": "<b64-12B>", "ct": "<b64>" },
  "meta":    { "revision": 0, "modifiedAt": "<RFC3339 UTC>", "deviceId": "<uuid>" }
}
```

### 4.1 Field rules

- `version` (integer, required) — currently `1`. Implementations MUST reject unknown versions.
- `kdf.algo` MUST be the literal string `"argon2id"`.
- `kdf.salt` MUST be exactly 32 bytes after base64 decode.
- `kdf.memKiB` MUST be ≥ 19456 (OWASP minimum for Argon2id). Default for new vaults: 65536.
- `kdf.iter` MUST be ≥ 2. Default: 3.
- `kdf.par` MUST be ≥ 1 and ≤ 16. Default: 4.
- `wrap.alg` MUST be the literal string `"AES-256-GCM"`.
- `wrap.nonce` MUST be exactly 12 bytes after base64 decode.
- `wrap.ct` MUST be exactly 48 bytes after base64 decode (32-byte VK + 16-byte GCM tag).
- `payload.alg` MUST be the literal string `"AES-256-GCM"`.
- `payload.nonce` MUST be exactly 12 bytes after base64 decode.
- `payload.ct` is arbitrary-length (≥ 16 bytes for the tag).
- `meta.revision` is a monotonically increasing integer, incremented on every save.
- `meta.modifiedAt` is RFC 3339 / ISO 8601 UTC timestamp (e.g. `2026-05-14T10:30:00Z`).
- `meta.deviceId` is a UUIDv4 identifying the writing device. Used only for sync diagnostics; not security-relevant.

### 4.2 Associated data (AAD)

Both AES-GCM operations (`wrap` and `payload`) use the **same AAD**, computed deterministically from the header. The AAD is a UTF-8-encoded JSON object with sorted keys, containing exactly:

```jsonc
{ "version": 1, "kdf": { ... full kdf object ... }, "meta": { ... full meta object ... } }
```

This binds the ciphertext to the version, KDF parameters, and metadata. An attacker who swaps in a weaker KDF, rewinds `revision`, or changes `deviceId` will cause both `wrap` and `payload` decryption to fail with `AEADBadTagException` *before* any plaintext is produced.

**The AAD computation is normative.** The canonical-JSON rules:
- Keys sorted lexicographically by Unicode code point.
- No insignificant whitespace.
- Numbers serialized without trailing zeros or exponents (`65536`, not `65536.0` or `6.5536e4`).
- Strings UTF-8, with `\\`, `\"`, and control characters `\u00XX`-escaped per JSON.

A canonical JSON serializer is provided in `:shared:core` as `CanonicalJson.encode(JsonElement): ByteArray`. All implementations MUST use it (or a byte-identical equivalent) to compute AAD.

### 4.3 Payload plaintext

After AEAD decryption of `payload.ct`, the plaintext is UTF-8 JSON of shape:

```jsonc
{
  "entries": [ VaultEntry, VaultEntry, ... ]
}
```

`VaultEntry` is defined in `:shared:core` (`VaultEntry.kt`). Implementations MUST tolerate unknown fields inside `VaultEntry` (forward compatibility) but MUST NOT tolerate unknown fields at the top level of the payload.

---

## 5. Operations

### 5.1 Create new vault

```
1. Generate salt   ← 32 random bytes from CSPRNG.
2. Derive MK       ← Argon2id(password, salt, m=65536, t=3, p=4, len=32).
3. Generate VK     ← 32 random bytes from CSPRNG.
4. Build header (version, kdf, meta with revision=0, modifiedAt=now, deviceId=this-device).
5. Compute aad     ← CanonicalJson.encode({ version, kdf, meta }).
6. wrapNonce       ← 12 random bytes.
7. wrap.ct         ← AES-256-GCM-Encrypt(key=MK, nonce=wrapNonce, plaintext=VK, aad=aad).
8. payloadNonce    ← 12 random bytes.
9. payload.ct      ← AES-256-GCM-Encrypt(key=VK, nonce=payloadNonce, plaintext=UTF8(`{"entries":[]}`), aad=aad).
10. Zero MK.
11. Serialize and write the file.
```

### 5.2 Unlock (password)

```
1. Parse header. Reject if version != 1 or any field invariant from §4.1 fails.
2. Compute aad ← CanonicalJson.encode({ version, kdf, meta }).
3. Derive MK ← Argon2id(password, kdf.salt, kdf.memKiB, kdf.iter, kdf.par, len=32).
4. VK ← AES-256-GCM-Decrypt(key=MK, nonce=wrap.nonce, ct=wrap.ct, aad=aad).
   If decryption fails → wrong password (or tampered file). Increment failure counter.
5. Zero MK.
6. plaintext ← AES-256-GCM-Decrypt(key=VK, nonce=payload.nonce, ct=payload.ct, aad=aad).
   If this fails after step 4 succeeded, the file is corrupted; treat as fatal error and surface a "vault corrupted" UI.
7. Parse plaintext as `{ "entries": [...] }`.
8. Hold VK in memory; do not retain plaintext beyond what the UI needs.
```

### 5.3 Save (re-encrypt payload)

```
Preconditions: VK is in memory; header.kdf and header.wrap are unchanged.

1. Increment meta.revision; set meta.modifiedAt = now.
2. Compute aad ← CanonicalJson.encode({ version, kdf, meta }) — note this changes per save.
3. payloadNonce ← 12 random bytes.
4. payload.ct  ← AES-256-GCM-Encrypt(key=VK, nonce=payloadNonce, plaintext=UTF8(entries-json), aad=aad).
5. Rewrap wrap.ct under the same MK?   NO — MK is gone. Instead: re-encrypt wrap.ct with the *current* VK is wrong (wrap is keyed by MK, not VK). See §5.4 for the correct rule.
```

### 5.4 Important: when does `wrap` need to change?

`wrap.ct` only needs to be rewritten when **MK changes** (i.e., the user changes their master password) or when **AAD changes** (every save, because `meta` is in AAD).

Because AAD changes on every save, `wrap.ct` MUST be re-encrypted on every save *if MK is in scope*. But after unlock we deliberately discard MK.

Two solutions:
- **(A) Keep MK in memory.** Simpler but doubles the in-memory secret surface and weakens the rationale for the two-key design.
- **(B) Exclude `meta` from AAD.** AAD becomes only `{ version, kdf }`. Trade-off: `meta` is no longer cryptographically bound to the ciphertext. `revision` could be rewound by an attacker holding two file versions.

**Decision for v1: option (B).** AAD = `CanonicalJson.encode({ "version": 1, "kdf": { ... } })`. `meta` is *not* in AAD.

Rationale:
- Replay/rollback of `meta.revision` is detected at the **sync layer** (Drive ETag + server-side mtime + our own sync log), not at the crypto layer.
- The crypto layer's job is confidentiality + integrity of `entries`. It achieves both: any tamper of `payload.ct` or `wrap.ct` causes decryption failure; `kdf` is bound so an attacker can't substitute weaker parameters; `version` is bound so an attacker can't downgrade format.
- Keeping the design simple (single AAD computation; MK discarded immediately) is worth more than crypto-binding a sync-layer concern.

**Updated §5.3 (save):**

```
1. Increment meta.revision; set meta.modifiedAt = now.
2. aad ← CanonicalJson.encode({ version, kdf }).     // unchanged from previous save
3. payloadNonce ← 12 random bytes.
4. payload.ct  ← AES-256-GCM-Encrypt(VK, payloadNonce, UTF8(entries-json), aad).
5. Write header + new payload. wrap is unchanged.
```

**Updated §5.1 step 5 / §5.2 step 2:** aad = `CanonicalJson.encode({ version, kdf })`. (The earlier `{ version, kdf, meta }` form is superseded.)

### 5.5 Change master password

```
Preconditions: vault is unlocked; VK is in memory.

1. newSalt   ← 32 random bytes.
2. newMK     ← Argon2id(newPassword, newSalt, params...).
3. newKdf    = { algo:"argon2id", salt:newSalt, memKiB, iter, par }.
4. newAad    ← CanonicalJson.encode({ version:1, kdf:newKdf }).
5. newWrapN  ← 12 random bytes.
6. newWrapCt ← AES-256-GCM-Encrypt(newMK, newWrapN, VK, newAad).
7. Re-encrypt payload with newAad (same VK, fresh nonce).
8. Atomically replace header.{kdf, wrap, payload}. Increment revision.
9. Zero newMK.
```

VK does **not** change; entries do not need re-encryption beyond the AAD-binding refresh in step 7.

---

## 6. Nonce safety

GCM is catastrophically broken if a `(key, nonce)` pair is ever reused. Our nonces are 96-bit random:

- **Birthday bound:** with random 96-bit nonces, collision probability reaches 2⁻³² after ~2³² encryptions under the same key. We are nowhere near that:
  - Per VK: ~1 payload encryption per save. A user saving 10× per day for 100 years = 365,000 encryptions, well under 2³².
  - Per MK: 1 wrap encryption per vault creation or password change. Trivially safe.
- We MUST use a CSPRNG (`SecureRandom`) for nonces. We MUST NOT use a counter — counter-based nonces require persistent state that survives device restoration, which adds complexity without measurable safety benefit at our scale.

---

## 7. Memory hygiene

- Master password `CharArray` MUST be filled with `' '` immediately after MK derivation.
- MK `ByteArray` MUST be filled with `0` immediately after `wrap` is decrypted/encrypted.
- VK `ByteArray` MUST be filled with `0` when the vault is locked (app backgrounded past timeout, explicit lock, process termination via shutdown hook).
- Plaintext payload `ByteArray` MUST be zeroed after parsing.
- `String` cannot be reliably zeroed in JVM. The crypto layer therefore uses `ByteArray` / `CharArray` at all boundaries. Conversions to `String` happen only inside the JSON parser/serializer; the UI MUST NOT hold long-lived `String` references to passwords (use `CharArray`-backed text-field state, or accept the residual risk explicitly).

These are best-effort defenses against in-process memory scraping. They are not perfect — the JVM may copy buffers during GC, and JIT may hold register-resident values — but the deltas are large enough vs. an attacker reading a heap dump to be worth doing.

---

## 8. Versioning and migration

- The on-disk `version` field exists for one purpose: format migration.
- Adding a field that does not affect decryption (e.g. a new optional `meta.foo`) does **not** bump `version`.
- Changing AAD computation, KDF, AEAD, or the key hierarchy bumps `version`.
- Implementations MUST refuse to read `version > knownVersion`. On `version < knownVersion`, run an upgrade migration on next save.

---

## 9. Test obligations

The `:shared:crypto` and `:shared:core` test suites MUST include:

1. **Round-trip**: `(password, plaintext)` → encrypt → decrypt → equal plaintext. Property-tested with 1000 random inputs.
2. **Wrong password**: decrypting with a different password fails with the AEAD error (not a generic exception, not a silent empty result).
3. **Tamper detection** — for each of these mutations, decryption MUST fail:
   - Flip any single byte in `wrap.ct`.
   - Flip any single byte in `payload.ct`.
   - Change `version` from `1` to `2` (parser must reject before crypto, but document the behavior).
   - Change `kdf.memKiB` (AAD binding catches this).
   - Change `kdf.iter` or `kdf.par` (AAD binding).
   - Change `kdf.salt` (AAD binding + wrong-key effect).
4. **Nonce uniqueness**: 10,000 nonces generated by the production code path MUST be pairwise distinct (smoke test for CSPRNG wiring).
5. **Canonical JSON**: golden test vectors for `CanonicalJson.encode`, including unicode, escape sequences, nested objects, and integer/float edge cases.
6. **Argon2 parameter floor**: building a vault with `memKiB < 19456` MUST throw at construction time, not silently weaken security.

---

## 10. Out of scope (for v1)

- **Forward secrecy** across vault revisions. An attacker who captured an old ciphertext and later learns the master password can decrypt the old ciphertext. We accept this — the vault is a mutable blob, not a message stream.
- **Padding**: AES-GCM is a stream mode; ciphertext length leaks plaintext length. Vault sizes are large enough that this is not meaningfully exploitable. Could add length padding in v2 if desired.
- **Per-entry encryption**: every entry is in the single `payload.ct`. Pro: single AAD-bound blob, simple sync. Con: any entry read requires decrypting all entries. Acceptable at expected vault sizes (≤ few thousand entries, ≤ a few MB).
