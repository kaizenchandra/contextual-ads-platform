import {NextRequest} from 'next/server';
import {getToken} from 'next-auth/jwt';

async function boundedBody(req:NextRequest){const parts:Uint8Array[]=[];let length=0;const reader=req.body?.getReader();if(!reader)return new Uint8Array(0);while(true){const {value,done}=await reader.read();if(done)break;length+=value.byteLength;if(length>3*1024*1024){await reader.cancel();throw Error('Request too large');}parts.push(value);}const result=new Uint8Array(length);let offset=0;for(const part of parts){result.set(part,offset);offset+=part.length;}return result;}
async function proxy(req: NextRequest, {params}: { params: Promise<{ path: string[] }> }) {
    const {path} = await params;
    if (path.some(p => !/^[-a-zA-Z0-9_.]+$/.test(p))) return new Response(null, {status: 400});
    if(path[0]==='ads' && path[1]==='render')return Response.json({detail:'Use the direct isolated rendering origin'},{status:403});
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
    let body:Uint8Array|undefined;
    if(req.method!=='GET'){try{body=await boundedBody(req);}catch{return Response.json({detail:'Request exceeds 3 MiB'},{status:413});}}
    try {
        const upstream = await fetch(`${process.env.BACKEND_URL || 'http://localhost:8086'}/api/v1/${path.join('/')}`, {
            method: req.method,
            headers: {Authorization: `Bearer ${token.accessToken}`, 'Content-Type': req.headers.get('Content-Type')||'application/json'},
            body: body as BodyInit|undefined,
            redirect: 'manual',
            cache: 'no-store',
            signal: AbortSignal.timeout(15000)
        });
        return new Response(await upstream.arrayBuffer(), {
            status: upstream.status, headers: {
                'Cache-Control': 'no-store', 'X-Content-Type-Options':'nosniff', 'Content-Type': upstream.headers.get('Content-Type') || 'application/json',
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
