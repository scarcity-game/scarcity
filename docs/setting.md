# The setting

What Scarcity is set in, and how it should look and sound. Content and art
work from this file; why it was chosen is in
[`decisions.md`](decisions.md) (2026-11-19).

## Setting and tone
Low fantasy, underground. A delve into an old, deep dungeon where everything
is scarce: light, food, health, time. The tone is grim and practical, a little
wry (DCSS rather than high epic). Magic exists, but it is costly and physical;
the orbiter's thrown stones are the model. The deeper the level, the older and
stranger it gets.

## Palette direction
- **The world:** dungeon stone greys and earth browns.
- **Warm accent:** torchlight amber, for light, fire and the delver.
- **Creatures:** muted natural hues.
- **One cool accent** (teal or violet), kept for magic and nothing else.
- **The client** gets dark backgrounds that suit this. Sprites carry a dark
  outline slot so they also read on light ground
  ([`content/sprites/AGENTS.md`](../content/sprites/AGENTS.md)).

## Categories
A sprite shows its category at a glance; variants differ by palette and
overlays, not by silhouette (as every `o` in DCSS is an orc, and the colour
says which kind).

| category | kind | prototypes |
|---|---|---|
| `humanoid` | animated | `player`, `diver` (lantern), `orbiter` (mage's hood) |
| `rodent` | animated | `mouse`; later `rat` and kin |
| `stone` | animated | `pebble` (a thrown sling stone) |
| `pillar` | static | `pillar` |
| `rubble` | static | `rubble` |
| `stairs` | static | `stairs-down`, `stairs-up` |

New creatures join an existing category as a variant where they can; a new
silhouette is a new category.

## Names
Prototype and action names are not renamed to fit the setting. If a name
clashes with it, file an issue.
