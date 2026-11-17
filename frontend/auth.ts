import NextAuth, {customFetch} from 'next-auth';
import Keycloak from 'next-auth/providers/keycloak';

const issuer = process.env.OIDC_ISSUER || 'http://localhost:8186/realms/contextads';
export const {handlers, auth, signIn, signOut} = NextAuth({
    trustHost: true,
    session: {strategy: 'jwt', maxAge: 15 * 60},
    providers: [Keycloak({
        issuer, clientId: 'contextads-web', clientSecret: process.env.OIDC_CLIENT_SECRET,
        checks: ['pkce', 'state', 'nonce'],
        [customFetch]: async (input, init) => {
            let url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
            if (process.env.OIDC_INTERNAL_ORIGIN && url.startsWith(new URL(issuer).origin + '/'))
                url = process.env.OIDC_INTERNAL_ORIGIN + url.slice(new URL(issuer).origin.length);
            return fetch(url, {...init, signal: AbortSignal.timeout(5000)});
        }
    })],
    callbacks: {
        jwt({token, account}) {
            if (account) {
                token.accessToken = account.access_token;
                token.accessExpires = account.expires_at;
            }
            return token;
        },
        // Access/refresh tokens are never included in the public session JSON.
        session({session}) {
            return session;
        }
    }
});
