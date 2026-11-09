import { expect, test, type Request } from '@playwright/test';
import { createUser, PASSWORD, registerViaUi, submitLogin, uniqueUsername } from './support';

test.describe('errors', () => {
  test('a username taken in another case shows the 409 message', async ({ page, request }) => {
    const username = uniqueUsername();
    await createUser(request, username);
    const variant = username.toUpperCase();
    expect(variant).not.toBe(username);

    const registerResponse = page.waitForResponse(
      (r) => r.url().endsWith('/api/auth/register') && r.request().method() === 'POST',
    );
    await registerViaUi(page, variant);

    expect((await registerResponse).status()).toBe(409);
    await expect(page.getByRole('alert')).toHaveText('Username is already taken');
    const usernameField = page.getByLabel('Username', { exact: true });
    await expect(usernameField).toHaveAttribute('aria-invalid', 'true');
    await expect(usernameField).toHaveAccessibleDescription(/Username is already taken/);
    await expect(usernameField).toBeFocused();
    await expect(page).toHaveURL('/register');
  });

  test('a wrong password shows the 401 message', async ({ page, request }) => {
    const username = uniqueUsername();
    await createUser(request, username);

    await page.goto('/login');
    const loginResponse = page.waitForResponse(
      (r) => r.url().endsWith('/api/auth/login') && r.request().method() === 'POST',
    );
    await submitLogin(page, username, `${PASSWORD}-wrong`);

    expect((await loginResponse).status()).toBe(401);
    // The server's problem detail wins over the client's fallback text
    // ("Invalid username or password."), so allow either.
    await expect(page.getByRole('alert')).toHaveText(/^Invalid username or password\.?$/);
    await expect(page).toHaveURL('/login');
    await expect(page.getByRole('button', { name: 'Log out' })).toHaveCount(0);
  });

  test('client-side validation blocks a short password without calling the API', async ({ page }) => {
    const authPosts: Request[] = [];
    page.on('request', (r) => {
      if (r.method() === 'POST' && new URL(r.url()).pathname.startsWith('/api/auth/')) {
        authPosts.push(r);
      }
    });

    await registerViaUi(page, uniqueUsername(), 'short');

    const passwordField = page.getByLabel('Password', { exact: true });
    const message = 'Password must be at least 8 characters long (it is 5).';
    await expect(page.getByText(message)).toBeVisible();
    await expect(passwordField).toHaveAttribute('aria-invalid', 'true');
    await expect(passwordField).toHaveAccessibleDescription(new RegExp(message.replace(/[.()]/g, '\\$&')));
    await expect(passwordField).toBeFocused();
    await expect(page).toHaveURL('/register');

    // Validation runs synchronously on submit, before any fetch; give a stray
    // request a moment to show up anyway before asserting there was none.
    await page.waitForTimeout(250);
    expect(authPosts.map((r) => r.url())).toEqual([]);
  });
});
