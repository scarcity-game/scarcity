# microbe

Plays the mice. It gets a token from auth with its API key and subscribes to
subscriptions' turn feed, so it hears about every mouse whose turn it is, in
any level, even ones made after it started. On a turn it reads the mouse
from world and either steps it a short way in a random direction, staying
inside the area, or has it wait. The direction is drawn uniformly and
reported with the intent, so the log shows why the mouse went where it did.

See [AGENTS.md](AGENTS.md) for the choices, the configuration and the
tests, and [`libs/service-controller`](../../libs/service-controller/AGENTS.md)
for a turn and the socket.

```
mvn -B -f ../../pom.xml -pl services/microbe -am verify
docker build -f services/microbe/Dockerfile -t scarcity-microbe .   # from the repo root
```

Run it locally against auth (with a `microbe` key in its
`AUTH_SERVICE_KEYS_DIR`), world and subscriptions:

```
AUTH_URL=http://localhost:8081 \
WORLD_URL=http://localhost:8082 \
SUBSCRIPTIONS_URL=ws://localhost:8085/api/subscriptions/ws \
MICROBE_API_KEY=<the key> \
SERVER_PORT=8086 \
MANAGEMENT_SERVER_PORT=8087 \
  mvn -B -f ../../pom.xml -pl services/microbe -am spring-boot:run -DskipTests
```
