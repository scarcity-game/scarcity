import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync(new URL('../index.css', import.meta.url), 'utf8');

function rule(selector: string): string {
  const start = css.indexOf(`\n${selector} {`);
  expect(start, `rule ${selector}`).toBeGreaterThanOrEqual(0);
  return css.slice(start, css.indexOf('}', start));
}

// jsdom does no layout, so this guards the cause of the clipped header, not its look: a grid item
// that scrolls has an automatic minimum size of 0, so its `auto` row collapsed to a strip.
describe('area screen stylesheet', () => {
  it('sizes the header row to its content and never lets the header scroll', () => {
    expect(rule('.area-screen')).toContain('grid-template-rows: max-content');
    expect(rule(".area-screen[data-layout='stacked']")).toContain('grid-template-rows: max-content');
    expect(rule('.area-bar')).not.toMatch(/overflow:\s*(auto|hidden|scroll)/);
    expect(rule('.area-bar')).not.toContain('max-height');
  });
});
