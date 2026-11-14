# world-model

Typed game state for the world: records for the game, area and entity
states, the action in progress, behaviours, controllers and draw records,
and `WorldJson`, their one JSON mapping. Pure Java, Jackson only. Part of
the world split (#103); actions, effects and the catalogue follow in #107.

See [AGENTS.md](AGENTS.md) for the types and the mapping rules.

    cd libs/world-model
    mvn -B -f ../../pom.xml -pl libs/world-model -am verify

No Docker needed; the suite runs in a few seconds.
