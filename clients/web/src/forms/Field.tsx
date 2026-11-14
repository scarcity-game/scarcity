import type { ChangeEvent, ReactNode, Ref } from 'react';

export interface FieldProps {
  id: string;
  label: string;
  type: 'text' | 'password';
  value: string;
  onChange(value: string): void;
  autoComplete: string;
  error?: string;
  hint?: string;
  inputRef?: Ref<HTMLInputElement>;
}

/**
 * A labelled input with optional hint and inline error. The error text is
 * linked with aria-describedby, and the input is aria-invalid while it has one.
 */
export function Field({
  id,
  label,
  type,
  value,
  onChange,
  autoComplete,
  error,
  hint,
  inputRef,
}: FieldProps) {
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ');

  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input
        ref={inputRef}
        id={id}
        name={id}
        type={type}
        value={value}
        onChange={(e: ChangeEvent<HTMLInputElement>) => onChange(e.target.value)}
        autoComplete={autoComplete}
        autoCapitalize={type === 'text' ? 'none' : undefined}
        spellCheck={false}
        required
        aria-invalid={error ? true : false}
        aria-describedby={describedBy || undefined}
      />
      {hint && (
        <p id={hintId} className="field-hint">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="field-error">
          {error}
        </p>
      )}
    </div>
  );
}

/** The form-level error region. Always rendered so screen readers pick up changes. */
export function FormError({ message }: { message: ReactNode }) {
  return (
    <div role="alert" aria-label="Form error" className="form-error">
      {message}
    </div>
  );
}
