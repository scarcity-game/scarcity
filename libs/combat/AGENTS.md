# AGENTS.md: libs/combat

The fighting rules (package `com.pessimisticit.scarcity.combat`; #451, epic
#449). Contract: `docs/action-types.md` (`strike`, `melee-attack`) and
`docs/resources.md` ("Attributes and factions": the keys these rules read).
Why it is a lib of its own: `docs/decisions.md`, 2026-11-26.

**Pure.** It depends on `libs/world-model` and nothing else at compile or
runtime scope (the enforcer rule `pure-combat` bans Spring, JDBC and
`resource-model`). Tests run strikes through `libs/world-engine`, which is
test scope only. Tests need no Docker. Lint applies with no baseline entries.

## API
| type | what |
|---|---|
| `Combat.addTo(catalogue)` | `catalogue` with `strike` and `melee-attack` (`Catalogue.with`); a name already there is an error |
| `Combat.standard()` | `Catalogue.standard()` with them: what `services/world` runs |
| `Strike` (`strike`, an `Action`) | params `{target}`. `start` timing; the duration is the striker's `strikeTime`, else `DEFAULT_TIME` (1 s). It starts only when the target is in the area, has a radius, isn't the striker, and is within `reach(striker)` (its `reach`, else its radius), measured by `gap(a, b)`: the centre distance minus both radii. It triggers `melee-attack`. `specFor(target)` is the `ActionSpec` a controller sends |
| `MeleeAttack` (`melee-attack`, an `Effect`) | params `{source, target}`. Draws `hit`, a gaussian with mean the source's `level` and sd `HIT_SD` (1.0). A value higher than the target's `level` (0 with none) hits and triggers `damage` with `damageRoll(attributes)`: mean `strength`, sd `DAMAGE_SD` (0.1) × strength, truncated to `[0, Double.MAX_VALUE)`. `result` `{targetLevel, hit}`, or `{skipped}` when the source or target is gone, the target has no hp, or the source has no attributes |

## Adding a rule
One class per action or effect, wired in `Combat.addTo` through
constructors, as world-model's rules are (its AGENTS.md, "Rule classes get
their collaborators through constructors"). Every random outcome is a
`ctx.draw(for, Draws…)`. A key a rule reads from an entity has to be a
world-model `Entity` key, because entity JSON rejects unknown keys. Put the
key there and document it in `docs/resources.md`. Add the rule's row to
`docs/action-types.md`.

## Tests
`StrikeTest` runs one strike per test through `Engine.advance` with a
scripted gaussian source. It covers reach edge to edge, a hit and a miss
with their logged draws, damage never below 0, a kill (through `damage`'s
death rule), and the strike time. The area has an idle npc onlooker: an
area with no player or npc in it doesn't advance (docs/actions.md,
"Turns").
