# 12. JWT authentication, role and ward-scoped authorization, field-level encryption

- Status: Accepted
- Date: 2026-10-25

## Context

Care data is health data (GDPR Art. 9). Nurses may see only their own ward. The services call each other. A
database dump or backup must not reveal resident names or what nurses said.

## Decision

- **Login** with `POST /v1/auth/token`. Passwords are hashed with bcrypt (cost 12). A wrong username and a wrong
  password give the same 401 in the same time, so attackers cannot find out which usernames exist. At most 10
  attempts per minute per IP address.
- **JWT signed with HS256**, valid for 15 minutes by default (`JWT_TTL_SECONDS`), with the claims `tenant`, `ward`,
  `roles` and `jti`. Roles: `NURSE`, `WARD_LEAD`, and `SERVICE` for calls from documentation-service to
  alert-service (a 5-minute token that is cached and refreshed before it expires).
- **Every query is limited to the caller's tenant and ward.** Resources of other wards answer 404 (ADR-0010).
- **AES-256-GCM field encryption** for resident names and transcript text. Every value gets a random 12-byte IV, and
  the ciphertext starts with a key id (`v1:...`). That allows key rotation: new writes use the new key, and values
  with old key ids stay readable. Ids that are used for lookups are not encrypted.
- **Raw transcript text is erased** after `TRANSCRIPT_RETENTION_DAYS` (data minimisation). The extracted
  observations stay.
- **Never logged**: transcript text, resident names, passwords, tokens and full request bodies.

## Consequences

- A leaked database or backup does not expose names or transcripts without the key.
- Encrypted columns cannot be searched or sorted in SQL. That is acceptable: nobody searches by name in this API.
- With HS256, every service that checks tokens holds the signing secret.

## Alternatives considered

- **RS256/ES256 with a JWKS endpoint or an identity provider (Keycloak)**: the production choice, because services
  that check tokens need only the public key. HS256 keeps the demo self-contained.
- **Database encryption at rest only**: protects disks, but not dumps, backups or someone with SQL access.
