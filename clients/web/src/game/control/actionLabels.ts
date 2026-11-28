// What the action bar calls each action type (`docs/action-types.md`); an unknown type shows its name.

const LABELS: Readonly<Record<string, string>> = {
  step: 'Move',
  wait: 'Wait',
  strike: 'Strike',
  throw: 'Throw',
  dig: 'Dig',
  fill: 'Fill',
};

export function actionLabel(type: string): string {
  return LABELS[type] ?? type;
}
