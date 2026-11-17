"""Local real-Keycloak smoke test. No token/credential values are printed.
Password grant uses a local test-only client, not the browser/production client.
"""
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

base = os.getenv('ADS_API_URL', 'http://localhost:8086') + '/api/v1/'
issuer = os.getenv('OIDC_ISSUER', 'http://localhost:8186/realms/contextads')
tokens = {}
for user in ['advertiser', 'advertiser2', 'publisher', 'publisher2', 'admin']:
    data = urllib.parse.urlencode({'client_id': 'contextads-test', 'grant_type': 'password', 'username': user,
                                   'password': user + '-dev'}).encode()
    with urllib.request.urlopen(urllib.request.Request(issuer + '/protocol/openid-connect/token', data=data),
                                timeout=10) as r: tokens[user] = json.load(r)['access_token']


def call(path, data=None, user='advertiser', token=None):
    req = urllib.request.Request(base + path, headers={'Authorization': 'Bearer ' + (token or tokens[user]),
                                                       'Content-Type': 'application/json'},
                                 data=json.dumps(data).encode() if data is not None else None)
    with urllib.request.urlopen(req, timeout=10) as r: return json.load(r)


def rejected(status, fn):
    try:
        fn();raise AssertionError('Request unexpectedly accepted')
    except urllib.error.HTTPError as e:
        assert e.code == status, (e.code, e.read())


c = call('campaigns',
         dict(name='OIDC smoke ' + str(time.time_ns()), headline='Synthetic Java', category='technology.java',
              destination='https://example.org', budget=100, dailyBudget=25, bid=5, pacing='EVEN'))['id']
call('admin/creatives/' + c + '/approve', {}, 'admin');
call('campaigns/' + c + '/activate', {})
assert any(r['id'] == c and r['daily_budget'] == 25 and r['pacing'] == 'EVEN' for r in call('analytics'))
rejected(404, lambda: call('campaigns/' + c + '/pause', {}, 'advertiser2'))
rejected(403, lambda: call('admin/creatives/' + c + '/approve', {}, 'advertiser'))
rejected(401, lambda: call('campaigns', token=tokens['advertiser'][:-10] + 'tampered'))
site = call('publishers/websites', {'domain': 'publisher-' + str(time.time_ns()) + '.com'}, 'publisher')
assert site['recordName'].startswith('_contextads-verification.')
rejected(404, lambda: call('publishers/websites/' + site['id'] + '/verify', {}, 'publisher2'))
rejected(403, lambda: call('publishers/placements', {'websiteId': site['id'], 'name': 'unverified'}, 'publisher'))
rejected(403, lambda: call('context/pages',
                           {'websiteId': site['id'], 'url': 'https://' + site['domain'] + '/java', 'title': 'Java',
                            'html': 'java spring developer'}, 'publisher'))
rejected(409, lambda: call('publishers/websites/' + site['id'] + '/verify', {}, 'publisher'))
call('campaigns/' + c + '/pause', {})
print(
    'PASS: real OIDC signed tokens; forged token rejected; campaign daily/pacing configuration; tenant and role isolation; DNS absence and unverified inventory fail closed')
