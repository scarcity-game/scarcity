// `npm run draw -- <category>`: runs scripts/draw/<category>.mjs, which writes categories/<category>.svg and
// its sprites.json entry, prints each frame's extent (scripts/extent.mjs), then runs the check and writes docs/sheet-<category>.png (even when the check
// fails, so the drawing can be seen). Exits non-zero when the check fails.
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { root } from '../lib.mjs';

const name = process.argv[2];
const script = join(root, 'scripts/draw', `${name}.mjs`);
if (!name || !existsSync(script)) {
  console.error('usage: npm run draw -- <category> (a scripts/draw/<category>.mjs must exist)');
  process.exit(1);
}
await import(pathToFileURL(script).href);
const node = (...args) => spawnSync(process.execPath, args, { cwd: root, stdio: 'inherit' }).status;
node('scripts/extent.mjs', name);
const checked = node('scripts/check.mjs');
node('scripts/sheet.mjs', name, '--png');
process.exit(checked);
