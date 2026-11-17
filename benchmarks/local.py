"""Synthetic benchmark. Requires a verified owned page/placement and an OIDC token.
No cached local password/token identities are used. Do not use real advertising inventory.
"""
import concurrent.futures
import json
import os
import platform
import time
import urllib.request

url = os.getenv('ADS_API_URL', 'http://localhost:8086') + '/api/v1/ads/decide'
headers = {'Authorization': 'Bearer ' + os.environ['ACCESS_TOKEN'], 'Content-Type': 'application/json'}
body = json.dumps({'pageId': os.environ['PAGE_ID'], 'placementId': os.environ['PLACEMENT_ID']}).encode()


def run(_):
    start = time.perf_counter()
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers, data=body), timeout=10) as r: json.load(r)
    return (time.perf_counter() - start) * 1000


start = time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool: values = sorted(pool.map(run, range(100)))
elapsed = time.perf_counter() - start
print(json.dumps(
    {'workload': '100 decisions, 8 closed-loop clients', 'machine': platform.machine(), 'seconds': round(elapsed, 3),
     'achievedRps': round(100 / elapsed, 2), 'p50Ms': round(values[49], 2), 'p95Ms': round(values[94], 2),
     'p99Ms': round(values[98], 2), 'errors': 0, 'limitations': 'Small closed-loop workload; not a capacity claim'},
    indent=2))
