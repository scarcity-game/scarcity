# The setting

What Scarcity is set in, and how it should look and sound. Content and art
work from this file; why it was chosen is in
[`decisions-settled.md`](decisions-settled.md) (2026-11-19).

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
| `humanoid` | animated | `player`, `diver` (lantern), `orbiter` (mage's hood), `goblin` (green skin, no overlay) |
| `rodent` | animated | `mouse`; later `rat` and kin |
| `canine` | animated | `wild-dog`; later `wolf` and kin |
| `arachnid` | animated | none yet; spiders and scorpions (`docs/categories.md` in `content/sprites` lists the categories to come) |
| `ooze` | animated | none yet; jellies and slimes (`docs/categories.md` in `content/sprites` lists the categories to come) |
| `fungus` | animated | none yet; mushrooms and moulds (`docs/categories.md` in `content/sprites` lists the categories to come) |
| `bird` | animated | none yet; crows, owls and vultures |
| `serpent` | animated | none yet; snakes and adders |
| `dragon` | animated | none yet; dragons and wyverns |
| `insect` | animated | none yet; wasps, moths and flies |
| `bat` | animated | none yet; bats and giant bats |
| `eye` | animated | none yet; floating eyes |
| `skeleton` | animated | none yet; skeletons and liches (crown overlay) |
| `feline` | animated | none yet; cat, lynx |
| `smallfolk` | animated | none yet; kobolds and gnomes (the goblin stays on `humanoid`) |
| `lizard` | animated | none yet; lizards, newts and drakes |
| `ungulate` | animated | none yet; boar, yak, deer, horse |
| `giant` | animated | none yet; ogres and trolls |
| `stone` | animated | `pebble` (a thrown sling stone) |
| `pillar` | static | `pillar` |
| `rubble` | static | `rubble` |
| `stairs` | static | `stairs-down`, `stairs-up` |
| `stash` | static | `stash` (a tied sack with stones at its foot: items left on the floor) |

## Walls
Each level's walls are of one material, drawn when the level is made:
mostly stone (granite, limestone, sandstone, basalt, slate, marble,
obsidian), sometimes brick or concrete, rarely timber (oak, pine), iron,
steel or ice ([`resources.md`](resources.md), "Materials"). Each game picks
one pattern per material from that material's candidates, so a game's
granite always looks the same and another game's may not. Walls are filled
with that tileable pattern
([`content/sprites/AGENTS.md`](../content/sprites/AGENTS.md), "Patterns"):
`ashlar` (dressed blocks, built halls), `brick`, `rubble` (fieldstone in
mortar), `setts`, `cave-rock` (natural caverns), `mossy` (wet levels),
`basalt` (column ends, deep and strange), `sandstone` (bedded layers),
`carved` (worn glyph blocks, the oldest depths), `timber` (shoring) and `bedrock` (near-black, cross-hatched strata: only the walls on an area's outline, the edge of the world, never a rock a level is made of).

New creatures join an existing category as a variant where they can; a new
silhouette is a new category.

## Names
Prototype and action names are not renamed to fit the setting. If a name
clashes with it, file an issue.
