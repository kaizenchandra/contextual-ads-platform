"use client";
import {useEffect, useState} from 'react';

type Row = Record<string, unknown>;

async function api(path: string, data?: unknown) {
    const response = await fetch('/api/' + path, {
        method: data ? 'POST' : 'GET',
        headers: {'Content-Type': 'application/json'},
        body: data ? JSON.stringify(data) : undefined
    });
    const body = await response.json();
    if (!response.ok) throw new Error(body.detail || body.title || String(response.status));
    return body;
}

export default function Finance({role}: { role: string }) {
    const [records, setRecords] = useState<Row[]>([]), [summary, setSummary] = useState<unknown>(null), [message, setMessage] = useState(''), [reason, setReason] = useState(''), [delivery, setDelivery] = useState<unknown>(null);
    useEffect(() => {
        setRecords([]);
        setSummary(null);
        setDelivery(null);
        setMessage('');
    }, [role]);

    async function load() {
        try {
            setRecords(await api(role === 'admin' ? 'admin/fraud' : 'billing/entries'));
            setSummary(await api(role === 'admin' ? 'admin/billing-reconciliation' : 'billing/summary'));
            setMessage('Loaded authoritative review and ledger data.');
        } catch (e) {
            setMessage(String(e));
        }
    }

    if (role === 'publisher') return null;
    return <section><h2>{role === 'admin' ? 'Traffic review and billing' : 'Validated billing ledger'}</h2><p>Client
        measurements are provisional. Only an administrator who has reviewed independent evidence can authorize a
        charge. Invoices and payments are separate and are not issued here.</p><p role="status"
                                                                                  aria-label="Billing status">{message}</p>
        <button onClick={load}>Refresh billing</button>
        {role === 'admin' && <><label>Review evidence / replay reason<textarea value={reason}
                                                                               onChange={e => setReason(e.target.value)}
                                                                               minLength={12} maxLength={2000}/></label>
            <button onClick={() => api('admin/events/status').then(setDelivery).catch(e => setMessage(String(e)))}>Event
                delivery status
            </button>
            <form onSubmit={async e => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                try {
                    await api(`admin/events/${f.get('event')}/replay`, {reason});
                    setMessage('Replay queued with original event identity.');
                } catch (e) {
                    setMessage(String(e));
                }
            }}><label>Outbox event ID<input name="event" required placeholder="Event UUID"/></label>
                <button>Replay outbox event</button>
            </form>
            <form onSubmit={async e => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                try {
                    await api(`admin/dead-letters/${f.get('failure')}/${f.get('action')}`, {reason});
                    setMessage('Dead-letter action recorded.');
                } catch (e) {
                    setMessage(String(e));
                }
            }}><label>Dead-letter ID<input type="number" name="failure" min={1} required/></label><label>Recovery action<select
                name="action" aria-label="Recovery action">
                <option value="replay">Replay canonical event</option>
                <option value="discard">Discard with evidence</option>
            </select></label>
                <button>Apply dead-letter action</button>
            </form>
        </>}{records.length === 0 && <p>No ledger or review records loaded.</p>}{records.map((r, i) => <article
            key={String(r.id || r.decision_id || i)}><strong>{String(r.status || r.kind)}</strong>
            <pre>{JSON.stringify(r, null, 2)}</pre>
            {role === 'admin' && ['VALIDATE', 'INVALIDATE', 'REVERSE'].map(action => <button key={action}
                                                                                             disabled={reason.trim().length < 12}
                                                                                             onClick={async () => {
                                                                                                 try {
                                                                                                     await api(`admin/fraud/${r.decision_id}/review`, {
                                                                                                         action,
                                                                                                         reason
                                                                                                     });
                                                                                                     await load();
                                                                                                 } catch (e) {
                                                                                                     setMessage(String(e));
                                                                                                 }
                                                                                             }}>{action}</button>)}
        </article>)}{summary !== null && <><h3>Balances and reconciliation</h3>
            <pre>{JSON.stringify(summary, null, 2)}</pre>
        </>}{delivery !== null && <><h3>Durable event delivery</h3>
            <pre>{JSON.stringify(delivery, null, 2)}</pre>
        </>}</section>
}
