# microbe

Plays the mice. It is an intelligence service: world POSTs it each mouse's
turn (`POST /turn`, with world's service token), and it answers with either a
short step in a random direction, staying inside the area, or a wait. The
direction is drawn uniformly and reported with the answer, so the log shows why
the mouse went where it did. It keeps no state, so several replicas run behind
one Service.

See [AGENTS.md](AGENTS.md) for the choices, the configuration and the
tests, and [`libs/service-controller`](../../libs/service-controller/AGENTS.md)
for a turn and the socket.

```
mvn -B -f ../../pom.xml -pl services/microbe -am verify
docker build -f services/microbe/Dockerfile -t scarcity-microbe .   # from the repo root
```

Run it locally with auth's keys (world's token is checked against them):

```
AUTH_JWKS_URI=http://localhost:8081/api/auth/.well-known/jwks.json \
SERVER_PORT=8086 \
MANAGEMENT_SERVER_PORT=8087 \
  mvn -B -f ../../pom.xml -pl services/microbe -am spring-boot:run -DskipTests
```
