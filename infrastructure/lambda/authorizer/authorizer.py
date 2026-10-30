"""HTTP API payload 2.0 authorizer. No credentials, headers or events in logs."""
import hmac
import ipaddress
import json
import os
import re
import time
from dataclasses import dataclass

TOKEN = re.compile(r'[A-Za-z0-9_-]{32,256}', re.ASCII)


def metric(reason):
    # Fixed metric names and a single low-cardinality dimension.
    print(json.dumps({'_aws': {'Timestamp': int(time.time() * 1000),
        'CloudWatchMetrics': [{'Namespace': 'EdgeOne/Origin', 'Dimensions': [['Function']],
        'Metrics': [{'Name': reason, 'Unit': 'Count'}]}]},
        'Function': os.environ.get('AWS_LAMBDA_FUNCTION_NAME', 'authorizer'), reason: 1}))


class Unavailable(Exception):
    pass


class Budget:
    def __init__(self, context, clock=time.monotonic, seconds=7.0):
        self.clock, self.context = clock, context
        self.deadline = clock() + seconds

    def remaining(self):
        remaining = self.deadline - self.clock()
        if self.context is not None:
            remaining = min(remaining, self.context.get_remaining_time_in_millis() / 1000 - .75)
        return remaining

    def check(self, reserve=0):
        if self.remaining() <= reserve:
            raise Unavailable('budget_exhausted')


@dataclass
class Cache:
    ttl: float
    stale: float
    value: object = None
    loaded_at: float = 0
    retry_at: float = 0

    def usable(self, now):
        return self.value is not None and now - self.loaded_at < self.ttl + self.stale

    def get(self, loader, budget, name, emit):
        now = budget.clock()
        if self.value is not None and now - self.loaded_at < self.ttl:
            return self.value
        if now >= self.retry_at:
            try:
                budget.check(1.5)
                value = loader(budget)
            except Exception:
                # Also catches SDK failures. Do not print exception messages.
                # Long backoff only while a stale value can still be served; with nothing to
                # serve, a 5 s backoff would deny every request of this environment after one
                # transient error (e.g. a slow TLS handshake on a cold start).
                self.retry_at = budget.clock() + (5 if self.usable(budget.clock()) else 1)
                emit(name + '_unavailable')
            else:
                # Keep a successful load even if the budget is now exhausted: the value is valid,
                # only this request is out of time.
                self.value, self.loaded_at, self.retry_at = value, budget.clock(), 0
                budget.check()
                return value
        if self.usable(budget.clock()):
            emit('stale_cache_used')
            return self.value
        raise Unavailable(name + '_unavailable')


def parse_tokens(raw):
    data = json.loads(raw)
    values = data.get('tokens') if isinstance(data, dict) else None
    if (not isinstance(values, list) or not 1 <= len(values) <= 2
            or any(not isinstance(t, str) or TOKEN.fullmatch(t) is None for t in values)
            or len(set(values)) != len(values)):
        raise ValueError('invalid_secret')
    return tuple(values)


class Authorizer:
    def __init__(self, ec2, secrets, prefix_id, secret_arn, emit=metric,
                 clock=time.monotonic, cidr_stale=3300, token_stale=60):
        self.ec2, self.secrets = ec2, secrets
        self.prefix_id, self.secret_arn = prefix_id, secret_arn
        self.emit, self.clock = emit, clock
        self.networks, self.tokens = Cache(300, cidr_stale), Cache(30, token_stale)

    def load_networks(self, budget):
        networks, next_token, seen = [], None, set()
        for _ in range(20):
            budget.check(1.5)
            args = {'PrefixListId': self.prefix_id, 'MaxResults': 100}
            if next_token:
                args['NextToken'] = next_token
            page = self.ec2.get_managed_prefix_list_entries(**args)
            for item in page.get('Entries', []):
                network = ipaddress.ip_network(item['Cidr'], strict=True)
                if network.version != 4 or network.prefixlen < 8:
                    raise ValueError('invalid_prefix_list')
                networks.append(network)
            next_token = page.get('NextToken')
            if not next_token:
                if not networks:
                    raise ValueError('empty_prefix_list')
                return tuple(networks)
            if next_token in seen:
                raise ValueError('pagination_loop')
            seen.add(next_token)
        raise ValueError('too_many_pages')

    def load_tokens(self, budget):
        budget.check(1.5)
        value = self.secrets.get_secret_value(SecretId=self.secret_arn, VersionStage='AWSCURRENT')
        return parse_tokens(value['SecretString'])

    def authorize(self, event, context=None):
        budget = Budget(context, self.clock)
        try:
            headers = event.get('headers') or {}
            candidates = [v for k, v in headers.items() if k.lower() == 'x-origin-verify']
            if len(candidates) != 1 or not isinstance(candidates[0], str) or not TOKEN.fullmatch(candidates[0]):
                self.emit('invalid_token')
                return {'isAuthorized': False}
            try:
                ip = ipaddress.ip_address(event['requestContext']['http']['sourceIp'])
                if ip.version != 4:
                    raise ValueError('ipv6')
            except (KeyError, TypeError, ValueError):
                self.emit('source_denied')
                return {'isAuthorized': False}
            networks = self.networks.get(self.load_networks, budget, 'prefix_list', self.emit)
            if not any(ip in n for n in networks):
                self.emit('source_denied')
                return {'isAuthorized': False}
            tokens = self.tokens.get(self.load_tokens, budget, 'secret', self.emit)
            matches = 0
            for token in tokens:
                matches |= hmac.compare_digest(candidates[0], token)
            budget.check()
            self.emit('allowed' if matches else 'invalid_token')
            return {'isAuthorized': bool(matches)}
        except Unavailable:
            self.emit('dependency_denied')
            return {'isAuthorized': False}
        except Exception:
            self.emit('invalid_event')
            return {'isAuthorized': False}


_instance = None


def handler(event, context):
    global _instance
    try:
        if _instance is None:
            import boto3
            from botocore.config import Config
            # No SDK retries; cache owns backoff. Bound every page/request separately.
            config = Config(connect_timeout=.4, read_timeout=.6, retries={'total_max_attempts': 1})
            _instance = Authorizer(boto3.client('ec2', config=config),
                boto3.client('secretsmanager', config=config), os.environ['PREFIX_LIST_ID'],
                os.environ['SECRET_ARN'],
                cidr_stale=float(os.environ.get('CIDR_MAX_STALE_SECONDS', '3300')),
                token_stale=float(os.environ.get('TOKEN_MAX_STALE_SECONDS', '60')))
        return _instance.authorize(event, context)
    except Exception:
        metric('initialization_unavailable')
        return {'isAuthorized': False}
