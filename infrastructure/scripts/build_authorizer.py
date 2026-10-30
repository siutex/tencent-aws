"""Deterministic source-only ZIP; boto3/botocore supplied by the managed runtime.
No Maven or proxy checkout needed. Pin/vendor SDK in CI if its exact version is required.
"""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_STORED
root = Path(__file__).resolve().parents[1]
out = root / 'build' / 'authorizer.zip'
out.parent.mkdir(exist_ok=True)
with ZipFile(out, 'w') as z:
    info = ZipInfo('authorizer.py', (2026, 1, 1, 0, 0, 0))
    # STORED, not DEFLATED: compressed bytes differ between zlib and zlib-ng, which would change
    # source_code_hash (and publish a new Lambda version) depending on who built the ZIP.
    info.compress_type = ZIP_STORED
    info.external_attr = 0o644 << 16
    z.writestr(info, (root / 'lambda/authorizer/authorizer.py').read_bytes())
print(out)
