import {expect, Page, test} from '@playwright/test';

const base = process.env.TEST_WEB_URL || 'http://localhost:3006';

async function login(page: Page, user: string) {
    await page.goto(base);
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    await expect(page).toHaveURL(/localhost:8186/);
    await page.getByLabel('Username or email').fill(user);
    await page.getByLabel('Password', {exact: true}).fill(user + '-dev');
    await page.getByRole('button', {name: 'Sign In', exact: true}).click();
    await expect(page).toHaveURL(base + '/', {timeout: 15000});
}

test('OIDC advertiser session creates daily-paced campaign; view selection cannot elevate roles', async ({page}) => {
    await login(page, 'advertiser');
    const name = 'OIDC Browser ' + Date.now();
    await page.getByLabel('Campaign name', {exact: true}).fill(name);
    await page.getByLabel('Creative headline', {exact: true}).fill('Electric charging at home');
    await page.getByLabel('HTTPS destination (no query)', {exact: true}).fill('https://example.org');
    await page.getByLabel('Lifetime budget in minor units', {exact: true}).fill('1000');
    await page.getByLabel('Daily budget in minor units', {exact: true}).fill('100');
    await page.getByLabel('Price per impression in minor units', {exact: true}).fill('1');
    await page.locator('select[name=pacing]').selectOption('EVEN');
    await page.getByRole('button', {name: 'Create draft', exact: true}).click();
    await expect(page.getByRole('heading', {name})).toBeVisible();
    const session = await page.request.get(base + '/api/auth/session');
    expect(await session.text()).not.toContain('accessToken');
    await page.getByRole('button', {name: 'admin', exact: true}).click();
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    await expect(page.getByRole('status', {name: 'Platform status'})).toContainText('403');
    const spoof = await page.request.get(base + '/api/admin/campaigns', {headers: {'x-demo-user': 'admin'}});
    expect(spoof.status()).toBe(403);
});
test('OIDC publisher registers domain; serving remains blocked without DNS proof', async ({page}) => {
    await login(page, 'publisher');
    await page.getByRole('button', {name: 'publisher', exact: true}).click();
    const domain = 'publisher-' + Date.now() + '.com';
    await page.getByLabel('Public website domain', {exact: true}).fill(domain);
    await page.getByRole('button', {name: 'Register domain / rotate challenge'}).click();
    await expect(page.getByRole('status', {name: 'Platform status'})).toContainText('_contextads-verification');
    await expect(page.getByRole('article').filter({has: page.getByRole('heading', {name: domain})})).toContainText('Not verified');
});
test('BFF rejects missing sessions, role spoofing and cross-origin writes', async ({request}) => {
    expect((await request.get(base + '/api/campaigns', {headers: {'x-demo-user': 'advertiser'}})).status()).toBe(401);
    expect((await request.post(base + '/api/campaigns', {
        headers: {origin: 'https://evil.com'},
        data: {}
    })).status()).toBe(403);
});

test('advertiser billing uses the authenticated ledger API', async ({page}) => {
    await login(page, 'advertiser');
    await page.getByRole('button', {name: 'Refresh billing', exact: true}).click();
    await expect(page.getByRole('status', {name: 'Billing status'})).toContainText('Loaded authoritative');
    await expect(page.getByRole('heading', {name: 'Balances and reconciliation'})).toBeVisible();
    await expect(page.getByText('"net_receivable"', {exact: false})).toBeVisible();
});
test('administrator can inspect fraud and durable event delivery', async ({page}) => {
    await login(page, 'admin');
    await page.getByRole('button', {name: 'admin', exact: true}).click();
    await page.getByRole('button', {name: 'Refresh billing', exact: true}).click();
    await expect(page.getByRole('status', {name: 'Billing status'})).toContainText('Loaded authoritative');
    await page.getByRole('button', {name: 'Event delivery status', exact: true}).click();
    await expect(page.getByRole('heading', {name: 'Durable event delivery'})).toBeVisible();
});
