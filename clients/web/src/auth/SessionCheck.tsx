/** Shown while a restored session is being validated, instead of the logged-out UI. */
export function SessionCheck() {
  return (
    <p role="status" className="session-check">
      Checking session…
    </p>
  );
}
