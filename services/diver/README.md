# diver

Plays the diver, the dive game's npc that always goes down (#171). It gets a
token from auth with its API key and subscribes to subscriptions' turn feed,
so it hears the diver's turns in whichever level it is in, even ones made
after it started. On a turn it waits 30 s of wall time (so people can watch),
reads the level's entities from world, and walks toward the nearest stairs
down, round walls and mice ([`libs/pathfinding`](../../libs/pathfinding/README.md)),
at most 1 m per step. Once they are within reach it takes them,
and does the same again on the level below.

See [AGENTS.md](AGENTS.md) for the choices, the configuration and the
tests, and [`libs/service-controller`](../../libs/service-controller/AGENTS.md)
for a turn and the socket.

```
mvn -B -f ../../pom.xml -pl services/diver -am verify
docker build -f services/diver/Dockerfile -t scarcity-diver .   # from the repo root
```

Run it locally against auth (with a `diver` key in its
`AUTH_SERVICE_KEYS_DIR`), world, subscriptions and microbe (the levels' mice
must move, or the diver's level waits for them):

```
AUTH_URL=http://localhost:8081 \
WORLD_URL=http://localhost:8082 \
SUBSCRIPTIONS_URL=ws://localhost:8085/api/subscriptions/ws \
DIVER_API_KEY=<the key> \
DIVER_ARRIVAL_PAUSE=2s \
SERVER_PORT=8088 \
MANAGEMENT_SERVER_PORT=8089 \
  mvn -B -f ../../pom.xml -pl services/diver -am spring-boot:run -DskipTests
```
