import { chromium } from 'playwright-core';

const BASE = process.env.E2E_BASE_URL || 'http://localhost:4173';
const chromePath =
  process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PASSWORD = 'StrongPass1!';
const runId = Date.now();
const alice = { name: 'E2E Alice', email: `alice.${runId}@example.com` };
const bob = { name: 'E2E Bob', email: `bob.${runId}@example.com` };

const results = [];
const consoleErrors = [];

function assert(condition, message) {
  if (!condition) throw new Error(message);
}
async function check(name, action) {
  try {
    await action();
    results.push({ name, ok: true });
    console.log(`PASS  ${name}`);
  } catch (error) {
    results.push({ name, ok: false, error: String(error) });
    console.log(`FAIL  ${name} :: ${error}`);
  }
}

const browser = await chromium.launch({ executablePath: chromePath, headless: true });
const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
const page = await context.newPage();
page.on('console', (message) => {
  const url = message.location()?.url || '';
  const expectedAuthProbe = message.text().includes('status of 401')
    && (url.includes('/api/auth/me') || url.includes('/api/auth/login'));
  if (message.type() === 'error' && !url.includes('favicon')
      && !message.text().includes('favicon') && !expectedAuthProbe) {
    consoleErrors.push(`${message.text()} @ ${url}`);
  }
});

async function register(user) {
  await page.goto(`${BASE}/#/register`, { waitUntil: 'networkidle' });
  await page.locator('input[autocomplete="name"]').fill(user.name);
  await page.locator('input[type="email"]').fill(user.email);
  const passwords = page.locator('input[autocomplete="new-password"]');
  await passwords.nth(0).fill(PASSWORD);
  await passwords.nth(1).fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await page.waitForURL('**/#/', { timeout: 15000 });
  await page.getByRole('heading', { name: new RegExp(`, ${user.name}$`) }).waitFor({ timeout: 15000 });
}

async function login(email, password = PASSWORD) {
  await page.goto(`${BASE}/#/login`, { waitUntil: 'networkidle' });
  await page.locator('input[type="email"]').fill(email);
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

async function logout() {
  await page.getByRole('link', { name: 'Profile' }).click();
  await page.waitForURL('**/#/profile');
  await page.getByRole('button', { name: 'Log out' }).click();
  await page.waitForURL('**/#/login', { timeout: 15000 });
}

try {
  await check('unauthenticated visit opens guest news browsing', async () => {
    await page.goto(`${BASE}/#/`, { waitUntil: 'networkidle' });
    await page.getByRole('heading', { name: 'Latest AI updates' }).waitFor();
    await page.getByText(/Browsing as a guest/).waitFor();
    assert((await page.locator('.bottom-nav .nav-item').count()) === 2, 'guest navigation should show public sections only');
    await page.goto(`${BASE}/#/bookmarks`, { waitUntil: 'networkidle' });
    await page.waitForURL('**/#/login');
  });

  await check('unknown credentials show a generic error', async () => {
    await login(`missing.${runId}@example.com`, 'WrongPass1!');
    await page.getByRole('alert').waitFor({ timeout: 15000 });
    const text = await page.getByRole('alert').innerText();
    assert(text.includes('Invalid email or password'), `unexpected error: ${text}`);
  });

  await check('registration creates and signs in the user', async () => {
    await register(alice);
    const heading = await page.locator('h1').innerText();
    assert(heading.includes(alice.name), `greeting missing name: ${heading}`);
  });

  await check('session survives a full page reload', async () => {
    await page.reload({ waitUntil: 'networkidle' });
    await page.getByRole('heading', { name: new RegExp(`, ${alice.name}$`) }).waitFor({ timeout: 15000 });
    await page.waitForURL('**/#/');
  });

  let savedTitle = '';
  await check('authenticated bookmark is stored for the owner', async () => {
    await page.getByRole('link', { name: 'Discover' }).click();
    await page.locator('.article-card').first().waitFor({ timeout: 20000 });
    savedTitle = (await page.locator('.article-card h3').first().innerText()).trim();
    await page.locator('.article-card .bookmark-toggle').first().click();
    await page.waitForResponse((response) =>
      response.url().endsWith('/api/bookmarks') && response.request().method() === 'POST'
    );
    await page.getByRole('link', { name: 'Saved' }).click();
    await page.getByText('Saved items', { exact: true }).waitFor();
    const titles = await page.locator('.saved-card h3').allTextContents();
    assert(titles.some((title) => title.trim() === savedTitle), `bookmark not listed: ${titles.join(' | ')}`);
  });

  await check('logout invalidates the browser session', async () => {
    await logout();
    await page.goto(`${BASE}/#/bookmarks`, { waitUntil: 'networkidle' });
    await page.waitForURL('**/#/login');
  });

  await check('login restores the saved bookmark after logout', async () => {
    await login(alice.email);
    await page.waitForURL('**/#/', { timeout: 15000 });
    await page.getByRole('link', { name: 'Saved' }).click();
    await page.waitForURL('**/#/bookmarks');
    await page.getByText('Saved items', { exact: true }).waitFor();
    await page.locator('.saved-card h3').first().waitFor({ timeout: 15000 });
    const titles = await page.locator('.saved-card h3').allTextContents();
    assert(titles.some((title) => title.trim() === savedTitle), 'bookmark did not persist across login');
  });

  await check('second user sees an empty private saved list', async () => {
    await logout();
    await register(bob);
    await page.getByRole('link', { name: 'Saved' }).click();
    await page.waitForURL('**/#/bookmarks');
    await page.getByText('Nothing saved yet', { exact: true }).waitFor({ timeout: 15000 });
    const count = await page.locator('.saved-card').count();
    assert(count === 0, `second user can see ${count} saved cards`);
  });

  await check('second user cannot see the first user in profile data', async () => {
    await page.getByRole('link', { name: 'Profile' }).click();
    await page.waitForURL('**/#/profile');
    await page.getByRole('heading', { name: 'Profile', exact: true }).waitFor();
    const text = await page.locator('.profile-card').innerText();
    assert(text.includes(bob.name), `profile is not Bob: ${text}`);
    assert(!text.includes(alice.name), 'profile leaked the first user');
  });

  await check('no browser console errors', async () => {
    assert(consoleErrors.length === 0, consoleErrors.join(' | '));
  });
} finally {
  await browser.close();
}

const failed = results.filter((result) => !result.ok);
console.log(`\n${results.length - failed.length}/${results.length} authentication checks passed`);
if (failed.length) {
  for (const result of failed) console.log(`  FAILED: ${result.name} :: ${result.error}`);
  process.exitCode = 1;
}
