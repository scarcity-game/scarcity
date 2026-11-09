# scarcity-auth

User accounts and identity for Scarcity. Stores users with bcrypt password
hashes and mints RS256 JWTs that every other service verifies locally against
the published JWKS.

Java 25, Spring Boot 3.5, Maven, Postgres (schema `auth`, managed by Flyway).
Listens on port 8080.

## Run locally

### 1. Start Postgres

```bash
docker run -d --name scarcity-pg \
  -p 5432:5432 \
  -e POSTGRES_DB=scarcity \
  -e POSTGRES_USER=scarcity \
  -e POSTGRES_PASSWORD=scarcity \
  postgres:18.6
```

The defaults in `application.yml` point at `jdbc:postgresql://localhost:5432/scarcity`
with user and password `scarcity`. Flyway creates schema `auth` on startup.

### 2a. Run with Maven (needs JDK 25)

From the repo root:

```bash
mvn -B -pl services/auth -am package -DskipTests
java -jar services/auth/target/scarcity-auth.jar
```

Without `AUTH_JWT_PRIVATE_KEY_PATH` the service generates an **ephemeral**
signing key and logs a WARN: tokens stop verifying after a restart. To use a
stable key, generate one outside the repo (never commit it):

```bash
mkdir -p ~/.scarcity
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out ~/.scarcity/auth-private.pem
export AUTH_JWT_PRIVATE_KEY_PATH=~/.scarcity/auth-private.pem
java -jar services/auth/target/scarcity-auth.jar
```

The file must be PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`). If the variable is
set but the file is missing or invalid, startup fails on purpose. Convert a
PKCS#1 key (`BEGIN RSA PRIVATE KEY`) with:

```bash
openssl pkcs8 -topk8 -nocrypt -in old.pem -out private.pem
```

### 2b. Run with Docker

The build context is the repo root:

```bash
docker build -f services/auth/Dockerfile -t scarcity-auth:dev .
docker run --rm -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/scarcity \
  scarcity-auth:dev
```

### Tests

Integration tests use Testcontainers (`postgres:18.6`), so Docker must be running.

```bash
mvn -B -pl services/auth -am verify
```

No JDK 25 locally? Run the build in a container from the repo root:

```bash
docker run --rm \
  -v "$PWD":/work -w /work \
  -v "$HOME/.m2":/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3.9-eclipse-temurin-25 \
  mvn -B -pl services/auth -am verify
```

## API

All errors are RFC 7807 `application/problem+json`, e.g.
`{"type":"about:blank","title":"Conflict","status":409,"detail":"Username is already taken","instance":"/api/auth/register"}`.
Validation errors (400) add an `errors` array of `{"field","message"}`.

Timestamps are ISO-8601 UTC strings. `createdAt` has the database's
microsecond precision (`2026-11-09T17:10:30.912207Z`). `expiresAt` is whole
seconds and equals the JWT's `exp` (`2026-11-09T18:10:31Z`).

### Rules

- **Username:** 3-32 characters from `A-Z a-z 0-9 _ -`. Stored as entered,
  unique case-insensitively (`Alice` and `alice` are the same account). Login
  matches case-insensitively.
- **Password:** 8-128 characters, counted as Unicode code points (an emoji is
  one character), and at most 72 bytes in UTF-8, because bcrypt ignores
  anything past byte 72. Longer passwords are rejected, not truncated. In
  practice 72 bytes is the limit that bites: 72 ASCII characters, fewer
  with non-ASCII. Passwords are not Unicode-normalised.
- **JWT:** RS256, `kid` = RFC 7638 thumbprint of the public key. Claims are
  exactly `iss=scarcity-auth`, `sub=<user uuid>`, `username`, `iat`, `exp`
  (`iat` + 1h). Verification allows 30 s of clock skew.

### Register

```bash
curl -i -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"Alice","password":"correct horse battery"}'
```

`201 {"id":"…","username":"Alice","createdAt":"…"}`. `409` if the name is taken
(any case), `400` if validation fails, `415` if the body is not JSON.

### Login

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"correct horse battery"}'
```

`200 {"token":"eyJ…","tokenType":"Bearer","expiresAt":"…"}`. `401` with the
same body whether the user doesn't exist or the password is wrong. `400` if a
field is missing.

### Me

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"correct horse battery"}' \
  | jq -r .token)
curl -i http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer $TOKEN"
```

`200 {"id","username","createdAt"}`. `401` (with `WWW-Authenticate: Bearer …`)
if the token is missing, malformed, signed by another key, expired, has the
wrong issuer, or the user no longer exists.

### JWKS

```bash
curl -i http://localhost:8080/api/auth/.well-known/jwks.json
```

`200 {"keys":[{"kty":"RSA","kid":"…","alg":"RS256","use":"sig","n":"…","e":"AQAB"}]}`.
Public parameters only. Other Spring services point
`spring.security.oauth2.resourceserver.jwt.jwk-set-uri` at
`http://auth:8080/api/auth/.well-known/jwks.json`.

### Health

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
```

Readiness includes the database, so it reports `DOWN` (503) while Postgres is
unreachable. Liveness does not. No other actuator endpoints are exposed.

## Configuration

| env / property | default | meaning |
|---|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | `jdbc:postgresql://localhost:5432/scarcity`, `scarcity`, `scarcity` | Postgres |
| `AUTH_JWT_PRIVATE_KEY_PATH` | unset (ephemeral key) | PKCS#8 PEM RSA private key; public key derived from it |
| `auth.jwt.ttl` | `1h` | token lifetime |
| `auth.password.bcrypt-strength` | `12` | bcrypt cost (10-31) |
| `SPRING_PROFILES_ACTIVE=k8s` | | graceful shutdown, smaller connection pool |
