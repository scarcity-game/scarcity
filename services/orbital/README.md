# orbital

Plays the test area's orbiter, the way a player would. It gets a token from
auth with its API key and keeps a WebSocket open to subscriptions. When the
game says the orbiter is up, it waits a moment and reads the orbiter from
world. Then it draws one of the orbiter's actions by weight (step round the
orbit, wait, or throw a pebble at the pillar) and posts it as an intent. It
never acts unless it has been told it is its turn.

Its draws are logged on world's `intent` entries (`reported`), so the log shows
exactly why the orbiter did what it did.

See [AGENTS.md](AGENTS.md) for a turn, the socket, the error handling, the
configuration and the tests.

```
mvn -B -f ../../pom.xml -pl services/orbital -am verify
docker build -f services/orbital/Dockerfile -t scarcity-orbital .   # from the repo root
```

Run it locally against auth (with an `orbital` key in its
`AUTH_SERVICE_KEYS_DIR`), world and subscriptions:

```
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
AUTH_URL=http://localhost:8081 \
WORLD_URL=http://localhost:8082 \
SUBSCRIPTIONS_URL=ws://localhost:8085/api/subscriptions/ws \
ORBITAL_API_KEY=<the key> \
SERVER_PORT=8083 \
MANAGEMENT_SERVER_PORT=8084 \
  mvn -B -f ../../pom.xml -pl services/orbital -am spring-boot:run -DskipTests
```
