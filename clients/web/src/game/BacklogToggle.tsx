import { useSkipBacklog } from './backlogPref.ts';

/** The area bar's switch for skipping animations that fall behind (off: every action plays). */
export function BacklogToggle() {
  const [skip, setSkip] = useSkipBacklog();
  return (
    <label
      className="backlog-toggle"
      title="When actions arrive faster than they animate, jump the older ones to their end"
    >
      <input type="checkbox" checked={skip} onChange={(e) => setSkip(e.target.checked)} />
      Skip animations when behind
    </label>
  );
}
