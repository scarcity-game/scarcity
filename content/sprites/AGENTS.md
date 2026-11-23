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
| `sprites.json` | the manifest; `sprites.schema.json` is its JSON Schema |
| `scripts/check.mjs` | `npm test`: the rules below |
| `scripts/preview.mjs` | `npm run preview` writes `preview.html` (git-ignored): every variant and state, animated, at 16, 32, 64 px on dark and light ground |
| `scripts/contact-sheet.mjs` | `npm run contact-sheet` writes `docs/contact-sheet.svg` (git-ignored); the committed `docs/contact-sheet.png` is `rsvg-convert docs/contact-sheet.svg -o docs/contact-sheet.png` of it, for reviewing the art without a browser. Regenerate it when art changes |

## The manifest (`sprites.json`)
- `grid`: `32`.
- `categories`: name to `{kind: "static"|"animated", file, fallbackColor, states: {<state>: {frames: [symbol id...], frameMs}}}`.
  Static categories have one state `static` with one frame and `frameMs` 0.
  Animated ones have `idle` (2-4 frames), `moving` (4), `acting` (2-4) and
  `acted-upon` (2-4); `stone` has only `idle` and `moving`.
- `overlays`: name to symbol id.
- `variants`: prototype to `{category, palette: {body, accent, detail, outline}, overlays: [...]}`.
- `fallbackColor` is what the client draws as a plain disc for a prototype with no variant.

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
  `fill-rule`, `id` and `transform` (translate and scale only). Nothing else: no `script`, `image`,
  `foreignObject`, `style`, `text`, filters, gradients, `href`s or data.
- **Size:** each category sheet under 12 KB (the largest, `rodent`, is about 11.5 KB; split a new state into fewer frames before adding bytes).
- **Overlays are static,** drawn over every frame at the same place. Keep each category's anchors still across frames:
  humanoid's head is fixed (the hood sits on it), the lantern hangs at the right hip.

## The check (`npm test`, run in CI)
Fails when: the manifest breaks the schema; a file isn't XML; a symbol named in the manifest
is missing or a symbol is not named (stray) or misnamed; an element, attribute or colour is
outside the rules; a shape has no `fill`; a stroke is under 1.5; a symbol's bounding box
(strokes included) leaves the viewBox; a sheet is 12 KB or more; a state has the wrong number
of frames; or a prototype in `libs/world-model/src/main/resources/prototypes/*.json` or
`libs/world-seed/src/main/resources/seed/*.json` has no variant (`placeholder` excepted, and any prototype with a `material` and no `radius`: it is a polygon, drawn with its material's pattern, #297).
Those two directories are in this module's `paths` in `modules.yaml`, so **a new prototype
fails CI here until it has a variant**: add one (an existing category with a new palette and
overlays if it can be, else a new category and sheet).

## Adding a sprite
1. New variant of an existing category: add it to `variants` in `sprites.json` (palette + overlays; a new overlay is a symbol in `overlays.svg` plus an entry in `overlays`).
2. New category: a sheet in `categories/`, an entry in `categories`, and the setting table in `docs/setting.md`.
3. `npm test`, `npm run preview`, then `npm run contact-sheet`, render it and commit the PNG.

## Out of scope
Restyling the client, renaming prototypes. The client (`clients/web/src/sprites/`) draws the sprites;
`clients/web`'s `paths` in `modules.yaml` include this module, so an art change rebuilds the client.
The `placeholder` entity has no sprite; the client draws the arriving entity's own, faded.
