"""Read-only live acceptance. Explicit opt-in; no credentials or bodies are logged.
See docs/ACCEPTANCE.md for the required temporary backend endpoint and static fixture.
"""
import hashlib
import json
import os
import ssl
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def url(name):
    value = os.environ[name]
    p = urllib.parse.urlsplit(value)
    if p.scheme != 'https' or not p.hostname or p.username or p.password or p.fragment:
        raise ValueError('HTTPS endpoint without userinfo/fragment required: ' + name)
    return value


def request(target, method='GET', headers=None, timeout=15):
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    req = urllib.request.Request(target, method=method, headers=headers or {})
    try:
        response = opener.open(req, timeout=timeout)
    except urllib.error.HTTPError as response:
        return response.code, dict(response.headers.items()), response.read(2 * 1024 * 1024)
    with response:
        return response.status, dict(response.headers.items()), response.read(2 * 1024 * 1024)


def header(headers, name):
    return next((v for k, v in headers.items() if k.lower() == name.lower()), None)


@unittest.skipUnless(os.getenv('EDGEONE_LIVE') == '1', 'Live environment explicitly required')
class EdgeOneLive(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.static = url('EDGEONE_STATIC_URL')
        cls.api = url('EDGEONE_API_URL')
        cls.direct_api = url('DIRECT_API_URL')
        cls.secret = os.environ['API_ORIGIN_TOKEN']
        cls.expected_hash = os.environ['STATIC_SHA256']
        cls.auth_a = os.environ['VIEWER_AUTH_A']
        cls.auth_b = os.environ['VIEWER_AUTH_B']
        cls.subject_a = os.environ['VIEWER_SUBJECT_A']
        cls.subject_b = os.environ['VIEWER_SUBJECT_B']
        if cls.subject_a == cls.subject_b:
            raise ValueError('Two different test users are required')

    def no_secret(self, headers, body):
        wire = json.dumps(headers).encode() + body
        for name in ['API_ORIGIN_TOKEN', 'STATIC_ORIGIN_TOKEN', 'VIEWER_AUTH_A', 'VIEWER_AUTH_B']:
            value = os.getenv(name)
            if value:
                self.assertFalse(value.encode() in wire, 'Secret reflected in response')

    def test_static_get_head_range_conditionals(self):
        status, headers, body = request(self.static)
        self.assertEqual(status, 200)
        self.assertTrue(hashlib.sha256(body).hexdigest() == self.expected_hash, 'Fixture hash mismatch/truncated fixture')
        self.no_secret(headers, body)
        etag = header(headers, 'etag')
        self.assertTrue(bool(etag), 'ETag required')
        status, head_headers, empty = request(self.static, 'HEAD')
        self.assertEqual(status, 200); self.assertEqual(len(empty), 0)
        self.assertEqual(header(head_headers, 'content-length'), str(len(body)))
        status, _, ranged = request(self.static, headers={'Range': 'bytes=0-15'})
        self.assertEqual(status, 206); self.assertTrue(ranged == body[:16], 'Incorrect range')
        status, _, empty = request(self.static, headers={'If-None-Match': etag})
        self.assertEqual(status, 304); self.assertEqual(len(empty), 0)

    def test_client_origin_token_is_overwritten(self):
        # Fixture MUST bypass cache during this test, otherwise a cache hit proves nothing.
        status, headers, body = request(self.static, headers={'X-Origin-Verify': 'attacker-value'})
        self.assertEqual(status, 200)
        self.assertTrue(hashlib.sha256(body).hexdigest() == self.expected_hash)
        self.no_secret(headers, body)

    def test_api_identity_isolation_no_cache_and_token_removed(self):
        for authorization, subject in [(self.auth_a, self.subject_a), (self.auth_b, self.subject_b), (self.auth_a, self.subject_a)]:
            nonce = uuid.uuid4().hex
            target = self.api + ('&' if '?' in self.api else '?') + 'nonce=' + nonce
            status, headers, body = request(target, headers={'Authorization': authorization, 'X-Origin-Verify': 'attacker-value'})
            self.assertEqual(status, 200)
            self.no_secret(headers, body)
            payload = json.loads(body)
            self.assertTrue(payload.get('subject') == subject, 'Identity crossed or lost')
            self.assertTrue(payload.get('nonce') == nonce, 'Cached response or lost query')
            self.assertIs(payload.get('origin_token_present'), False)
            self.assertIn('no-store', (header(headers, 'cache-control') or '').lower())

    def test_direct_api_with_valid_origin_token_is_denied(self):
        # Run outside EdgeOne's origin-pull CIDRs.
        status, headers, body = request(self.direct_api, headers={'X-Origin-Verify': self.secret, 'Authorization': self.auth_a})
        self.assertEqual(status, 403)
        self.no_secret(headers, body)

    def test_direct_api_missing_token_is_denied(self):
        status, _, _ = request(self.direct_api, headers={'Authorization': self.auth_a})
        self.assertEqual(status, 401)

    def test_api_requires_viewer_authentication(self):
        status, headers, body = request(self.api)
        self.assertIn(status, (401, 403))
        self.no_secret(headers, body)


if __name__ == '__main__':
    unittest.main()
