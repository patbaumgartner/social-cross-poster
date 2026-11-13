# ADR-007 — Encrypted workspace secrets with externally supplied keys

- **Status:** Accepted; replaces the pre-implementation plaintext-DB/deferred-encryption sketch
- **Date:** 2026-11-13

## Context

Several brands' provider tokens, AI keys, SMTP passwords, webhook secrets, and optional bridge sessions require a clear storage boundary. A database-only leak should not expose every publishing credential.

## Decision

- Keep non-secret deployment configuration in Compose environment/`.env`. Supply bootstrap password hash, DB credential, signing keys, and versioned encryption keys through protected environment or read-only secret files. Use restrictive host permissions and never commit populated files.
- Compose `.env` interpolation is not automatic Spring property loading. The future Compose file must explicitly pass environment entries or mount files that the application explicitly reads; merely mounting `/app/.env` does not configure it.
- Encrypt provider, AI, SMTP, and notification secrets from P0 using authenticated encryption (AES-GCM with unique nonces and versioned key IDs). Bind workspace and secret identity/type as associated data.
- Store ciphertext/nonce/key ID in PostgreSQL; keep encryption keys outside the database. No installation-wide provider/AI credential fallback; each workspace explicitly configures its credentials.
- Write-only secret fields never return saved values. Redact Authorization headers, webhook/bot URLs, callback codes, signed media query strings, provider bodies, prompts, and private post text from routine logs and notification errors.
- Resolve and decrypt secrets only for an authorized operation in the owning workspace. No model receives social credentials or notification secrets.
- Key rotation retains the old key until all rows are re-encrypted and a restore is tested; losing required keys makes secrets unrecoverable. Signing-key rotation revokes grants or uses an explicitly bounded overlap.
- Back up ciphertext and key material separately to protected locations, document key versions, and test restore. If an attacker obtains both host keys and DB, encryption cannot protect the secrets.
- Sidecar session files may not support application encryption; protect each isolated bridge volume and include that residual risk in its feasibility review.

## Consequences

Encryption mitigates DB-only exposure and supports workspace-bound integrity; it is not protection against a compromised running application. Restore and rotation are release gates, not deferred options. No external Vault dependency is required for the NAS baseline.

## Alternatives

Plaintext provider secrets in DB or per-account `.env` entries are rejected. A full external secrets service remains a deployment option, not a v1 prerequisite.
