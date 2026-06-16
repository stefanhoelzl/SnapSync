#!/usr/bin/env python3
"""Independent AWS SigV4 query-presigner (pure stdlib), written from the AWS spec.

It is the golden oracle for the `s3-request-provider` change: first it reproduces AWS's own
published known-answer vector (proving this reference is AWS-correct), then it emits the golden
values for the provider's path-style + metadata shape, which are pasted into the Kotlin golden test.

Run:  python3 openspec/changes/s3-request-provider/sigv4_reference.py
"""
import hashlib
import hmac
from urllib.parse import quote

ALGORITHM = "AWS4-HMAC-SHA256"
UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"


def _sha256_hex(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def _hmac(key: bytes, data: str) -> bytes:
    return hmac.new(key, data.encode("utf-8"), hashlib.sha256).digest()


def _rfc3986(value: str) -> str:
    # AWS canonical-query encoding: unreserved = A-Za-z0-9-._~ ; everything else %XX (uppercase).
    return quote(value, safe="-._~")


def presign(access_key, secret_key, region, service, http_method,
            host, canonical_uri, signed_headers, amz_date, expires):
    """signed_headers: dict of lowercase header name -> value (must include 'host')."""
    date_stamp = amz_date[:8]
    scope = f"{date_stamp}/{region}/{service}/aws4_request"
    signed_names = ";".join(sorted(signed_headers))

    query_params = {
        "X-Amz-Algorithm": ALGORITHM,
        "X-Amz-Credential": f"{access_key}/{scope}",
        "X-Amz-Date": amz_date,
        "X-Amz-Expires": str(expires),
        "X-Amz-SignedHeaders": signed_names,
    }
    canonical_query = "&".join(
        f"{_rfc3986(k)}={_rfc3986(v)}" for k, v in sorted(query_params.items())
    )
    canonical_headers = "".join(f"{n}:{signed_headers[n].strip()}\n" for n in sorted(signed_headers))
    canonical_request = "\n".join([
        http_method, canonical_uri, canonical_query, canonical_headers, signed_names, UNSIGNED_PAYLOAD,
    ])
    string_to_sign = "\n".join([ALGORITHM, amz_date, scope, _sha256_hex(canonical_request)])

    k_date = _hmac(("AWS4" + secret_key).encode("utf-8"), date_stamp)
    k_region = _hmac(k_date, region)
    k_service = _hmac(k_region, service)
    k_signing = _hmac(k_service, "aws4_request")
    signature = hmac.new(k_signing, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    return canonical_request, string_to_sign, signature, canonical_query


# --- 1. Validate against AWS's published vector (GET examplebucket/test.txt) ----------------------
# Source: AWS SigV4 docs, "Example: GET Object" presigned-URL worked example.
_cr, _sts, sig, _q = presign(
    access_key="AKIAIOSFODNN7EXAMPLE",
    secret_key="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    region="us-east-1", service="s3", http_method="GET",
    host="examplebucket.s3.amazonaws.com", canonical_uri="/test.txt",
    signed_headers={"host": "examplebucket.s3.amazonaws.com"},
    amz_date="20130524T000000Z", expires=86400,
)
AWS_EXPECTED = "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"
assert sig == AWS_EXPECTED, f"reference FAILS AWS vector: {sig} != {AWS_EXPECTED}"
print("OK  reference reproduces AWS published vector:", sig)
print()

# --- 2. Generate the golden for the provider's path-style + metadata shape ------------------------
ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"
SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
REGION = "eu-central-1"
BUCKET = "snapsync-test"
ENDPOINT = "https://s3.eu-central-1.amazonaws.com"
KEY = "resources/AB%2Fcd-ios.photo.jpg"        # filename "AB/cd-ios.photo.jpg" after key-encoding
HOST = "s3.eu-central-1.amazonaws.com"
AMZ_DATE = "20260615T120000Z"                   # 2026-06-15T12:00:00Z
EXPIRES = 604800
SIGNED = {
    "host": HOST,
    "content-type": "image/jpeg",
    "x-amz-meta-asset-id": "ABC123",
    "x-amz-meta-original-filename": "IMG_0001.HEIC",
}
cr, sts, signature, canonical_query = presign(
    ACCESS_KEY, SECRET_KEY, REGION, "s3", "PUT",
    HOST, f"/{BUCKET}/{KEY}", SIGNED, AMZ_DATE, EXPIRES,
)
url = f"{ENDPOINT}/{BUCKET}/{KEY}?{canonical_query}&X-Amz-Signature={signature}"
print("CANONICAL_REQUEST:\n" + cr + "\n")
print("STRING_TO_SIGN:\n" + sts + "\n")
print("SIGNATURE:", signature)
print("URL:", url)
