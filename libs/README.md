# libs

Shared libraries (Java 25 by default, children of the root `pom.xml`).
Built: `resource-model` (the resource envelope, store, notifications and read API), `world-model` (typed game state, its JSON and the rules), `world-engine` (the advance, in memory) `world-db` (loading an area and committing an advance) and `world-seed` (the test area: ids, states, seeding).
Planned: `world-seed` (#103), `distributions` (seeded, self-describing randomness), `mapgen`.

Each library gets its own `AGENTS.md`, `README.md` and an entry in `/modules.yaml`.
