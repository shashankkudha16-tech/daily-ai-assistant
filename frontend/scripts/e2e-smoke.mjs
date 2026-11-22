import { chromium } from 'playwright-core';

const BASE = process.env.E2E_BASE_URL || 'http://localhost:4173';
const chromePath =
  process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';

const results = [];
const consoleErrors = [];
const pageErrors = [];

function pass(name) {
  results.push({ name, ok: true });
  console.log(`PASS  ${name}`);
}

function fail(name, error) {
  results.push({ name, ok: false, error: String(error) });
  console.log(`FAIL  ${name} :: ${error}`);
}

async function check(name, action) {
  try {
    await action();
    pass(name);
  } catch (error) {
    fail(name, error);
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function textOf(page, selector) {
  return (await page.locator(selector).first().textContent())?.trim() || '';
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
page.on('pageerror', (error) => pageErrors.push(String(error)));

try {
  await page.goto(`${BASE}/#/`, { waitUntil: 'networkidle' });

  await check('authentication bootstrap for regression run', async () => {
    const email = process.env.E2E_EMAIL || `regression.${Date.now()}@example.com`;
    const password = process.env.E2E_PASSWORD || 'StrongPass1!';
    if (process.env.E2E_EMAIL) {
      if (!page.url().includes('#/login')) {
        await page.getByRole('link', { name: 'Log in' }).click();
      }
      await page.locator('input[type="email"]').fill(email);
      await page.locator('input[autocomplete="current-password"]').fill(password);
      await page.getByRole('button', { name: 'Sign in' }).click();
    } else {
      if (!page.url().includes('#/register')) await page.getByRole('link', { name: 'Sign up' }).click();
      await page.locator('input[autocomplete="name"]').fill('E2E Regression');
      await page.locator('input[type="email"]').fill(email);
      const passwords = page.locator('input[autocomplete="new-password"]');
      await passwords.nth(0).fill(password);
      await passwords.nth(1).fill(password);
      await page.getByRole('button', { name: 'Create account' }).click();
    }
    await page.waitForURL('**/#/', { timeout: 15000 });
    await page.locator('.bottom-nav .nav-item').first().waitFor();
  });

  await check('bottom navigation has five tabs', async () => {
    const count = await page.locator('.bottom-nav .nav-item').count();
    assert(count === 5, `expected 5 tabs, found ${count}`);
  });

  await check('dashboard renders live greeting and sections', async () => {
    await page.getByText('Top 5 AI updates', { exact: true }).waitFor({ timeout: 15000 });
    const heading = await textOf(page, 'h1');
    assert(/Good (morning|afternoon|evening), /.test(heading), `unexpected heading: ${heading}`);
    await page.getByText('Trending AI tools', { exact: true }).waitFor();
    const cards = await page.locator('.article-card').count();
    assert(cards > 0, 'no article cards rendered');
  });

  let savedTitle = '';
  await check('article can be bookmarked and appears in Saved', async () => {
    savedTitle = await textOf(page, '.article-card h3');
    assert(savedTitle.length > 0, 'no article title found');
    await page.locator('.article-card .bookmark-toggle').first().click();
    await page.waitForResponse((response) =>
      response.url().endsWith('/api/bookmarks') && response.request().method() === 'POST'
    );
    await page.getByRole('link', { name: 'Saved' }).click();
    await page.getByText('Saved items', { exact: true }).waitFor();
    const titles = await page.locator('.saved-card h3').allTextContents();
    assert(titles.includes(savedTitle), `saved title missing: ${titles.join(' | ')}`);
  });

  await check('bookmark can be removed from Saved', async () => {
    await page.locator('.saved-card .bookmark-toggle').first().click();
    await page.waitForResponse((response) =>
      response.url().includes('/api/bookmarks') && response.request().method() === 'DELETE'
    );
    await page.waitForTimeout(400);
    const remaining = await page.locator('.saved-card').count();
    assert(remaining === 0, `expected empty saved list, found ${remaining}`);
    await page.getByText('Nothing saved yet', { exact: true }).waitFor();
  });

  await check('theme toggle switches and persists', async () => {
    const before = await page.locator('html').getAttribute('data-theme');
    await page.locator('.top-bar .icon-button').click();
    const after = await page.locator('html').getAttribute('data-theme');
    assert(before !== after, 'theme did not change');
    await page.reload({ waitUntil: 'networkidle' });
    const persisted = await page.locator('html').getAttribute('data-theme');
    assert(persisted === after, `theme not persisted: ${persisted} vs ${after}`);
    await page.locator('.top-bar .icon-button').click();
  });

  await check('discover news search works', async () => {
    await page.getByRole('link', { name: 'Discover' }).click();
    await page.getByText('All collected articles', { exact: true }).waitFor();
    await page.locator('.search-bar input').fill('agent');
    await Promise.all([
      page.waitForResponse((response) => response.url().includes('/api/news/search')),
      page.getByRole('button', { name: 'Search', exact: true }).click(),
    ]);
    await page.getByText(/Results for/).waitFor();
    await page.locator('.article-card').first().waitFor({ timeout: 15000 });
    const cards = await page.locator('.article-card').count();
    assert(cards > 0, 'search returned no article cards');
  });

  await check('discover tools tab expands tool details', async () => {
    await page.getByRole('tab', { name: 'AI tools' }).click();
    await page.locator('.tool-card').first().waitFor();
    const count = await page.locator('.tool-card').count();
    assert(count > 0, 'no tools rendered');
    await page.locator('.tool-card .text-button').first().click();
    await page.getByText('Main features', { exact: true }).first().waitFor();
  });

  await check('learning topic progress updates and restores', async () => {
    await page.getByRole('link', { name: 'Learn' }).click();
    await page.getByText('Roadmap progress', { exact: true }).waitFor();
    await page.locator('.topic-row').first().click();
    await page.getByText('Explanation', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'In progress', exact: true }).click();
    await page.waitForResponse((response) =>
      response.url().endsWith('/api/learning/progress') && response.request().method() === 'POST'
    );
    await page.getByText('Current status: In progress', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Not started', exact: true }).click();
    await page.waitForResponse((response) =>
      response.url().endsWith('/api/learning/progress') && response.request().method() === 'POST'
    );
    await page.getByText('Current status: Not started', { exact: true }).waitFor();
  });

  await check('daily plan shows seven days, 28 tasks and toggles', async () => {
    await page.getByRole('link', { name: 'Learn' }).click();
    await page.getByText('Daily learning plan', { exact: true }).waitFor();
    await page.locator('.plan-day').first().waitFor({ timeout: 15000 });
    const days = await page.locator('.plan-day').count();
    const tasks = await page.locator('.task-toggle').count();
    assert(days === 7, `expected 7 days, found ${days}`);
    assert(tasks === 28, `expected 28 tasks, found ${tasks}`);

    const task = page.locator('.task-toggle').first();
    await task.scrollIntoViewIfNeeded();
    const wasPressed = (await task.getAttribute('aria-pressed')) === 'true';
    await Promise.all([
      page.waitForResponse((response) =>
        response.url().includes('/api/learning/daily-plan/') && response.request().method() === 'POST'),
      task.click(),
    ]);
    await page.waitForResponse((response) =>
      response.url().includes('/api/learning/daily-plan') && response.request().method() === 'GET');
    await page.waitForTimeout(200);
    const after = (await page.locator('.task-toggle').first().getAttribute('aria-pressed')) === 'true';
    assert(after !== wasPressed, 'daily task state did not toggle');

    await Promise.all([
      page.waitForResponse((response) =>
        response.url().includes('/api/learning/daily-plan/') && response.request().method() === 'POST'),
      page.locator('.task-toggle').first().click(),
    ]);
    await page.waitForResponse((response) =>
      response.url().includes('/api/learning/daily-plan') && response.request().method() === 'GET');
    await page.waitForTimeout(200);
    const restored = (await page.locator('.task-toggle').first().getAttribute('aria-pressed')) === 'true';
    assert(restored === wasPressed, 'daily task state was not restored');
  });

  await check('article detail shows beginner explanation', async () => {
    await page.getByRole('link', { name: 'Home' }).click();
    await page.locator('.article-card').first().waitFor();
    await page.locator('.article-card .read-more').first().click();
    await page.getByText('Beginner-friendly explanation', { exact: true }).waitFor();
    await page.getByText('Quick summary', { exact: true }).waitFor();
    await page.getByText('Related technologies', { exact: true }).waitFor();
  });

  await check('claim provenance labels render for new AI claims', async () => {
    const mockedArticle = {
      id: 999999,
      title: 'Grounded claim rendering fixture',
      source: 'Test source',
      publishedAt: '2026-11-19T00:00:00Z',
      categoryName: 'AI Research',
      description: 'Abstract: We study this in 56 interactive worlds.',
      url: 'https://example.com/fixture',
      bookmarked: false,
      explanation: {
        generatedBy: 'AI',
        whatHappened: 'The fixture checks claim provenance.',
        claims: [
          { section: 'howItWorks', kind: 'SOURCE_FACT', statement: 'The study uses 56 worlds.', evidence: 'We study this in 56 interactive worlds.' },
          { section: 'importance', kind: 'INTERPRETATION', statement: 'The result may matter.', evidence: 'We study this in 56 interactive worlds.' },
        ],
      },
    };
    await page.route('**/api/news/999999', (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(mockedArticle),
    }));
    try {
      await page.goto(`${BASE}/#/news/999999`, { waitUntil: 'networkidle' });
      await page.getByText('Source-grounded claims', { exact: true }).waitFor();
      const kinds = await page.locator('.claim-kind').allTextContents();
      assert(kinds.includes('Source fact') && kinds.includes('App interpretation'),
        `missing provenance labels: ${kinds.join(', ')}`);
      await page.getByText('Relates to: How does it work?', { exact: true }).waitFor();
    } finally {
      await page.unroute('**/api/news/999999');
    }
  });

  await check('profile name can be saved and restored', async () => {
    await page.getByRole('link', { name: 'Profile' }).click();
    await page.getByText('Reading history', { exact: true }).waitFor();
    const input = page.locator('.inline-form input');
    await input.fill('E2E Tester');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByText('Profile updated.', { exact: true }).waitFor();
    await page.getByText('E2E Tester', { exact: true }).waitFor();
    await input.fill('Fresher');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByText('Fresher', { exact: true }).waitFor();
    const name = await textOf(page, '.profile-card h2');
    assert(name === 'Fresher', `name was not restored: ${name}`);
  });

  await check('unknown route shows not-found state', async () => {
    await page.goto(`${BASE}/#/missing-route`, { waitUntil: 'networkidle' });
    await page.getByText('Page not found', { exact: true }).waitFor();
  });

  await check('no uncaught page errors', async () => {
    assert(pageErrors.length === 0, pageErrors.join(' | '));
  });

  await check('no browser console errors', async () => {
    assert(consoleErrors.length === 0, consoleErrors.join(' | '));
  });
} catch (error) {
  fail('test harness', error);
} finally {
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\nRESULT: ${results.length - failed.length} passed, ${failed.length} failed`);
if (failed.length) process.exit(1);
