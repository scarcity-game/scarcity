# auth: build decisions

Moved verbatim from `services/auth/AGENTS.md` (the former "Decision log", initial build entry).

- **2026-11-09: users table, bcrypt, JWT minting (initial build).**
  - Package `com.pessimisticit.scarcity.auth`. The groupId has a hyphen, which Java packages can't contain.
  - **No Spring Security starter.** It uses `spring-security-crypto` (BCrypt) and `nimbus-jose-jwt` directly.
    - nimbus is pinned to 9.37.4 because Boot 3.5.7 doesn't manage it. That matches spring-security-oauth2-jose 6.5.x.
    - `/me` checks the Bearer token by hand against our own key. The scheme check is case-insensitive.
    - 401s send `WWW-Authenticate: Bearer`, plus `error="invalid_token"` when a token was sent.
  - **Data access** is plain `JdbcClient`; there is no JPA.
    - Flyway runs with `schemas=auth`, `default-schema=auth` and `create-schemas=true`. History lives in `auth.flyway_schema_history`.
    - `auth.users` has a unique index on `lower(username)` and a CHECK on the username format.
    - The application generates `id`.
  - **Duplicate usernames.** A pre-check avoids bcrypt work, but the unique index is the real guard: `DuplicateKeyException` becomes 409. A concurrent-registration test covers the race.
  - **bcrypt cost** is 12 by default (`auth.password.bcrypt-strength`). Startup fails outside 10-31 (Bean Validation on `AuthProperties`). Tests use 10.
  - **Validation.** "Chars" means Unicode code points.
    - The 72-UTF-8-byte limit is enforced too, so in practice at most 72 ASCII characters.
    - Unpaired surrogates are rejected. Passwords are not NFC-normalised.
    - All violations come back together as `errors: [{field, message}]` on the 400 problem.
    - JSON input is strict: numbers and booleans are not converted to String (`web/JsonConfig`). Missing or null fields give 400.
  - **Login.** Missing fields give 400. Every other failure gives one byte-identical 401 "Invalid username or password": unknown user, wrong password, malformed username, password over 72 bytes.
    - Exactly one bcrypt compare always runs, against a dummy hash when the user doesn't exist.
    - The response sends `Cache-Control: no-store`.
  - **JWT.**
    - The `kid` is the RFC 7638 SHA-256 thumbprint; the header carries `typ=JWT`.
    - `iat` is in whole seconds and `exp = iat + 3600`. There is no `jti`, so logins in the same second return the same token.
    - Verification requires RS256, our kid, `iss`, every claim present and `sub` a UUID. It allows 30 s of clock skew.
    - `/me` returns 401 if the user no longer exists.
  - **Key.**
    - It must be PKCS#8 and RSA ≥ 2048 bits (3072 expected). PKCS#1 fails with a conversion hint.
    - A set but unreadable path makes startup fail. A blank or unset path generates an ephemeral RSA-3072 key and logs a WARN.
    - The env var is mapped explicitly: `auth.jwt.private-key-path: ${AUTH_JWT_PRIVATE_KEY_PATH:}`.
  - **Timestamps** are ISO-8601 UTC strings. `createdAt` has microsecond precision. `expiresAt` equals `exp`.
  - **JWKS** returns `application/json` with `Cache-Control: public, max-age=300` and one key. Rotation is not designed yet.
  - **No Location header on 201**, because the contract has no per-user URL.
  - **Errors.** `ApiExceptionHandler` makes 400/404/405/415/500 all problem+json. The whitelabel page is off.
  - **Actuator.** Only `health` is exposed, with probes on. The readiness group is `readinessState,db`; liveness doesn't depend on the DB. It runs on port 8080. *(Superseded 2026-11-09: port 8081, plus `prometheus`.)*
  - **`k8s` profile.** Graceful shutdown (20 s), a Hikari pool of at most 5, plain logs. *(Superseded 2026-11-09: ECS JSON logs.)*
  - **Dockerfile.** It copies the POMs, runs `dependency:go-offline`, copies `src`, then runs `package -pl services/auth -am`.
    - The runtime is `eclipse-temurin:25-jre` as uid/gid 10001, starting `java -jar /app/app.jar`.
    - When the root pom gains more modules, their POMs must be COPYed before go-offline.
    - The image also works with a read-only root filesystem plus a tmpfs on `/tmp`.
  - **Mockito** is loaded as a surefire `-javaagent` (JDK 21+ self-attach is deprecated).
  - **Known issue.** Flyway (Boot-managed) warns that PostgreSQL 18.6 is newer than it supports. It works; upgrade when Boot does.
- **2026-11-20: nimbus 10.9.1 (#246).** With Boot 4.1 the pin follows Spring
  Security 7.1.x, whose `spring-security-oauth2-jose` uses nimbus 10.9.1. Boot
  still doesn't manage it. The 9.37.4 entry above is the Boot 3.5 history.
