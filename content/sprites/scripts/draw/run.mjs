// `npm run draw -- <category>`: runs scripts/draw/<category>.mjs, which writes categories/<category>.svg.
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
