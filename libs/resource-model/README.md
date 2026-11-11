# resource-model

Java library for Scarcity resources (game, area, entity, item): the
`{id, type, version, children, state}` envelope, a Postgres `ResourceStore` with
the version rule, change notifications over Postgres LISTEN/NOTIFY
(`ChangePublisher` / `ChangeSubscriber`, channel `resource_changes`), and a ready-made
read-only REST controller (list, children, get with ETag).

See [AGENTS.md](AGENTS.md) for the API, wiring and properties.

    cd libs/resource-model
    mvn -B -f ../../pom.xml -pl libs/resource-model -am verify

Tests need Docker (Testcontainers, postgres:18.6).
