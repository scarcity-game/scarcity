# content/sprites: module agent notes

**Kind:** content · **Stack:** SVG + one JSON manifest, checked by a Node script
(`ajv`, `fast-xml-parser`) · **Deployed:** no; `clients/web` bundles it (#208)

## Purpose
The pictures of every entity. Setting and palette direction:
[`docs/setting.md`](../../docs/setting.md). **Category first, variant second:** a
sprite shows its category at a glance (every rodent is the same silhouette) and
a variant (one per prototype) differs only by **palette** and **overlays**,
never by a new silhouette. Mapping a prototype to a variant is data in
`sprites.json`, not code.

## Files
| path | holds |
|---|---|
| `categories/<category>.svg` | one sheet per category: one `<symbol id="<category>.<state>.<n>">` per frame (static: `<category>.static.0`) |
| `overlays.svg` | one `<symbol id="overlay.<name>">` per overlay |
| `effects.svg` | one `<symbol id="effect.<kind>">` per effect kind (`physical`, `magic`), centred on the origin; written by `node scripts/draw/effects.mjs` |
| `patterns/<name>.svg` | one wall pattern: a single tileable `<symbol id="pattern.<name>" viewBox="0 0 64 64">` |
| `sprites.json` | the manifest; `sprites.schema.json` is its JSON Schema |
| `docs/categories.md` | the creature categories to draw: research, and one brief per category (silhouette, neighbours, slots, motion, sample palette) |
| `scripts/draw/<category>.mjs` | the script that writes `categories/<category>.svg` and its `sprites.json` entry; `scripts/draw/kit.mjs` holds the shared pieces: slots, `limbs` (outlined limbs), `tube` (a tapering body), `leaf` (a pointed oval: wing, ear, flame), `turn` (rotate points), `star` (the hit star, kept inside the box) and `sheet` (the writer). The older, hand-drawn sheets have none |
| `scripts/sheet.mjs` | `npm run sheet -- <category> --png` writes `docs/sheet-<category>.png` (git-ignored): every frame at 128 px on the grid with the radius circle, every frame at 16, 32 and 64 px on dark and light ground, and every category side by side, filled and as a silhouette |
| `scripts/check.mjs` | `npm test`: the rules below |
| `scripts/preview.mjs` | `npm run preview` writes `preview.html` (git-ignored): every variant and state, animated, at 16, 32, 64 px on dark and light ground |
| `docs/patterns.png` | every pattern (bedrock too) repeated 3 x 3, to see the seams; regenerate it when a pattern changes |
| `scripts/contact-sheet.mjs` | `npm run contact-sheet` writes `docs/contact-sheet.svg` (git-ignored); the committed `docs/contact-sheet.png` is `rsvg-convert docs/contact-sheet.svg -o docs/contact-sheet.png` of it, for reviewing the art without a browser. Regenerate it when art changes |

## The manifest (`sprites.json`)
- `grid`: `32`.
- `categories`: name to `{kind: "static"|"animated", file, fallbackColor, sample?, states: {<state>: {frames: [symbol id...], frameMs, anchors?}}}`.
  `anchors` has one entry per frame: named points of that frame, `[x, y]` or `[x, y, deg]` (deg clockwise
  from pointing right): `impact` (where an effect's icon goes; every acted-upon frame needs one), and as gear
  needs them `head`, `hand.main`, `hand.off`, `hip.main`, `back`. Draw scripts write them (`sheet()`).
  Static categories have one state `static` with one frame and `frameMs` 0.
  Animated ones have `idle` (2-4 frames), `moving` (4), `acting` (2-4) and
  `acted-upon` (2-4); `stone` has only `idle` and `moving`.
- `overlays`: name to a symbol id (drawn where it is), or `{symbol, anchor, at}`: drawn moved (and turned) from
  its rest point `at` to the frame's `anchor`, so it follows the pose. Every frame of a category that a variant
  with it uses needs that anchor.
- `effects`: `{kinds: {<kind>: {symbol, palette}}, types: {<effect type>: <kind>}, default}`. The client draws
  the icon of an effect's kind, in the kind's own palette, at the `impact` anchor of whatever the effect acts
  upon (even a static sprite: then above the middle). Effect types not in `types` are `default` (`physical`).
- `variants`: prototype to `{category, palette: {body, accent, detail, outline}, overlays: [...]}`.
- `fallbackColor` is what the client draws as a plain disc for a prototype with no variant.
- `sample` (optional): a category's palette for the preview, the contact sheet and `npm run sheet` while no
  variant uses it, so a new category can be drawn and reviewed before its first prototype exists.
- `patterns`: name to `{file: "patterns/<name>.svg", tile: 64, metres, fallbackColor, description}`. A wall
  (a polygon entity, docs/resources.md "Walls") is filled with its material's pattern, repeated every `metres`
  in the area (1.6 m: two 0.8 m cells); `fallbackColor` is its flat fill.
- `materials`: an entity `material` to the pattern it is drawn with when its game has no
  `materialPatterns` of its own (docs/resources.md, "Materials"): one entry per material of
  `libs/world-model/src/main/resources/materials.json`, the first of that material's `patterns`
  (`granite` → `ashlar`, `oak` → `timber`). A game draws its own map over the same candidates.

## Patterns
- **Tile:** `viewBox="0 0 64 64"`, everything inside the tile. It repeats edge to edge, so **nothing may show a
  seam**: a shape that crosses an edge is drawn as its pieces on both sides (cut flush at the edge, fill only,
  no stroke along the cut; outlines are a slightly larger dark shape underneath), and a regular grid's period
  must divide 64.
- Same element, attribute and colour rules as sprites (slots with fallbacks, so a variant could recolour one);
  no `<pattern>` element: the client wraps the symbol in its own.
- A tile is under 12 KB: group runs of one fill in a `<g fill>` rather than repeating the slot string.
- The setting's stone greys and earth browns; deeper and stranger levels get the stranger ones (`basalt`,
  `carved`). Keep teal or violet for magic (a `carved` variant with a magic accent may use it).

## Drawing rules
- **Grid:** 32 x 32, `viewBox="-16 -16 32 32"` on the sheet and every symbol, origin at
  the entity's `position`. The circle of radius 16 is the entity's `radius`
  (#206); the client scales each sprite to it. Keep the silhouette inside the box and mostly inside the circle.
- **View:** front three-quarter, upright like a token, never rotated. Animated sprites face **right**.
- **Legible at 16 px and good at 64 px:** bold solid shapes, no detail below 2 units, no stroke thinner than 1.5.
- **Colour only through slots:** `fill`/`stroke` are `var(--sprite-body|accent|detail|outline, #fallback)`,
  `none` or `currentColor`. Every sprite has an outline (slot `outline`, 1.5 wide) so it reads on light and dark ground.
  Slot roles are per category (body: main mass, accent: the bright or magic bit, detail: skin, shade, secondary, outline: the dark edge).
- **Allowed SVG:** `symbol`, `g`, `path` (absolute `M L H V C Q Z` only), `circle`, `ellipse`, `rect`,
  `polygon`, `polyline`, `line`, with geometry, `fill`, `stroke`, `stroke-*`, `*-opacity`,
  `fill-rule`, `id` and `transform` (`translate`, `scale`, `rotate`). Nothing else: no `script`, `image`,
  `foreignObject`, `style`, `text`, filters, gradients, `href`s or data.
- **Size:** each frame (and overlay) symbol at most 2 KB. Today's frames are about 1.1 KB; the cap is a backstop against pasted-in art, not a style rule (decisions.md, 2026-11-27).
- **No effect art in frames.** A hit is a pose (a recoil, a flinch); the star or sparkle is the client's,
  drawn from `effects.svg` at the frame's `impact` anchor, so it reads on every palette and says physical or magic.
- **Anchored overlays follow the pose**: give every frame the anchors its gear hangs on (humanoid's hood on
  `head`, lantern on `hip.main`). The sheet view shows them as blue dots.

## The check (`npm test`, run in CI)
Fails when: the manifest breaks the schema; a file isn't XML; a pattern's tile isn't 0..64, its symbol isn't
`pattern.<name>` in `patterns/<name>.svg`, or a material names no pattern; a symbol named in the manifest
is missing or a symbol is not named (stray) or misnamed; an element, attribute or colour is
outside the rules; a shape has no `fill`; a stroke is under 1.5; a symbol's bounding box
(strokes included) leaves the viewBox; a frame or overlay symbol is over 2 KB, or a pattern tile over 12 KB; a state has the wrong number
of frames; or a prototype in `libs/world-model/src/main/resources/prototypes/*.json` or
`libs/world-seed/src/main/resources/seed/*.json` has no variant (`placeholder` excepted, and any fixed prototype with no `radius`: it is a polygon, drawn with its material's pattern, #297). The test also reads the materials catalogue (`libs/world-model/src/main/resources/materials.json`): every pattern a material names must be in `patterns`, and `materials` must have exactly the catalogue's materials, each mapped to one of its own patterns. `bedrock` (very dark, cross-hatched and banded in strata) is the marker pattern of the walls on an area's outline: it is no rock, so keep it unlike every rock pattern.
Those two directories are in this module's `paths` in `modules.yaml`, so **a new prototype
fails CI here until it has a variant**: add one (an existing category with a new palette and
overlays if it can be, else a new category and sheet).

## Adding a sprite
1. New variant of an existing category: add it to `variants` in `sprites.json` (palette + overlays; a new overlay is a symbol in `overlays.svg` plus an entry in `overlays`).
2. New category: follow its brief in `docs/categories.md` (or add one), then the loop below. The draw
   script writes its entry in `categories` (pass `sample` to `sheet()`); add its row to the setting
   table in `docs/setting.md`. Nothing changes in `clients/web`: the client globs `categories/*.svg`.
3. `npm test`, `npm run preview`, then `npm run contact-sheet`, render it and commit the PNG.

## Drawing a category
Write `scripts/draw/<category>.mjs` rather than typing the SVG: describe the creature once as a function of
a few parameters (offset, a lifted leg, a raised head) and make each frame a call to it, so frames stay
consistent and a change is one edit. Then loop, looking at the PNG every time (you can read images):
```sh
npm run draw -- <category>   # writes the sheet and its manifest entry, runs npm test, writes docs/sheet-<category>.png
```
It writes the PNG even when the check fails, so you can see what is out of the box; a bounds failure names
the first shapes and points outside it.
Lessons from the first one (`arachnid`):
- **Look at it at 16 px first.** The silhouette line-up is the test: does it read apart from every other
  category with no colour at all?
- **Chunky beats accurate.** A big body and few, thick (2 + outline) limbs; thin long legs vanish at 32 px
  and turn into a cage at 128.
- **The sample palette must read on both grounds.** Body near black disappears on dark ground; keep the
  body mid-tone (contrast 2.4 or more with `#16130f`, checked) and let the outline carry the edge.
- **A category's silhouette must differ from the others', not only its colour.** The first serpent read as
  the bird (a round head top right, a point sticking right, a mass lower left). The line-up shows the
  categories on your branch only: also compare the sheet images of the other open `sprites: draw the` PRs.
- **Draw order is the readability.** Limbs behind the main mass keep its shape whole; limbs in front of the
  head hide the face.
- **Frames of one state must differ at a glance**, not by a half-unit nudge: in `acted-upon` the arachnid
  first jolts back with its front legs up, then cowers flat. Compare
  each state's frames side by side in the sheet view.
- **Bytes:** `limbs()` draws a set of limbs as one path stroked twice and lets the outline pass inherit the
  frame group's stroke; shapes that share a fill can share a `<g>`. A frame is about 1.1 KB that way.
- **Bounds include strokes**: a foot at x 14 with a 4-wide outlined limb ends at 16; keep feet inside
  ±13.5 and remember the frame offsets (`dx`) of moving and acted-upon frames.

## Out of scope
Restyling the client, renaming prototypes. The client (`clients/web/src/sprites/`) draws the sprites;
`clients/web`'s `paths` in `modules.yaml` include this module, so an art change rebuilds the client.
The `placeholder` entity has no sprite; the client draws the arriving entity's own, faded.
