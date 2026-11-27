# Creature categories: research and briefs

Which body shapes Scarcity draws, and a brief for each one to be drawn. A
category is a **silhouette**: every prototype in it shares the frames and
differs only by palette and overlays (`../AGENTS.md`). Twenty creature categories
should cover most monsters a DCSS-like game needs. Three exist already
(`humanoid`, `rodent`, `canine`) and `arachnid` is the first of the seventeen below; the rest are to be drawn.

## Research
Classic roguelikes group monsters by glyph, and a glyph is mostly a body
shape. Sources: the DCSS monster lists
([crawl.chaosforge.org](https://crawl.chaosforge.org/Category:Lists_of_monsters)),
and NetHack's monster classes
([nethackwiki](https://nethackwiki.com/wiki/Monster_class)). Glyphs vary
between DCSS versions; the ones below are the common ones.

The shapes both games give a glyph of their own, roughly most populous first:
- **Arthropods.** These are the largest group in DCSS: spiders and scorpions (`s`),
  flying insects (`y`), beetles and ants (`B`). NetHack has `a`, `s` and `x`.
- **Humanoids, by size:** small (DCSS kobolds `K`, goblins and gnolls; NetHack
  `k`, `G`, `o`), human-sized (`p`/`@`, orcs `o`, elves `e`/`@`), large (ogres
  `O`, trolls `T`, giants `C`; NetHack `O`, `T`, `H`).
- **Reptiles:** snakes (`S`), lizards and crocodiles (`l`/`:`), drakes
  (DCSS `k`) and dragons (`D`).
- **Mammals:** dogs (DCSS `h`, NetHack `d`), cats (`f`), rodents (`r`), bats
  (`b`/`B`), hoofed beasts (yaks `Y`, horses `u`, NetHack `q`).
- **Birds** (DCSS shares `b`; NetHack `B`).
- **Undead:** skeletons and zombies (`z`/`Z`), wraiths and ghosts (`W`),
  liches and mummies (`L`, `M`).
- **Demons** (`&`, DCSS digits `1`-`5`, NetHack `i` for the small ones).
- **Amorphous:** jellies and oozes (`J`; NetHack `j`, `P`, `b`), floating
  eyes (`e`), fungi and plants (`f`/`F`).
- Fewer, or stranger: elementals and vortices (`E`, `v`), golems (`'`),
  worms (`w`), centaurs and nagas (`c`, `N`), merfolk and fish (`m`, `;`),
  mimics, abominations.

## The twenty
Existing: `humanoid` (the player's size), `rodent`, `canine`. To draw:

| category | silhouette at 16 px | DCSS / NetHack | first prototypes |
|---|---|---|---|
| `arachnid` (drawn) | low wide body, eight bent legs | `s` / `s` | spider, scorpion (overlay: tail) |
| `insect` | small body hanging between two big wings | `y` / `a`, `x` | wasp, moth, fly |
| `serpent` | S-curve, raised head, no legs | `S` / `S` | snake, adder |
| `lizard` | long low body, four splayed legs, tail on the ground | `l`, `k` / `:` | lizard, newt, drake |
| `dragon` | big four-legged reptile, raised wings, long neck | `D` / `D` | dragons, wyverns |
| `bird` | round body, beak, two thin legs, folded wing | `b` / `B` | crow, owl, vulture |
| `bat` | wings spread wide, small body between them, ears | `b` / `B` | bat, giant bat |
| `feline` | lean four-legged, small round head, tail curled up | `f` / `f` | cat, lynx |
| `ungulate` | big body on four long straight legs, horns or long head | `Y` / `q`, `u` | boar, yak, deer, horse |
| `smallfolk` | half-height humanoid, big head, short legs | `K`, `g` / `k`, `G` | kobold, gnome, goblin (now `humanoid`) |
| `giant` | humanoid filling the box, hunched, huge arms, small head | `O`, `T`, `C` / `O`, `T`, `H` | ogre, troll |
| `skeleton` | humanoid made of bones: ribcage, skull, gaps | `z` / `Z` | skeleton, lich (overlay: crown) |
| `spectre` | hooded floating shape, no legs, ragged hem | `W` / `W` | ghost, wraith |
| `demon` | humanoid with horns, bat wings and a tail | `&` / `&`, `i` | imp, demon |
| `ooze` | dome of slime on the floor, no limbs | `J` / `j`, `P` | jelly, slime |
| `eye` | one big floating eye ball, a trailing nerve | `e` / `e` | floating eye |
| `fungus` | cap on a thick stem, rooted, no limbs | `f` / `F` | mushroom, mould |

Not now: elementals and vortices, golems, worms, centaurs and nagas,
merfolk and fish. They are fewer, and most can borrow a category until they
have one.

## Briefs
Each brief gives the silhouette, what keeps it apart from its nearest
neighbour, the slot roles, the overlay anchors and a sample palette (the
sheet's fallback colours, used for the preview until a prototype uses it).
A sample body has a contrast of at least 2.4 against the dark ground
(`#16130f`), which `npm test` checks: a darker one vanishes at 16 px.
Every animated category has `idle` (2), `moving` (4), `acting` (2) and
`acted-upon` (2) frames, with frameMs 600, 120, 150, 150, faces right and
shows a hit as the accent star near its head in `acted-upon`, as the existing
categories do; its two `acted-upon` frames differ at a glance (a recoil, then a
flinch or cower, the star shrinking).

### `arachnid` (drawn: `scripts/draw/arachnid.mjs`)
- **Silhouette:** big round abdomen at the back (left), smaller head at the
  front (right), eight legs: four near ones bent high above the body, knees
  up, feet on the ground; four far ones darker behind them.
- **Apart from:** `insect` (no wings, eight legs not six, on the ground) and
  `canine` (no head on a neck, no straight legs).
- **Slots:** body = abdomen and head; detail = far legs, fangs; accent = the
  eyes and a mark on the abdomen (a redback's hourglass); outline.
- **Anchors:** the abdomen top (a scorpion's tail overlay curls from it).
- **Motion:** moving alternates leg pairs; acting rears the front, fangs out.
- **Sample palette:** body `#6b5a48`, accent `#c43d2e`, detail `#43372b`, outline `#17120d`.

### `insect`
- **Silhouette:** segmented body (head, thorax, striped abdomen) between two
  large translucent wings held up; thin legs hang below. It hovers.
- **Apart from:** `bat` (no fur body, wings are two clear ovals, not webbed
  fingers) and `arachnid` (it flies; the wings are the read).
- **Slots:** body = thorax and abdomen; accent = stripes or a stinger; detail
  = wings (lighter, may use `fill-opacity`); outline.
- **Motion:** idle and moving beat the wings (bob up and down);
  acting thrusts the stinger forward.
- **Sample palette:** body `#a8862f`, accent `#2a2016`, detail `#cfc8b0`, outline `#1d1810`.

### `serpent`
- **Silhouette:** a body in an S on the ground rising to a raised head facing
  right, tongue out. No limbs.
- **Apart from:** `lizard` (no legs) and worms (a head with eyes, raised).
- **Slots:** body = scales; detail = belly; accent = eyes, tongue, a pattern;
  outline.
- **Motion:** moving slides the S (shift its waves); acting strikes forward
  with the jaw open.
- **Sample palette:** body `#5b6b3a`, accent `#c9a43b`, detail `#a9a77a`, outline `#1a1d10`.

### `lizard`
- **Silhouette:** long low body close to the ground, four short splayed legs,
  long tapering tail on the ground behind, wedge head.
- **Apart from:** `dragon` (no wings, low, small) and `serpent` (legs).
- **Slots:** body = back; detail = belly, legs; accent = eye, crest or
  spines; outline.
- **Motion:** moving swings legs diagonal pairs and the tail; acting opens
  the jaw.
- **Sample palette:** body `#6f7448`, accent `#d1a43a`, detail `#4c4f30`, outline `#1b1c10`.

### `dragon`
- **Silhouette:** a heavy four-legged reptile filling the box, two wings
  raised high behind, long neck rising to a horned head facing right.
- **Apart from:** `lizard` (wings, size, neck) and `bat` (a body on legs).
- **Slots:** body = scales; detail = wing membrane, belly; accent = eyes,
  breath, horns; outline.
- **Anchors:** the mouth (a breath overlay), the back between the wings.
- **Motion:** acting rears the head and opens the jaw (breath).
- **Sample palette:** body `#9a4636`, accent `#e8a23a`, detail `#5f2a20`, outline `#1d0d0a`.

### `bird`
- **Silhouette:** egg-shaped body upright on two thin legs, folded wing on
  the side, round head with a clear beak pointing right, tail behind.
- **Apart from:** `bat` (wings folded, standing, beak).
- **Slots:** body = feathers; detail = wing, tail; accent = beak, legs, eye; outline.
- **Motion:** moving hops; acting pecks or flares the wings.
- **Sample palette:** body `#3a3633`, accent `#d1a540`, detail `#252220`, outline `#100e0d`.

### `bat`
- **Silhouette:** wings spread to both edges of the box, webbed between finger
  bones, a small furred body and big ears in the middle. It hovers.
- **Apart from:** `insect` (webbed, scalloped wings with bones; ears) and
  `demon` (no legs or arms, all wing).
- **Slots:** body = fur; detail = wing membrane; accent = eyes, fangs; outline.
- **Motion:** idle and moving flap (wings up, wings down); acting dives with
  fangs.
- **Sample palette:** body `#6e5c50`, accent `#c4533e`, detail `#41352e`, outline `#120e0c`.

### `feline`
- **Silhouette:** lean, arched four-legged body, small round head with
  pointed ears, long tail curled up.
- **Apart from:** `canine` (rounder head, no muzzle, tail up and curled,
  lower and longer body).
- **Slots:** body = fur; detail = stripes or spots, legs; accent = eyes; outline.
- **Motion:** moving slinks low; acting crouches and pounces.
- **Sample palette:** body `#9c7a4a`, accent `#d9c35a`, detail `#5e4628`, outline `#1f170d`.

### `ungulate`
- **Silhouette:** big barrel body high on four long straight legs with
  hooves, long head held forward, horns or tusks.
- **Apart from:** `canine` (taller, longer legs, hooves, bigger body).
- **Slots:** body = hide; detail = mane, legs, hooves; accent = horns or
  tusks, eye; outline.
- **Motion:** moving trots; acting lowers the head and charges.
- **Sample palette:** body `#6d5038`, accent `#d8ccb0`, detail `#3f2c1d`, outline `#1a120b`.

### `smallfolk`
- **Silhouette:** a humanoid half the height of the box (feet on the ground,
  head at the centre), big head, big ears or nose, short legs, a crude
  weapon.
- **Apart from:** `humanoid` (half its height, big head, bandy legs) and
  `giant`.
- **Slots:** body = clothes; detail = skin; accent = eyes, weapon; outline.
- **Anchors:** the head (hats, hoods), the weapon hand.
- **Sample palette:** body `#7a6244`, accent `#b0532e`, detail `#7f9a4a`, outline `#1e1a10`.

### `giant`
- **Silhouette:** a hunched humanoid filling the whole box, wide shoulders,
  huge arms hanging to the knees, small head low between the shoulders, a
  club.
- **Apart from:** `humanoid` (much wider, hunched, small head) and `ungulate`.
- **Slots:** body = hide or clothes; detail = skin; accent = eyes, club,
  tusks; outline.
- **Anchors:** the head (helms), the club hand.
- **Sample palette:** body `#5d5a3f`, accent `#c9b27a`, detail `#8a7a5a`, outline `#1b1a10`.

### `skeleton`
- **Silhouette:** a humanoid of bones: a skull, a ribcage with gaps between
  ribs, a spine, thin limbs, gaps where flesh would be.
- **Apart from:** `humanoid` (holes through it; skull) and `spectre` (legs).
- **Slots:** body = bone; detail = shadow, rags; accent = eye glow; outline.
- **Anchors:** the skull (a lich's crown), the weapon hand.
- **Sample palette:** body `#d8d0bc`, accent `#5fd0c0`, detail `#8a8270`, outline `#1d1a14`.

### `spectre`
- **Silhouette:** a hooded robe tapering to a ragged hem that floats above
  the ground, no feet, long sleeves, a dark face with two eyes.
- **Apart from:** `humanoid` (no legs; it floats) and the `orbiter`'s hood.
- **Slots:** body = robe (may use `fill-opacity`); detail = face shadow;
  accent = eyes; outline.
- **Motion:** idle and moving bob and ripple the hem; acting reaches out.
- **Sample palette:** body `#8c94a0`, accent `#5fd0c0`, detail `#262a30`, outline `#101216`.

### `demon`
- **Silhouette:** a humanoid with two horns, bat wings rising behind the
  shoulders, a pointed tail, clawed hands.
- **Apart from:** `humanoid` (horns and wings) and `bat` (a body on legs).
- **Slots:** body = skin; detail = wings, horns; accent = eyes, claws; outline.
- **Anchors:** between the horns (crowns), the hand (fire).
- **Sample palette:** body `#a24a38`, accent `#f2b84a`, detail `#5a241c`, outline `#1d0a08`.

### `ooze`
- **Silhouette:** a dome or blob spread on the ground, no limbs, a wet
  highlight, bits floating inside.
- **Apart from:** `stash` and `rubble` (it is soft and shiny; it moves) and
  `fungus` (no stem).
- **Slots:** body = jelly (may use `fill-opacity`); detail = what floats in
  it; accent = highlight or eyes; outline.
- **Motion:** idle wobbles; moving stretches forward then catches up; acting
  rears a pseudopod.
- **Sample palette:** body `#5d8a4a`, accent `#d9e6a0`, detail `#3a5a2e`, outline `#121c0e`.

### `eye`
- **Silhouette:** a big sphere floating above the ground with one huge eye
  (iris and pupil looking right) and trailing stalks or a nerve below.
- **Apart from:** `ooze` (floats, round, one eye) and `stone`.
- **Slots:** body = the ball; detail = veins, lid, stalks; accent = iris; outline.
- **Motion:** idle bobs and blinks; acting widens the eye (a gaze).
- **Sample palette:** body `#d6c8b4`, accent `#4f8ac0`, detail `#9a4a40`, outline `#1d1612`.

### `fungus`
- **Silhouette:** a broad cap on a thick stem rooted in the ground, gills
  under the cap, spots on top. It barely moves.
- **Apart from:** `pillar` (organic and curved) and `ooze` (a stem).
- **Slots:** body = cap; detail = stem and gills; accent = spots or glow; outline.
- **Motion:** idle breathes (cap rises a little); moving shuffles; acting
  puffs a cloud of spores (accent).
- **Sample palette:** body `#8a4a3a`, accent `#e6d6b0`, detail `#cbbfa6`, outline `#1d120e`.
