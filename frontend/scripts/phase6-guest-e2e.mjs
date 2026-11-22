import { chromium } from 'playwright-core';

const base = process.env.E2E_BASE_URL || 'http://localhost:4173';
const api = process.env.E2E_API_URL || 'http://localhost:18080';
const chromePath = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const browser = await chromium.launch({ executablePath: chromePath, headless: true });
const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
const page = await context.newPage();
const results = [];
let accountCredentials;

async function check(name, action) {
  try {
    await action();
    results.push({ name, ok: true });
    console.log(`PASS  ${name}`);
  } catch (error) {
    results.push({ name, ok: false });
    console.log(`FAIL  ${name} :: ${error}`);
  }
}

try {
  await check('guest browsing and HttpOnly cookie', async () => {
    await page.goto(`${base}/#/`, { waitUntil: 'networkidle' });
    await page.getByRole('heading', { name: 'Latest AI updates' }).waitFor();
    const cookie = (await context.cookies()).find(({ name }) => name === 'DAILYAI_GUEST');
    if (!cookie?.httpOnly || !cookie.expires) throw new Error('persistent HttpOnly guest cookie missing');
  });

  await check('navigation fits mobile and desktop viewports', async () => {
    for (const width of [390, 1280]) {
      await page.setViewportSize({ width, height: 844 });
      const documentWidth = await page.evaluate(() => document.documentElement.scrollWidth);
      if (documentWidth > width) throw new Error(`horizontal overflow at ${width}px: ${documentWidth}px`);
    }
    await page.setViewportSize({ width: 390, height: 844 });
  });

  await check('private APIs reject guests', async () => {
    const status = await page.evaluate((url) => fetch(`${url}/api/bookmarks`, { credentials: 'include' }).then((r) => r.status), api);
    if (status !== 401) throw new Error(`bookmarks returned ${status}`);
  });

  let firstArticleId;
  await check('guest opens article and records a qualified view', async () => {
    const card = page.locator('.article-card').first();
    firstArticleId = Number(new URL(await card.locator('.card-link').getAttribute('href'), base).hash.match(/news\/(\d+)/)?.[1]);
    const qualified = page.waitForResponse((response) => response.url().endsWith('/api/guest/interactions')
      && response.request().postData()?.includes('QUALIFIED_VIEW'), { timeout: 35000 });
    await card.locator('.card-link').click();
    await page.getByText('Quick summary', { exact: true }).waitFor();
    await page.locator('.detail-meta').getByText('Author unavailable', { exact: true }).waitFor();
    await qualified;
  });

  await check('recommendations use activity and exclude the read article', async () => {
    await page.goto(`${base}/#/`, { waitUntil: 'networkidle' });
    await page.getByText(/Based on your reading activity/).waitFor();
    const token = await page.evaluate((url) => fetch(`${url}/api/auth/csrf`, { credentials: 'include' }).then((r) => r.json()), api);
    const result = await page.evaluate(({ csrf, url }) => fetch(`${url}/api/guest/recommendations?limit=12`, {
      method: 'POST', credentials: 'include', headers: { 'X-XSRF-TOKEN': csrf },
    }).then((r) => r.json()), { csrf: token.token, url: api });
    const ids = result.articles.map(({ id }) => id);
    if (!result.personalized || !ids.length || ids.includes(firstArticleId) || new Set(ids).size !== ids.length) {
      throw new Error('recommendations are not personalized, unique, or exclude read article');
    }
  });

  await check('reset clears guest personalization', async () => {
    await page.getByRole('button', { name: 'Reset my recommendations' }).click();
    await page.getByText(/recommendations will improve as you explore/).waitFor();
  });

  await check('registration migrates interests and preserves account access', async () => {
    await page.locator('.article-card .card-link').first().click();
    const qualified = page.waitForResponse((response) => response.url().endsWith('/api/guest/interactions')
      && response.request().postData()?.includes('QUALIFIED_VIEW'), { timeout: 35000 });
    await qualified;
    const oldGuest = (await context.cookies()).find(({ name }) => name === 'DAILYAI_GUEST')?.value;
    await page.goto(`${base}/#/register`, { waitUntil: 'networkidle' });
    const stamp = Date.now();
    accountCredentials = { email: `phase6.${stamp}@example.test`, password: 'StrongPass1!' };
    await page.locator('input[autocomplete="name"]').fill('Phase 6 Guest');
    await page.locator('input[type="email"]').fill(accountCredentials.email);
    await page.locator('input[autocomplete="new-password"]').nth(0).fill(accountCredentials.password);
    await page.locator('input[autocomplete="new-password"]').nth(1).fill(accountCredentials.password);
    const registered = page.waitForResponse((response) => response.url().endsWith('/api/auth/register'));
    await page.getByRole('button', { name: 'Create account' }).click();
    const response = await registered;
    if (response.headers()['x-guest-migration-status'] !== 'MIGRATED') throw new Error('guest interests were not migrated');
    await page.getByText('Your reading interests were added to your account.').waitFor();
    const rotated = (await context.cookies()).find(({ name }) => name === 'DAILYAI_GUEST')?.value;
    if (!rotated || rotated === oldGuest) throw new Error('guest cookie was not rotated');
    const status = await page.evaluate((url) => fetch(`${url}/api/bookmarks`, { credentials: 'include' }).then((r) => r.status), api);
    if (status !== 200) throw new Error(`authenticated bookmarks returned ${status}`);
  });

  await check('existing account login migrates guest interests', async () => {
    if (!accountCredentials) throw new Error('test account credentials are unavailable');
    await page.goto(`${base}/#/profile`, { waitUntil: 'networkidle' });
    await page.getByRole('button', { name: 'Log out' }).click();
    await page.waitForURL('**/#/login');
    await page.goto(`${base}/#/`, { waitUntil: 'networkidle' });
    await page.locator('.article-card .card-link').first().click();
    const qualified = page.waitForResponse((response) => response.url().endsWith('/api/guest/interactions')
      && response.request().postData()?.includes('QUALIFIED_VIEW'), { timeout: 35000 });
    await qualified;
    const oldGuest = (await context.cookies()).find(({ name }) => name === 'DAILYAI_GUEST')?.value;
    await page.goto(`${base}/#/login`, { waitUntil: 'networkidle' });
    await page.locator('input[type="email"]').fill(accountCredentials.email);
    await page.locator('input[autocomplete="current-password"]').fill(accountCredentials.password);
    const loggedIn = page.waitForResponse((response) => response.url().endsWith('/api/auth/login'));
    await page.getByRole('button', { name: 'Sign in' }).click();
    const response = await loggedIn;
    if (response.headers()['x-guest-migration-status'] !== 'MIGRATED') throw new Error('login did not migrate guest interests');
    await page.getByText('Your reading interests were added to your account.').waitFor();
    const rotated = (await context.cookies()).find(({ name }) => name === 'DAILYAI_GUEST')?.value;
    if (!rotated || rotated === oldGuest) throw new Error('guest identity was not rotated after login');
  });
} finally {
  await browser.close();
}

const failed = results.filter(({ ok }) => !ok);
console.log(`\nRESULT: ${results.length - failed.length} passed, ${failed.length} failed`);
if (failed.length) process.exitCode = 1;
