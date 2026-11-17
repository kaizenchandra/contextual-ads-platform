import {test,expect,Page} from '@playwright/test';
import {readFileSync} from 'node:fs';
const f=JSON.parse(readFileSync(process.env.ISOLATION_FIXTURE!,'utf8'));
const base=process.env.TEST_WEB_URL||'http://localhost:3006';
async function decision(request:any){const r=await request.post(f.api+'/api/v1/ads/decide',{headers:{Authorization:'Bearer '+f.publisherToken},data:{pageId:f.pageId,placementId:f.placementId,synthetic:true}});expect(r.status()).toBe(200);const d=await r.json();expect(d.filled).toBe(true);return d;}
async function mount(page:Page,renderUrl:string,offscreen=false){await page.goto(base);await page.evaluate(({renderUrl,api,offscreen})=>{document.body.replaceChildren();(window as any).attack=false;const slot=document.createElement('div');slot.id='ad-slot';slot.dataset.contextualAd='';slot.dataset.renderUrl=renderUrl;if(offscreen)slot.style.marginTop='3000px';document.body.append(slot);const script=document.createElement('script');script.src=api+'/sdk/v1.js';document.body.append(script);},{renderUrl,api:f.api,offscreen});await expect(page.locator('iframe')).toHaveCount(1);}
async function campaign(request:any){const r=await request.get(f.api+'/api/v1/analytics',{headers:{Authorization:'Bearer '+f.advertiserToken}});expect(r.status()).toBe(200);return (await r.json()).find((r:any)=>r.id===f.campaignId);}
test('real SDK renders approved image in an opaque sandbox and records one signed impression',async({page,request})=>{
 const d=await decision(request);const before=await campaign(request);await mount(page,d.renderUrl);
 const frame=page.frameLocator('iframe');await expect(frame.getByRole('heading',{name:f.headline,exact:true})).toBeVisible();
 await expect(frame.getByText('Sponsored · Advertisement',{exact:true})).toBeVisible();
 await expect(frame.getByRole('img')).toBeVisible();expect(await frame.getByRole('img').evaluate((image:HTMLImageElement)=>image.naturalWidth)).toBeGreaterThan(0);
 const actual=page.frames().find(x=>x.url().startsWith(d.renderUrl))!;
 expect(await actual.evaluate(()=>{try{void parent.document.body;return 'accessible';}catch(e){return (e as Error).name;}})).toBe('SecurityError');
 expect(await actual.evaluate(()=>{try{localStorage.setItem('tracking','forbidden');return 'accessible';}catch(e){return (e as Error).name;}})).toBe('SecurityError');
 expect(await page.evaluate(()=>(window as any).attack)).toBe(false);
 await expect.poll(async()=>(await campaign(request)).impressions).toBe(before.impressions+1);
 const charged=await request.get(f.api+'/api/v1/billing/entries',{headers:{Authorization:'Bearer '+f.advertiserToken}});expect(await charged.json()).toEqual([]);
});
test('offscreen placement does not signal an impression until it becomes viewable',async({page,request})=>{
 const d=await decision(request),before=await campaign(request);await mount(page,d.renderUrl,true);await page.waitForTimeout(1500);
 expect((await campaign(request)).impressions).toBe(before.impressions);
 await page.locator('#ad-slot').scrollIntoViewIfNeeded();await expect.poll(async()=>(await campaign(request)).impressions).toBe(before.impressions+1);
});
test('approved click uses the stored destination and SDK rejects arbitrary render origins',async({page,request})=>{
 const d=await decision(request);await mount(page,d.renderUrl);
 const error=await page.evaluate(()=>{try{(window as any).ContextualAds.mount(document.getElementById('ad-slot'),{renderUrl:'https://evil.example/api/v1/ads/render/00000000-0000-0000-0000-000000000000.fake'});return 'accepted';}catch(e){return (e as Error).message;}});expect(error).toContain('Invalid rendering capability');
 await page.context().route('https://example.org/**',route=>route.fulfill({contentType:'text/html',body:'<h1>Synthetic advertiser destination</h1>'}));
 const popupPromise=page.context().waitForEvent('page');await page.frameLocator('iframe').getByRole('link',{name:'Visit advertiser',exact:true}).click();const popup=await popupPromise;await expect(popup).toHaveURL('https://example.org/advertiser');expect(await popup.evaluate(()=>window.opener===null)).toBe(true);await popup.close();
 const response=await request.get(f.api+'/api/v1/ads/click/'+d.clickToken+'?url=https://evil.example',{maxRedirects:0});expect(response.status()).toBe(303);expect(response.headers().location).toBe('https://example.org/advertiser');
 const forged=await request.get(f.api+'/api/v1/ads/render/'+d.clickToken);expect(forged.status()).toBe(400);
});
test('rejected versions cannot render, win or commit an old impression',async({page,request})=>{
 const d=await decision(request);const review=f.api+'/api/v1/admin/creatives/'+f.creativeId;
 try{expect((await request.post(review+'/reject',{headers:{Authorization:'Bearer '+f.adminToken},data:{reason:'Synthetic browser test revokes this exact version'}})).status()).toBe(200);
 expect((await request.get(d.renderUrl)).status()).toBe(410);
 expect((await request.post(f.api+'/api/v1/ads/impressions',{data:{token:d.impressionToken}})).status()).toBe(400);
 const nofill=await request.post(f.api+'/api/v1/ads/decide',{headers:{Authorization:'Bearer '+f.publisherToken},data:{pageId:f.pageId,placementId:f.placementId,synthetic:true}});expect((await nofill.json()).filled).toBe(false);
 }finally{await request.post(review+'/approve',{headers:{Authorization:'Bearer '+f.adminToken},data:{reason:'Restore synthetic test version after revocation assertions'}});}
});

test('failed render capabilities reach a bounded fallback without measuring an impression',async({page,request})=>{
 const d=await decision(request),before=await campaign(request);
 await mount(page,f.api+'/api/v1/ads/render/'+d.clickToken);
 await expect(page.getByText('Advertisement unavailable',{exact:true})).toBeVisible({timeout:12000});await expect(page.locator('iframe')).toHaveCount(0);
 expect((await campaign(request)).impressions).toBe(before.impressions);
});
