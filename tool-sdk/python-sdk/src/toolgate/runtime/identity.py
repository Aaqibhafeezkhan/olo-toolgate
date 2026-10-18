# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Adapter A4: verified JWTs and RFC 7662 introspection, before protocol dispatch."""
from __future__ import annotations

import base64
import hashlib
import json
import threading
import time
import datetime as dt
from pathlib import Path
from urllib.parse import urlencode, urlsplit

from ..protocol.identity import IdentityVerifier, Rejected, VerifiedIdentity
from .._json import loads
from ..protocol.request_states import b64url_decode
from .config import Mode, StartupRefused
from .network import request_url


def https(url):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.fragment:
        raise StartupRefused("CONFIGURATION", "Identity endpoints must use HTTPS")
    return url


class JwtVerifier(IdentityVerifier):
    def __init__(self, issuer, audience, *, keys=None, jwks_url=None, algorithms=("RS256", "ES256")):
        try:
            import cryptography  # noqa: F401
        except ImportError:
            raise StartupRefused("CONFIGURATION", "JWT verification requires toolgate-sdk[identity]")
        self.issuer, self.audience = issuer, audience
        self.url = https(jwks_url) if jwks_url else None
        self.algorithms = {a.strip() for a in algorithms}
        if not self.algorithms or not self.algorithms <= {p+s for p in ("RS", "PS", "ES") for s in ("256", "384", "512")}:
            raise StartupRefused("CONFIGURATION", "Only RSA, RSA-PSS and ECDSA JWT algorithms are allowed")
        self.keys = keys
        self.loaded = time.time() if keys is not None else 0
        self.refreshed = 0
        self.lock = threading.RLock()
        if keys is not None and (not isinstance(keys, dict) or not isinstance(keys.get("keys"), list)):
            raise StartupRefused("CONFIGURATION", "A JWKS must contain a keys array")

    def _refresh(self):
        self.refreshed = time.time()
        try:
            status, body = request_url(self.url, "GET", {}, None, 10, 1048576)
            keys = loads(body)
            if status != 200 or not isinstance(keys, dict) or not isinstance(keys.get("keys"), list):
                return
            self.keys, self.loaded = keys, time.time()
        except (OSError, ValueError):
            pass

    def verify(self, bearer):
        try:
            return self._verify(bearer)
        except Exception:
            raise Rejected("Invalid bearer credential") from None

    def _verify(self, bearer):
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa, utils
        if not isinstance(bearer, str) or len(bearer) > 65536:
            raise ValueError()
        head, payload, signature = bearer.split(".")
        header, claims = loads(b64url_decode(head)), loads(b64url_decode(payload))
        alg = header.get("alg")
        if alg not in self.algorithms or "crit" in header:
            raise ValueError()
        with self.lock:
            at = time.time()
            if self.url and at-self.loaded > 3600 and at-self.refreshed >= 30:
                self._refresh()
            def select():
                return [k for k in (self.keys or {}).get("keys", []) if k.get("kid") == header.get("kid")
                        and k.get("use", "sig") == "sig" and k.get("alg", alg) == alg
                        and "verify" in k.get("key_ops", ["verify"])]
            candidates = select()
            if not candidates and self.url and at-self.refreshed >= 30:
                self._refresh()
                candidates = select()
            if len(candidates) != 1:
                raise ValueError()
            jwk = candidates[0]
        digest = {"256": hashes.SHA256, "384": hashes.SHA384, "512": hashes.SHA512}[alg[2:]]()
        integer = lambda field: int.from_bytes(b64url_decode(jwk[field]), "big")
        sig = b64url_decode(signature)
        data = (head+"."+payload).encode("ascii")
        if alg.startswith(("RS", "PS")) and jwk["kty"] == "RSA":
            key = rsa.RSAPublicNumbers(integer("e"), integer("n")).public_key()
            if key.key_size < 2048:
                raise ValueError()
            pad = padding.PKCS1v15() if alg.startswith("RS") else padding.PSS(mgf=padding.MGF1(digest), salt_length=digest.digest_size)
            key.verify(sig, data, pad, digest)
        elif alg.startswith("ES") and jwk["kty"] == "EC":
            curve, size = {"ES256": (ec.SECP256R1(), 32), "ES384": (ec.SECP384R1(), 48), "ES512": (ec.SECP521R1(), 66)}[alg]
            if jwk["crv"] != {"ES256": "P-256", "ES384": "P-384", "ES512": "P-521"}[alg] or len(sig) != size*2:
                raise ValueError()
            key = ec.EllipticCurvePublicNumbers(integer("x"), integer("y"), curve).public_key()
            sig = utils.encode_dss_signature(int.from_bytes(sig[:size], "big"), int.from_bytes(sig[size:], "big"))
            key.verify(sig, data, ec.ECDSA(digest))
        else:
            raise ValueError()
        at = time.time()
        numeric = lambda v: isinstance(v, (int, float)) and not isinstance(v, bool)
        if not isinstance(claims, dict) or not numeric(claims.get("exp")) or not numeric(claims.get("nbf")) \
                or claims["exp"] < at-60 or claims["nbf"] > at+60 or claims.get("iss") != self.issuer \
                or not isinstance(claims.get("sub"), str) or not claims["sub"]:
            raise ValueError()
        aud = claims.get("aud")
        if not (aud == self.audience or isinstance(aud, list) and self.audience in aud):
            raise ValueError()
        return VerifiedIdentity(self.issuer, claims["sub"], claims, dt.datetime.fromtimestamp(claims["exp"], dt.timezone.utc))


class IntrospectionVerifier(IdentityVerifier):
    def __init__(self, url, client_id, client_secret, issuer=None, audience=None):
        self.url, self.issuer, self.audience = https(url), issuer, audience
        self.authorization = "Basic " + base64.b64encode((client_id+":"+client_secret).encode()).decode()
        self.cache, self.lock = {}, threading.Lock()

    def verify(self, bearer):
        key = hashlib.sha256(bearer.encode()).digest()
        with self.lock:
            entry = self.cache.get(key)
            if entry and entry[0] > time.time():
                return entry[1]
        try:
            status, body = request_url(self.url, "POST", {"Authorization": self.authorization,
                "Content-Type": "application/x-www-form-urlencoded"}, urlencode({"token": bearer}), 10, 1048576)
            c = loads(body)
            at = time.time()
            exp = c.get("exp", at+300)
            if status != 200 or c.get("active") is not True or not isinstance(c.get("sub"), str) or not c["sub"] \
                    or not isinstance(c.get("iss", self.url), str) \
                    or not isinstance(exp, (int, float)) or isinstance(exp, bool) or exp <= at \
                    or self.issuer is not None and c.get("iss") != self.issuer \
                    or self.audience is not None and not (c.get("aud") == self.audience or
                        isinstance(c.get("aud"), list) and self.audience in c["aud"]):
                raise ValueError()
            identity = VerifiedIdentity(c.get("iss", self.url), c["sub"], c, dt.datetime.fromtimestamp(exp, dt.timezone.utc))
            with self.lock:
                self.cache = {k: v for k, v in self.cache.items() if v[0] > at}
                if len(self.cache) >= 4096:
                    self.cache.pop(next(iter(self.cache)))
                self.cache[key] = (min(exp, at+300), identity)
            return identity
        except Exception:
            raise Rejected("Invalid bearer credential") from None


def verifier_from_env(env, mode):
    settings = {k[14:]: v for k, v in env.items() if k.startswith("TOOLGATE_AUTH_")}
    if not settings:
        return None
    if mode is Mode.DEVELOPMENT:
        raise StartupRefused("CONFIGURATION", "Identity settings are refused in development mode")
    # len('TOOLGATE_AUTH_') is 14.
    sources = [k for k in ("JWKS_URL", "KEYS_FILE", "INTROSPECTION_URL") if settings.get(k)]
    if len(sources) != 1:
        raise StartupRefused("CONFIGURATION", "Configure exactly one identity key source")
    source = sources[0]
    try:
        if source == "INTROSPECTION_URL":
            return IntrospectionVerifier(settings[source], settings["CLIENT_ID"], settings["CLIENT_SECRET"],
                                         settings.get("ISSUER"), settings.get("AUDIENCE"))
        if not settings.get("ISSUER") or not settings.get("AUDIENCE"):
            raise ValueError("JWTs require issuer and audience")
        return JwtVerifier(settings["ISSUER"], settings["AUDIENCE"],
                           keys=json.loads(Path(settings[source]).read_text()) if source == "KEYS_FILE" else None,
                           jwks_url=settings[source] if source == "JWKS_URL" else None,
                           algorithms=settings.get("ALGORITHMS", "RS256,ES256").split(","))
    except (KeyError, OSError, ValueError) as exc:
        raise StartupRefused("CONFIGURATION", "Invalid identity configuration: " + str(exc))
