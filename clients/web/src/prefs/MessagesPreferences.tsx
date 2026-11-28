import {
  MAX_LEVEL,
  MESSAGE_CATEGORIES,
  MESSAGE_TYPES,
  MIN_LEVEL,
  colourPreference,
  useMinLevel,
  type MessageCategory,
  type MessageColours,
  type MessageType,
} from './messages.ts';
import { usePreference } from './store.ts';

function MinLevel({ category, label }: { category: MessageCategory; label: string }) {
  const [level, setLevel] = useMinLevel(category);
  const levels = Array.from({ length: MAX_LEVEL - MIN_LEVEL + 1 }, (_, i) => MIN_LEVEL + i);
  return (
    <label className="pref-level">
      Minimum level
      <select value={level} onChange={(e) => setLevel(Number(e.target.value))}>
        {levels.map((l) => (
          <option key={l} value={l}>
            {l}
          </option>
        ))}
      </select>
      <span className="pref-hint">{`Hides ${label} messages under level ${level}.`}</span>
    </label>
  );
}

function ColourInput({
  caption,
  label,
  value,
  onChange,
}: {
  caption: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <label>
      {caption}
      <input
        type="color"
        aria-label={label}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
}

function Sample({ type, colours }: { type: MessageType; colours: MessageColours }) {
  return (
    <span
      className="pref-sample"
      data-testid={`sample-${type.category}-${type.id}`}
      style={{ color: colours.fg, backgroundColor: colours.bg }}
    >
      {`${type.label}: a sample message`}
    </span>
  );
}

function ResetButton({
  type,
  isDefault,
  onReset,
}: {
  type: MessageType;
  isDefault: boolean;
  onReset: () => void;
}) {
  return (
    <button
      type="button"
      disabled={isDefault}
      aria-label={`Reset ${type.label} colours`}
      onClick={onReset}
    >
      Reset
    </button>
  );
}

function TypeRow({ type }: { type: MessageType }) {
  const [colours, set] = usePreference(colourPreference(type));
  const change = (next: MessageColours) =>
    set(next.fg === type.defaults.fg && next.bg === type.defaults.bg ? type.defaults : next);
  return (
    <li className="pref-type">
      <span className="pref-type-name">{type.label}</span>
      <ColourInput
        caption="Text"
        label={`${type.label} text colour`}
        value={colours.fg}
        onChange={(fg) => change({ ...colours, fg })}
      />
      <ColourInput
        caption="Background"
        label={`${type.label} background colour`}
        value={colours.bg}
        onChange={(bg) => change({ ...colours, bg })}
      />
      <ResetButton
        type={type}
        isDefault={colours === type.defaults}
        onReset={() => set(type.defaults)}
      />
      <Sample type={type} colours={colours} />
    </li>
  );
}

/** The Messages tab: a minimum level per category, and colours per message type under it. */
export function MessagesPreferences() {
  return (
    <>
      {MESSAGE_CATEGORIES.map(({ id, label }) => (
        <fieldset key={id} className="pref-section">
          <legend>{label}</legend>
          <MinLevel category={id} label={label} />
          <ul className="pref-types">
            {MESSAGE_TYPES.filter((t) => t.category === id).map((t) => (
              <TypeRow key={t.id} type={t} />
            ))}
          </ul>
        </fieldset>
      ))}
    </>
  );
}
