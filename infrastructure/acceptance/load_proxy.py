"""Explicit, finite staging-only load probe. No automatic execution or retry.
Measures full transfer latency, verifies SHA-256, optionally throttles readers.
"""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
import time
import urllib.error
from urllib.request import Request, build_opener, HTTPSHandler
from test_edgeone_live import NoRedirect, url


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--confirm-staging', action='store_true', required=True)
    p.add_argument('--requests', type=int, default=100)
    p.add_argument('--concurrency', type=int, default=32)
    p.add_argument('--slow-read-ms', type=int, default=0)
    args = p.parse_args()
    if not (1 <= args.requests <= 5000 and 1 <= args.concurrency <= 512 and 0 <= args.slow_read_ms <= 1000):
        p.error('Limits: requests 1..5000, concurrency 1..512, slow read 0..1000ms')
    target = url('LOAD_URL')
    expected = os.environ['LOAD_SHA256']
    token = os.getenv('LOAD_ORIGIN_TOKEN')
    def one(_):
        start = time.monotonic()
        try:
            req = Request(target, headers={'X-Origin-Verify': token} if token else {})
            with build_opener(NoRedirect(), HTTPSHandler()).open(req, timeout=30) as response:
                digest = hashlib.sha256()
                total = 0
                while True:
                    chunk = response.read(64 * 1024)
                    if not chunk: break
                    total += len(chunk)
                    if total > 512 * 1024 * 1024 or time.monotonic() - start > 120:
                        raise TimeoutError("Transfer budget exceeded")
                    digest.update(chunk)
                    if args.slow_read_ms: time.sleep(args.slow_read_ms / 1000)
                code = response.status if digest.hexdigest() == expected else 'bad_hash'
        except urllib.error.HTTPError as e:
            code = e.code
            e.close()
        except Exception:
            code = 'transport_error'
        return code, time.monotonic() - start
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        results = list(pool.map(one, range(args.requests)))
    counts = Counter(str(r[0]) for r in results)
    times = sorted(r[1] for r in results)
    print(json.dumps({'requests': len(results), 'status': counts,
        'p50_seconds': times[int((len(times)-1)*.5)], 'p95_seconds': times[int((len(times)-1)*.95)],
        'max_seconds': max(times)}, indent=2))
    # 503 is permitted only for the deliberate overload phase, checked by the operator.
    return 0 if set(counts) <= {'200', '503'} and counts['200'] > 0 else 1


if __name__ == '__main__': raise SystemExit(main())
