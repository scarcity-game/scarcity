# brawler

Plays the fighters. brawler is an intelligence service: world POSTs it each
fighter's turn (`POST /turn`, with world's service token).

Each turn the fighter does one of three things:
- **Strike** the nearest creature it is hostile to, if that creature is in
  reach. Whom it is hostile to comes from the game's faction relations and
  its foes.
- **Step** toward that creature along a path round walls, if it is out of
  reach.
- **Roam** when nobody is in range: step toward a point drawn by a uniform heading and a distance of
  half to the whole step, so fighters from distant dens meet. The draws are in the intent's `reported`.
- **Wait**, if there is nowhere to roam to, or no way to the quarry.

It keeps no state, so any replica answers any turn.

See [AGENTS.md](AGENTS.md) for the choices, the configuration and the tests,
and [`libs/service-controller`](../../libs/service-controller/AGENTS.md) for a
turn.

```
mvn -B -f ../../pom.xml -pl services/brawler -am verify
docker build -f services/brawler/Dockerfile -t scarcity-brawler .   # from the repo root
```

Run it locally with auth's keys (world's token is checked against them):

```
AUTH_JWKS_URI=http://localhost:8081/api/auth/.well-known/jwks.json \
SERVER_PORT=8088 \
MANAGEMENT_SERVER_PORT=8089 \
  mvn -B -f ../../pom.xml -pl services/brawler -am spring-boot:run -DskipTests
```
