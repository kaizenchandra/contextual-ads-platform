"use client";
import {useEffect, useRef, useState} from 'react';
import {signIn, signOut} from 'next-auth/react';
import Finance from './finance';

type Row = Record<string, unknown>;
type Decision = {
    filled: boolean;
    headline?: string;
    advertiser?: string;
    impressionToken?: string;
    clickToken?: string
};
const articles = [['Electric vehicles and charging', 'Electric vehicle battery charging infrastructure makes electric transport practical.'], ['Java development', 'Java spring jvm developer code practices for reliable services.'], ['Cloud infrastructure', 'Cloud kubernetes infrastructure server deployment.'], ['Travel destinations', 'Travel hotel destination holiday planning.'], ['Fitness equipment', 'Fitness exercise gym training equipment.'], ['Home improvement', 'Home renovation kitchen garden projects.']];
export default function Home() {
    const [role, setRole] = useState('advertiser'), [rows, setRows] = useState<Row[]>([]), [message, setMessage] = useState(''), [ad, setAd] = useState<Decision | null>(null), [article, setArticle] = useState(0), [dailyRows, setDailyRows] = useState<Row[]>([]), [websites, setWebsites] = useState<Row[]>([]), [placements, setPlacements] = useState<Row[]>([]), [websiteId, setWebsiteId] = useState(''), [placementId, setPlacementId] = useState(''), [pageUrl, setPageUrl] = useState('');
    const slot = useRef<HTMLElement>(null);

    async function api(path: string, body?: unknown, user = role) {
        const r = await fetch('/api/' + path, {
            method: body ? 'POST' : 'GET',
            headers: {'Content-Type': 'application/json'},
            body: body ? JSON.stringify(body) : undefined
        });
        const text = await r.text();
        let data;
        try {
            data = text ? JSON.parse(text) : {};
        } catch {
            data = {};
        }
        if (!r.ok) throw Error(data.detail || `Request failed: ${r.status}`);
        return data;
    }

    async function inventory() {
        try {
            const w = await api('publishers/websites');
            const p = await api('publishers/placements');
            setWebsites(w);
            setPlacements(p);
        } catch (e) {
            setMessage(String(e));
        }
    }

    async function load() {
        try {
            setRows(await api(role === 'admin' ? 'admin/campaigns' : role === 'advertiser' ? 'analytics' : 'admin/campaigns'));
            setMessage('Loaded current database values.')
        } catch (e) {
            setMessage(String(e));
        }
    }

    useEffect(() => {
        setRows([]);
        setMessage('');
    }, [role]);
    useEffect(() => {
        if (!ad?.impressionToken || !slot.current) return;
        let timer: ReturnType<typeof setTimeout> | undefined;
        const observer = new IntersectionObserver(([e]) => {
            if (timer) clearTimeout(timer);
            if (e.intersectionRatio >= .5 && document.visibilityState === 'visible') timer = setTimeout(() => {
                api('ads/impressions', {token: ad.impressionToken}, 'publisher').then(() => setMessage('Viewability signal recorded; human attention is unverified.')).catch(e => setMessage(String(e)));
                observer.disconnect();
            }, 1000);
        }, {threshold: [.5]});
        observer.observe(slot.current);
        return () => {
            observer.disconnect();
            if (timer) clearTimeout(timer);
        };
    }, [ad]);

    async function serve() {
        try {
            const page = await api('context/pages', {
                title: articles[article][0],
                html: articles[article][1],
                websiteId,
                url: pageUrl
            }, 'publisher');
            setAd(await api('ads/decide', {pageId: page.id, placementId, synthetic: true}, 'publisher'));
            setMessage('Context: ' + page.context.category);
        } catch (e) {
            setMessage(String(e));
        }
    }

    return <main>
        <header><span>CONTEXT / ADS LAB</span><small>Context-only serving · authenticated with OIDC</small></header>
        <h1>Advertising with context.</h1><p>Configure campaigns, review sponsored text, and see content-based matching
        in action.</p>
        <button onClick={() => signIn('keycloak')}>Sign in</button>
        <button onClick={() => signOut()}>Sign out</button>
        <nav aria-label="Console view">{['advertiser', 'publisher', 'admin'].map(r => <button key={r}
                                                                                              aria-pressed={r === role}
                                                                                              onClick={() => setRole(r)}>{r}</button>)}</nav>
        <p role="status" aria-label="Platform status">{message}</p>
        {role === 'advertiser' && <section><h2>Create a campaign</h2>
            <form onSubmit={async e => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                try {
                    await api('campaigns', {
                        name: f.get('name'),
                        headline: f.get('headline'),
                        category: f.get('category'),
                        destination: f.get('destination'),
                        budget: Number(f.get('budget')),
                        bid: Number(f.get('bid')),
                        dailyBudget: Number(f.get('dailyBudget')),
                        pacing: f.get('pacing')
                    });
                    setMessage('Campaign created. Admin approval and activation required.');
                    await load();
                } catch (e) {
                    setMessage(String(e));
                }
            }}>{[['name', 'Campaign name', 'text'], ['headline', 'Creative headline', 'text'], ['destination', 'HTTPS destination (no query)', 'url'], ['budget', 'Lifetime budget in minor units', 'number'], ['bid', 'Price per impression in minor units', 'number'], ['dailyBudget', 'Daily budget in minor units', 'number']].map(([name, label, type]) =>
                <label key={name}>{label}<input name={name} type={type} required min={1}
                                                maxLength={160}/></label>)}<label>Target topic<select
                name="category">{['automotive.ev', 'technology.java', 'technology.cloud', 'lifestyle.travel', 'lifestyle.fitness', 'lifestyle.home'].map(c =>
                <option key={c}>{c}</option>)}</select></label><label>Pacing<select name="pacing" aria-label="Pacing">
                <option>ACCELERATED</option>
                <option>EVEN</option>
            </select></label>
                <button>Create draft</button>
            </form>
        </section>}
        {dailyRows.length > 0 && <section><h2>Daily budget accounting (UTC)</h2>
            <pre>{JSON.stringify(dailyRows, null, 2)}</pre>
        </section>}{role !== 'publisher' &&
        <section><h2>{role === 'admin' ? 'Creative review' : 'Campaign performance'}</h2>
            <button onClick={load}>Refresh</button>
            {rows.length === 0 && <p>No records loaded.</p>}{rows.map(r => <article key={String(r.id)}>
                <h3>{String(r.name)}</h3><p>{String(r.headline || '')} · {String(r.status || '')}</p>
                <pre>{JSON.stringify(r, null, 2)}</pre>
                {role === 'advertiser' && <button
                    onClick={() => api(`campaigns/${r.id}/daily-budgets`).then(setDailyRows).catch(e => setMessage(String(e)))}>Daily
                    budget ledger</button>}{role === 'admin' ? <button
                onClick={() => api(`admin/creatives/${r.id}/approve`, {}).then(load).catch(e => setMessage(String(e)))}>Approve
                text creative</button> : ['activate', 'pause'].map(action => <button key={action}
                                                                                     onClick={() => api(`campaigns/${r.id}/${action}`, {}).then(load).catch(e => setMessage(String(e)))}>{action}</button>)}
            </article>)}</section>}
        {role === 'publisher' &&
            <section><h2>Website verification and placements</h2><p>Add the returned DNS TXT record, then verify.
                Verification expires after 30 days.</p>
                <form onSubmit={async e => {
                    e.preventDefault();
                    const f = new FormData(e.currentTarget);
                    try {
                        const result = await api('publishers/websites', {domain: f.get('domain')});
                        setMessage(JSON.stringify(result));
                        await inventory();
                    } catch (e) {
                        setMessage(String(e));
                    }
                }}><label>Public website domain<input name="domain" required placeholder="publisher.com"/></label>
                    <button>Register domain / rotate challenge</button>
                </form>
                <button onClick={inventory}>Refresh websites and placements</button>
                {websites.map(w => <article key={String(w.id)}><h3>{String(w.domain)}</h3><p>Verified
                    until: {String(w.verified_until || 'Not verified')}</p>
                    <button onClick={() => api(`publishers/websites/${w.id}/verify`, {}).then(() => {
                        setMessage('Verified');
                        inventory();
                    }).catch(e => setMessage(String(e)))}>Verify DNS
                    </button>
                    <button
                        onClick={() => api(`publishers/websites/${w.id}/revoke`, {}).then(inventory).catch(e => setMessage(String(e)))}>Revoke
                    </button>
                </article>)}
                <form onSubmit={async e => {
                    e.preventDefault();
                    const f = new FormData(e.currentTarget);
                    try {
                        await api('publishers/placements', {websiteId: f.get('website'), name: f.get('name')});
                        await inventory();
                    } catch (e) {
                        setMessage(String(e));
                    }
                }}><label>Website<select name="website" aria-label="Website" required>{websites.map(w => <option
                    value={String(w.id)} key={String(w.id)}>{String(w.domain)}</option>)}</select></label><label>Placement
                    name<input name="name" required maxLength={100}/></label>
                    <button>Create placement</button>
                </form>
            </section>}
        <section><h2>Publisher demonstration</h2>
            <button onClick={inventory}>Load my inventory</button>
            <label>Verified website<select value={websiteId} onChange={e => setWebsiteId(e.target.value)}>
                <option value="">Select website</option>
                {websites.map(w => <option key={String(w.id)} value={String(w.id)}>{String(w.domain)}</option>)}
            </select></label><label>Owned placement<select value={placementId}
                                                           onChange={e => setPlacementId(e.target.value)}>
                <option value="">Select placement</option>
                {placements.filter(p => p.website_id === websiteId).map(p => <option key={String(p.id)}
                                                                                     value={String(p.id)}>{String(p.name)}</option>)}
            </select></label><label>Approved page URL<input type="url" value={pageUrl}
                                                            onChange={e => setPageUrl(e.target.value)}
                                                            placeholder="https://publisher.com/article"/></label><label>Article<select
                aria-label="Article" value={article} onChange={e => {
                setArticle(Number(e.target.value));
                setAd(null);
            }}>{articles.map((a, i) => <option value={i} key={i}>{a[0]}</option>)}</select></label>
            <h3>{articles[article][0]}</h3><p>{articles[article][1]}</p>
            <button onClick={serve}>Analyze content and request an ad</button>
            <aside ref={slot} aria-label="Sponsored placement"><strong>Sponsored</strong>{ad?.filled ? <>
                    <h3>{ad.headline}</h3><p>Advertiser: {ad.advertiser}</p><a rel="sponsored noreferrer"
                                                                               href={'/api/ads/click/' + ad.clickToken}
                                                                               onClick={async e => {
                                                                                   e.preventDefault();
                                                                                   window.location.href = (process.env.NEXT_PUBLIC_TRACKING_URL || 'http://localhost:8086') + '/api/v1/ads/click/' + ad.clickToken;
                                                                               }}>Visit advertiser ↗</a></> :
                <p>{ad ? 'No eligible campaign.' : 'Request an advertisement to preview this placement.'}</p>}</aside>
        </section>
        <Finance role={role}/>
        <footer>No browser identifiers or cross-site profiles. The selected console view does not grant permissions. The
            API checks your OIDC roles and resource ownership.
        </footer>
    </main>
}
