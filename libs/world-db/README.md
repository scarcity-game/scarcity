# world-db

Loads an area into model objects and commits an advance of it:
- the area is written once;
- each entity is written once, at the versions the engine chose;
- one change batch is published with `logSeq`;
- the area log entries are written, then its head, with snapshots and
  pruning (docs/log.md).

`AreaLock` takes the area's log head `FOR UPDATE`, so intents on one area
run one at a time. `AreaLogReader` is the log's read query. Part of the
world split (#103). `services/world` wires it in #112.

See [AGENTS.md](AGENTS.md) for the commit's order and the tables it expects.

    cd libs/world-db
    mvn -B -f ../../pom.xml -pl libs/world-db -am verify

The tests need Docker (Testcontainers Postgres). The suite runs in about 11 seconds, and most of that is the
container start.
