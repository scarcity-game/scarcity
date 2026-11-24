// File names of the build output. Every emitted file carries the build's git sha
// and Rollup's content hash, so a new deploy never reuses a URL a browser or a
// proxy may hold under the year-long `immutable` cache of nginx (`/assets/`).
// The sha is a character set safe in file names (see sanitizeVersion in vite.config.ts).
export interface BuildNames {
  entryFileNames: string;
  chunkFileNames: string;
  assetFileNames: string;
}

export function buildNames(sha: string): BuildNames {
  return {
    entryFileNames: `assets/[name]-${sha}-[hash].js`,
    chunkFileNames: `assets/[name]-${sha}-[hash].js`,
    assetFileNames: `assets/[name]-${sha}-[hash][extname]`,
  };
}
