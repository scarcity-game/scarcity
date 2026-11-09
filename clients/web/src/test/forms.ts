import { fireEvent, screen } from '@testing-library/react';

export function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

export function fillLogin(username: string, password: string) {
  type('Username', username);
  type('Password', password);
}

export function fillRegister(username: string, password: string, confirm = password) {
  type('Username', username);
  type('Password', password);
  type('Confirm password', confirm);
}

export function submit(name: string | RegExp) {
  fireEvent.click(screen.getByRole('button', { name }));
}
