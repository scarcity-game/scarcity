import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from 'vitest';

/** Every rule's selector list and declarations, comments removed. */
function rules(css: string): { selectors: string[]; body: string }[] {
  const out: { selectors: string[]; body: string }[] = [];
  for (const m of css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    out.push({ selectors: (m[1] ?? '').split(',').map((s) => s.trim()), body: m[2] ?? '' });
  }
  return out;
}

// jsdom loads no CSS, so this reads the stylesheets: the log's current-entry
// outline and background once landed on the map's SVG rings through a stray
// selector list, and drew a white rectangle and a black square on the map.
test('a ring drawn on the map shares no rule with an HTML outline or background', () => {
  const dir = join(__dirname);
  const sheets = readdirSync(dir).filter((f) => f.endsWith('.css'));
  for (const sheet of sheets) {
    for (const { selectors, body } of rules(readFileSync(join(dir, sheet), 'utf8'))) {
      if (!selectors.some((s) => /-ring\b/.test(s))) continue;
      expect(selectors, `${sheet}: ${selectors.join(', ')}`).not.toContain('');
      expect(body, `${sheet}: ${selectors.join(', ')}`).not.toMatch(/outline|background/);
    }
  }
});
