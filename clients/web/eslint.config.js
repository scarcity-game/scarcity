import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

// Complexity limits: see AGENTS.md. A limit is raised only with the human's OK.
const limits = {
  complexity: ['error', 8],
  'max-depth': ['error', 3],
  'max-params': ['error', 4],
  'max-statements': ['error', 15],
  'max-nested-callbacks': ['error', 3],
  'max-lines-per-function': [
    'error',
    { max: 30, skipBlankLines: true, skipComments: true },
  ],
};

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'coverage'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    // The classic hooks rules only; the React Compiler rules in the plugin's
    // `recommended` preset (refs, immutability, set-state-in-effect) are not on.
    plugins: { 'react-hooks': reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error',
    },
  },
  { files: ['**/*.{ts,tsx}'], rules: limits },
  {
    files: ['**/*.test.{ts,tsx}', 'src/test/**'],
    rules: {
      'max-lines-per-function': [
        'error',
        { max: 400, skipBlankLines: true, skipComments: true },
      ],
    },
  },
);
