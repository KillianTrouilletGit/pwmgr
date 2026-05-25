# Security Policy

## Reporting a vulnerability

If you find a security issue in PwMgr, please **do not file a public GitHub issue**.

Instead, email the details to: `YOUR_EMAIL@example.com` (replace before publishing).

What helps:

- A clear description of the vulnerability and its impact
- Steps to reproduce (if a working PoC exists, even better)
- The affected version (commit SHA preferred, or tag like `v0.1.0`)
- Your suggested fix if you have one

What to expect:

- Acknowledgement within 7 days
- An initial assessment within 14 days
- Coordinated disclosure once a fix is released (typically 60-90 days)

If you want public attribution after the fix is shipped, mention it in your report. Default is anonymous.

## Scope

In scope:

- Cryptographic flaws in vault format, KDF, or AEAD usage
- Authentication bypass (master password, biometric, recovery code)
- Plaintext secret leakage (logs, error messages, clipboard, memory dumps after lock)
- Privilege escalation between PwMgr components (extension ↔ desktop, autofill service ↔ vault)
- TLS or certificate-validation issues
- Drive sync replay / rollback attacks beyond what [docs/THREAT-MODEL.md](docs/THREAT-MODEL.md) already documents

Out of scope (already documented in [docs/THREAT-MODEL.md](docs/THREAT-MODEL.md)):

- Same-user malware reading your unlocked vault from process memory
- Coerced disclosure of the master password
- Loss of vault due to forgotten master password AND lost recovery code
- DPAPI-only "convenience unlock" on Windows not requiring biometric prompt (documented behaviour, not a bug)

## No bug bounty

PwMgr is a personal / open-source project with no budget. We can't pay for reports. We can:

- Publicly credit you in release notes
- Add you to a contributors list
- Help you understand and disclose responsibly

## Supported versions

Only the latest tagged release receives security fixes. There are no LTS branches. If you find a critical issue in an old version, please upgrade — that's the fix.
