# content/sprites

SVG sprites for every Scarcity entity, drawn by category (humanoid, rodent,
stone, pillar, rubble, stairs) and recoloured per prototype by palette and
overlays. The setting they follow is in [`docs/setting.md`](../../docs/setting.md);
the rules for drawing and the manifest are in [`AGENTS.md`](AGENTS.md).

![every variant and frame on dark and light ground](docs/contact-sheet.png)

## Use
```sh
npm ci
npm test               # validate the manifest, the sheets and prototype coverage
npm run preview        # writes preview.html: open it in a browser
npm run contact-sheet  # writes docs/contact-sheet.svg; render to PNG with rsvg-convert
```

A sprite is a `<use href="#<symbol id>">` of a symbol from a sheet, inside an
`<svg viewBox="-16 -16 32 32">` that sets the palette as CSS variables
(`--sprite-body`, `--sprite-accent`, `--sprite-detail`, `--sprite-outline`);
the variant's overlays are further `<use>`s on top. `sprites.json` says which
symbols make which state, how long a frame lasts, and which palette and overlays
each prototype uses.

`npm test` also fails when a prototype in `libs/world-model` or `libs/world-seed`
has no variant, so a new prototype needs a sprite before it merges.
