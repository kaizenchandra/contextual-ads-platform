import {NextRequest} from 'next/server';
import {getToken} from 'next-auth/jwt';

async function proxy(req: NextRequest, {params}: { params: Promise<{ path: string[] }> }) {
    const {path} = await params;
    if (path.some(p => !/^[-a-zA-Z0-9_.]+$/.test(p))) return new Response(null, {status: 400});
    // Cookie-authenticated BFF mutations require an exact origin. API itself accepts only Bearer tokens.
    if (req.method !== 'GET' && req.headers.get('origin') !== new URL(process.env.AUTH_URL || req.url).origin)
        return Response.json({detail: 'Invalid request origin'}, {status: 403});
    if (!process.env.AUTH_SECRET) return Response.json({detail: 'Authentication is not configured'}, {status: 503});
    const token = await getToken({
        req,
        secret: process.env.AUTH_SECRET,
        secureCookie: (process.env.AUTH_URL || req.url).startsWith('https:')
    });
    if (!token || typeof token.accessToken !== 'string' || typeof token.accessExpires !== 'number' || token.accessExpires * 1000 <= Date.now())
        return Response.json({detail: 'Sign in required; your access token may have expired.'}, {status: 401});
    try {
        const upstream = await fetch(`${process.env.BACKEND_URL || 'http://localhost:8086'}/api/v1/${path.join('/')}`, {
            method: req.method,
            headers: {Authorization: `Bearer ${token.accessToken}`, 'Content-Type': 'application/json'},
            body: req.method === 'GET' ? undefined : await req.text(),
            redirect: 'manual',
            cache: 'no-store',
            signal: AbortSignal.timeout(5000)
        });
        return new Response(await upstream.text(), {
            status: upstream.status, headers: {
                'Cache-Control': 'no-store', 'Content-Type': upstream.headers.get('Content-Type') || 'application/json',
                ...(upstream.headers.get('Location') ? {
                    Location: upstream.headers.get('Location')!,
                    'Referrer-Policy': 'no-referrer'
                } : {})
            }
        });
    } catch {
        return Response.json({detail: 'Backend unavailable'}, {status: 503});
    }
}

export {proxy as GET, proxy as POST};
