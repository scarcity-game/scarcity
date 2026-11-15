# orbital

Plays the test area's orbiter. About once a second it reads the orbiter from
world, draws what to do by weight (step round the orbit, wait, or throw a
pebble at the pillar), and posts that as an intent with its API key. Game time
then moves because something played it, through the same path a player uses.

Its draws are logged on world's `intent` entries (`reported`), so the log shows
exactly why the orbiter did what it did.

See [AGENTS.md](AGENTS.md) for the loop, the error handling, the configuration
and the tests.

```
mvn -B -f ../../pom.xml -pl services/orbital -am verify
docker build -f services/orbital/Dockerfile -t scarcity-orbital .   # from the repo root
```

Run it locally against a world started with an `orbital` key in its
`SCARCITY_WORLD_API_KEYS_DIR`:

```
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
WORLD_URL=http://localhost:8082 \
ORBITAL_API_KEY=<the key> \
SERVER_PORT=8083 \
MANAGEMENT_SERVER_PORT=8084 \
  mvn -B -f ../../pom.xml -pl services/orbital -am spring-boot:run -DskipTests
```
