import importlib.util
import json
from pathlib import Path
import sys
import unittest

path = Path(__file__).resolve().parents[1] / 'lambda/authorizer/authorizer.py'
spec = importlib.util.spec_from_file_location('authorizer', path)
a = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = a
spec.loader.exec_module(a)
TOKEN = 'a' * 64
OTHER = 'b' * 64


class Clock:
    def __init__(self): self.now = 0
    def __call__(self): return self.now


class AWS:
    def __init__(self, clock):
        self.clock = clock
        self.ec2_calls = self.secret_calls = 0
        self.ec2_failure = self.secret_failure = False
        self.delay = 0
        self.cidrs = ['43.0.0.0/24', '101.0.0.0/24']
        self.secret = json.dumps({'tokens': [TOKEN]})
        self.loop = False
    def get_managed_prefix_list_entries(self, **kw):
        self.ec2_calls += 1
        self.clock.now += self.delay
        if self.ec2_failure: raise RuntimeError('sensitive SDK error')
        index = int(kw.get('NextToken', 0))
        result = {'Entries': [{'Cidr': self.cidrs[index]}]}
        if index + 1 < len(self.cidrs): result['NextToken'] = str(index + 1)
        if self.loop: result['NextToken'] = '0'
        return result
    def get_secret_value(self, **kw):
        self.secret_calls += 1
        self.clock.now += self.delay
        if self.secret_failure: raise RuntimeError('sensitive SDK error')
        return {'SecretString': self.secret}


def event(token=TOKEN, ip='43.0.0.1'):
    return {'headers': {'x-origin-verify': token}, 'requestContext': {'http': {'sourceIp': ip}}}


class Tests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.aws = AWS(self.clock)
        self.metrics = []
        self.auth = a.Authorizer(self.aws, self.aws, 'pl-test', 'arn:test', self.metrics.append, self.clock)
    def call(self, e=None, context=None):
        return self.auth.authorize(event() if e is None else e, context)['isAuthorized']
    def test_valid_and_cache(self):
        self.assertTrue(self.call()); self.assertTrue(self.call())
        self.assertEqual((2, 1), (self.aws.ec2_calls, self.aws.secret_calls))
    def test_wrong_source_never_reads_secret(self):
        self.assertFalse(self.call(event(ip='8.8.8.8')))
        self.assertEqual(0, self.aws.secret_calls)
    def test_malformed_token_no_remote_calls(self):
        for t in ['', 'short', TOKEN + ',' + TOKEN, 'ą' * 64, None]:
            self.assertFalse(self.call(event(t)))
        self.assertEqual(0, self.aws.ec2_calls)
    def test_source_is_not_forwarded_header(self):
        e = event(ip='8.8.8.8'); e['headers']['x-forwarded-for'] = '43.0.0.1'
        self.assertFalse(self.call(e))
    def test_missing_or_ipv6_source(self):
        for e in [{}, event(ip='::1'), event(ip='043.0.0.1')]: self.assertFalse(self.call(e))
    def test_first_ec2_failure_fails_closed_and_backs_off(self):
        self.aws.ec2_failure = True
        self.assertFalse(self.call()); self.assertFalse(self.call())
        self.assertEqual(1, self.aws.ec2_calls)
        self.assertIn('prefix_list_unavailable', self.metrics)
    def test_empty_cache_retries_after_short_backoff(self):
        self.aws.ec2_failure = True
        self.assertFalse(self.call())
        self.aws.ec2_failure = False
        self.clock.now = 0.5; self.assertFalse(self.call())  # still backing off
        self.clock.now = 1.0; self.assertTrue(self.call())   # recovered after 1 s, not 5 s
        self.assertEqual(2 + 1, self.aws.ec2_calls)          # failed call + 2 pages
    def test_stale_value_keeps_long_backoff(self):
        self.assertTrue(self.call()); self.aws.secret_failure = True
        self.clock.now = 31; self.assertTrue(self.call())
        calls = self.aws.secret_calls
        self.clock.now = 35; self.assertTrue(self.call())
        self.assertEqual(calls, self.aws.secret_calls)
    def test_slow_successful_load_is_cached_but_request_denied(self):
        self.aws.delay = 4  # two EC2 pages * 4 s exceed the 7 s budget after the load
        self.assertFalse(self.call())
        self.aws.delay = 0; self.clock.now += 1
        self.assertTrue(self.call())
        self.assertEqual(2, self.aws.ec2_calls)              # list reused, not reloaded
    def test_first_secret_failure(self):
        self.aws.secret_failure = True
        self.assertFalse(self.call())
        self.assertIn('secret_unavailable', self.metrics)
    def test_secret_stale_then_hard_expiry(self):
        self.assertTrue(self.call()); self.aws.secret_failure = True
        self.clock.now = 31; self.assertTrue(self.call())
        self.assertIn('stale_cache_used', self.metrics)
        self.clock.now = 90; self.assertFalse(self.call())
        self.aws.secret_failure = False; self.clock.now = 96
        self.assertTrue(self.call())
    def test_cidr_stale_then_hard_expiry(self):
        self.assertTrue(self.call()); self.aws.ec2_failure = True
        self.clock.now = 301; self.assertTrue(self.call())
        self.clock.now = 3600; self.assertFalse(self.call())
    def test_revocation_after_refresh(self):
        self.assertTrue(self.call()); self.aws.secret = json.dumps({'tokens': [OTHER]})
        self.clock.now = 31
        self.assertFalse(self.call()); self.assertTrue(self.call(event(OTHER)))
    def test_cidr_removal(self):
        self.assertTrue(self.call()); self.aws.cidrs = ['101.0.0.0/24']
        self.clock.now = 301; self.assertFalse(self.call())
    def test_two_tokens_and_invalid_secret(self):
        self.aws.secret = json.dumps({'tokens': [TOKEN, OTHER]})
        self.assertTrue(self.call()); self.assertTrue(self.call(event(OTHER)))
        for raw in ['{}', 'null', '{', json.dumps({'tokens': [TOKEN, TOKEN]}), json.dumps({'tokens': [3]})]:
            with self.assertRaises((ValueError, TypeError)): a.parse_tokens(raw)
    def test_pagination_deadline_stops_before_secret(self):
        self.aws.delay = 4
        self.assertFalse(self.call())
        self.assertEqual(0, self.aws.secret_calls)
    def test_low_lambda_remaining_time(self):
        class Context:
            def get_remaining_time_in_millis(self): return 1500
        self.assertFalse(self.call(context=Context()))
        self.assertEqual(0, self.aws.ec2_calls)
    def test_pagination_loop_rejected(self):
        self.aws.loop = True; self.assertFalse(self.call())
        self.assertEqual(2, self.aws.ec2_calls)
    def test_budget_expiry_does_not_authorize_stale(self):
        self.assertTrue(self.call()); self.clock.now = 301; self.aws.delay = 8
        self.assertFalse(self.call())
    def test_zero_stale_emergency_mode(self):
        self.auth.tokens.stale = 0
        self.assertTrue(self.call()); self.aws.secret_failure = True
        self.clock.now = 30; self.assertFalse(self.call())

if __name__ == '__main__': unittest.main()
