# world-engine

The advance (docs/actions.md, "Game time"): `Engine.advance(snapshot,
intent, rng, nextSeq, catalogue)` starts an intent's action and runs the
area's events and effects until a controlled entity is idle again. It
returns every entity's final state, the area's new time and the log
entries, all in memory. Pure Java on `libs/world-model`: no database, no
Spring, no clock. Part of the world split (#103); `services/world` wires it
in #112.

See [AGENTS.md](AGENTS.md) for the rules it keeps and what the caller does.

    cd libs/world-engine
    mvn -B -f ../../pom.xml -pl libs/world-engine -am verify

No Docker needed; the suite runs in a few seconds.
