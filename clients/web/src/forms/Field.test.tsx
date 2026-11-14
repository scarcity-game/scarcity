import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { FormError } from './Field.tsx';

describe('FormError', () => {
  it('is an alert named "Form error" that shows the message', () => {
    render(<FormError message="Invalid username or password" />);
    expect(screen.getByRole('alert', { name: 'Form error' })).toHaveTextContent(
      'Invalid username or password',
    );
  });

  it('is rendered even without a message', () => {
    render(<FormError message={null} />);
    expect(screen.getByRole('alert', { name: 'Form error' })).toBeEmptyDOMElement();
  });
});
