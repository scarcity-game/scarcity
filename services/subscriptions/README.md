# subscriptions

The service that holds game clients' WebSockets. A client connects to
`/api/subscriptions/ws`, authenticates with its JWT in the first frame, subscribes to an area and
then receives the area's change batches as they are committed, plus a periodic digest of the
current versions so it can notice anything it missed.

- Protocol: [`docs/websocket.md`](../../docs/websocket.md); implementation notes and
  the interpretations chosen are in [`AGENTS.md`](AGENTS.md).
- Source of changes: Postgres `LISTEN/NOTIFY` via `libs/resource-model`.
- Initial versions: `world`'s resource API, called with the subscriber's own JWT.
- Ports: 8080 (WebSocket), 8081 (actuator: health, Prometheus).

Build and test: `mvn -B -f ../../pom.xml -pl services/subscriptions -am verify` (needs Docker for
Testcontainers). Configuration and how to run locally: see `AGENTS.md`.
