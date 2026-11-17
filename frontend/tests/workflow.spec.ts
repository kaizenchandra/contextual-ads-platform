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

test('publisher revenue and settlements use owned backend records',async({page})=>{
 await login(page,'publisher');await page.getByRole('button',{name:'publisher',exact:true}).click();
 await page.getByRole('button',{name:'Refresh treasury',exact:true}).click();
 await expect(page.getByRole('status',{name:'Treasury status'})).toContainText('Loaded invoice');
 await expect(page.getByRole('heading',{name:'Publisher revenue and settlement',exact:true})).toBeVisible();
 await expect(page.getByText('"collectedEligible"',{exact:false})).toBeVisible();
 expect((await page.request.get(base+'/api/billing/invoices')).status()).toBe(403);
});
test('advertiser can list its invoice and reconciled cash records',async({page})=>{
 await login(page,'advertiser');await page.getByRole('button',{name:'Refresh treasury',exact:true}).click();
 await expect(page.getByRole('status',{name:'Treasury status'})).toContainText('Loaded invoice');
 expect((await page.request.get(base+'/api/billing/invoices')).status()).toBe(200);
 expect((await page.request.get(base+'/api/billing/payments')).status()).toBe(200);
});
test('administrator issues an idempotent closing batch through the UI',async({browser,page})=>{
 const advertiserContext=await browser.newContext();const advertiserPage=await advertiserContext.newPage();
 await login(advertiserPage,'advertiser');const subject=(await (await advertiserPage.request.get(base+'/api/identity')).json()).subject;await advertiserContext.close();
 await login(page,'admin');await page.getByRole('button',{name:'admin',exact:true}).click();
 await page.getByLabel('Treasury evidence / reason',{exact:true}).fill('Synthetic browser test closes only existing validated ledger entries');
 await page.getByLabel('Advertiser account subject',{exact:true}).fill(subject);
 const key='browser-close-'+Date.now();await page.getByLabel('Invoice request key',{exact:true}).fill(key);
 await page.getByRole('button',{name:'Issue documents',exact:true}).click();
 await expect(page.getByRole('status',{name:'Treasury status'})).toContainText('batchId');
 const first=JSON.parse((await page.getByRole('status',{name:'Treasury status'}).textContent())!);
 const retry=await page.request.post(base+'/api/admin/invoices/issue',{headers:{origin:base},data:{advertiser:subject,requestKey:key,reason:'Retry synthetic browser financial closing request'}});
 expect(retry.status()).toBe(200);expect((await retry.json()).batchId).toBe(first.batchId);
});

test('advertiser uploads normalized imagery and administrator reviews its immutable version',async({page,browser})=>{
 await login(page,'advertiser');
 const campaign=await page.request.post(base+'/api/campaigns',{headers:{origin:base},data:{name:'Upload browser '+Date.now(),headline:'Initial Java',category:'technology.java',destination:'https://example.org',budget:100,dailyBudget:100,bid:5}});expect(campaign.status()).toBe(201);const id=(await campaign.json()).id;
 const oversized=await page.request.post(base+'/api/creatives/assets',{headers:{origin:base},multipart:{file:{name:'too-large.png',mimeType:'image/png',buffer:Buffer.alloc(2*1024*1024+1)}}});expect(oversized.status()).toBe(413);
 const bounded=await page.request.post(base+'/api/creatives/assets',{headers:{origin:base,'Content-Type':'application/octet-stream'},data:Buffer.alloc(3*1024*1024+1)});expect(bounded.status()).toBe(413);
 await page.getByLabel('Creative image (PNG/JPEG, max 2 MiB)',{exact:true}).setInputFiles({name:'disguised.png',mimeType:'image/png',buffer:Buffer.from('<svg><script>parent.attack=true</script></svg>')});
 await page.getByRole('button',{name:'Upload image',exact:true}).click();await expect(page.getByRole('status',{name:'Creative status'})).toContainText('Only PNG and JPEG');
 await page.getByLabel('Creative image (PNG/JPEG, max 2 MiB)',{exact:true}).setInputFiles('tests/fixtures/ad.png');
 await page.getByRole('button',{name:'Upload image',exact:true}).click();await expect(page.getByRole('status',{name:'Creative status'})).toContainText('Normalized asset ID:');
 const asset=(await page.getByRole('status',{name:'Creative status'}).textContent())!.split('Normalized asset ID: ')[1];
 await page.getByLabel('Creative campaign ID',{exact:true}).fill(id);const headline='Reviewed uploaded image '+Date.now();
 await page.getByLabel('Version headline',{exact:true}).fill(headline);await page.getByLabel('Version description',{exact:true}).fill('Synthetic upload and review browser test');await page.getByLabel('Version HTTPS destination',{exact:true}).fill('https://example.org/java');await page.getByLabel('Owned image asset',{exact:true}).selectOption(asset);
 await page.getByRole('button',{name:'Create creative version',exact:true}).click();await expect(page.getByRole('status',{name:'Creative status'})).toContainText('Version 2');
 const adminContext=await browser.newContext();try{const admin=await adminContext.newPage();await login(admin,'admin');await admin.getByRole('button',{name:'admin',exact:true}).click();await admin.getByRole('button',{name:'Refresh creatives',exact:true}).click();await admin.getByLabel('Creative review reason',{exact:true}).fill('Synthetic image and destination inspected');const row=admin.getByRole('article').filter({has:admin.getByRole('heading',{name:'Version 2 · '+headline,exact:true})});await expect(row.getByRole('img')).toBeVisible();await row.getByRole('button',{name:'approve version',exact:true}).click();await expect(row).toContainText('APPROVED');}finally{await adminContext.close();}
 await page.getByRole('button',{name:'Refresh creatives',exact:true}).click();await expect(page.getByRole('article').filter({has:page.getByRole('heading',{name:'Version 2 · '+headline,exact:true})})).toContainText('APPROVED');
});
