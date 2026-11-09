import { expect, test } from '@playwright/test';
import {
  accountNav,
  createUser,
  expectLoggedInHome,
  PASSWORD,
  registerViaUi,
  submitLogin,
  uniqueUsername,
} from './support';

test.describe('session', () => {
  test('register logs in automatically and greets the player', async ({ page }) => {
    const username = uniqueUsername();

    await registerViaUi(page, username);

    await expectLoggedInHome(page, username);
  });

  test('a reload restores the session', async ({ page }) => {
    const username = uniqueUsername();
    await registerViaUi(page, username);
    await expectLoggedInHome(page, username);

    await page.reload();

    await expectLoggedInHome(page, username);
    await expect(accountNav(page).getByRole('link', { name: 'Log in' })).toHaveCount(0);
  });

  test('log out ends the session, also after a reload', async ({ page }) => {
    const username = uniqueUsername();
    await registerViaUi(page, username);
    await expectLoggedInHome(page, username);

    await accountNav(page).getByRole('button', { name: 'Log out' }).click();

    await expect(page).toHaveURL('/');
    await expect(accountNav(page).getByRole('link', { name: 'Log in' })).toBeVisible();
    await expect(accountNav(page).getByRole('button', { name: 'Log out' })).toHaveCount(0);
    await expect(page.getByText(`Welcome, ${username}.`)).toHaveCount(0);

    await page.reload();
    await expect(accountNav(page).getByRole('link', { name: 'Log in' })).toBeVisible();
    await expect(page.getByText(`Welcome, ${username}.`)).toHaveCount(0);
  });

  test('/play redirects to login and returns to /play afterwards', async ({ page, request }) => {
    const username = uniqueUsername();
    await createUser(request, username);

    await page.goto('/play');

    await expect(page).toHaveURL('/login?next=%2Fplay');
    await expect(page.getByRole('heading', { level: 1, name: 'Log in' })).toBeVisible();

    await submitLogin(page, username, PASSWORD);

    await expect(page).toHaveURL('/play');
    await expect(page.getByRole('heading', { level: 1, name: 'Play' })).toBeVisible();
    await expect(page.getByText(`The dungeon is not ready for you yet, ${username}.`)).toBeVisible();
  });
});
