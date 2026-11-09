import { randomBytes } from 'node:crypto';
import { expect, type APIRequestContext, type Page } from '@playwright/test';

/** A password that satisfies the auth policy (8-128 chars, <= 72 UTF-8 bytes). */
export const PASSWORD = 'correct-horse-battery';

/**
 * A fresh username per test, so tests never share accounts and can run in
 * parallel against one database: "e2e_" + 12 lowercase hex = 16 chars of
 * [A-Za-z0-9_-] (the policy allows 3-32).
 */
export function uniqueUsername(): string {
  return `e2e_${randomBytes(6).toString('hex')}`;
}

/** Creates an account through the API (faster than the UI for test setup). */
export async function createUser(
  request: APIRequestContext,
  username: string,
  password: string = PASSWORD,
): Promise<void> {
  const response = await request.post('/api/auth/register', { data: { username, password } });
  expect(response.status(), await response.text()).toBe(201);
}

/** The register form, filled in and submitted through the UI. */
export async function registerViaUi(page: Page, username: string, password: string = PASSWORD) {
  await page.goto('/register');
  await page.getByLabel('Username', { exact: true }).fill(username);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByLabel('Confirm password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Register' }).click();
}

/** The login form, filled in and submitted through the UI (expects to be on /login). */
export async function submitLogin(page: Page, username: string, password: string) {
  await page.getByLabel('Username', { exact: true }).fill(username);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Log in' }).click();
}

/** The header's account controls ("Account" nav). */
export function accountNav(page: Page) {
  return page.getByRole('navigation', { name: 'Account' });
}

/** Asserts the logged-in state: the home greeting and the header controls. */
export async function expectLoggedInHome(page: Page, username: string) {
  await expect(page).toHaveURL('/');
  await expect(page.getByText(`Welcome, ${username}.`)).toBeVisible();
  await expect(accountNav(page).getByText(username, { exact: true })).toBeVisible();
  await expect(accountNav(page).getByRole('button', { name: 'Log out' })).toBeVisible();
}
