# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Declared route calls: one request, no redirects/retries; broker/test transport uses the same shape."""
from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import http.client
import ipaddress
import re
import socket
import ssl
import time
from urllib.parse import quote, urlencode, urlsplit

from .._json import dumpb, loads, now, text as json_text
from ..context import RawResponse
from ..descriptor.analysis import _VARIABLE, without_patterns
from ..errors import PlatformCode, PlatformError, ToolConfigurationError, UpstreamError
from .config import Mode
from .converter import from_json, to_json

FORBIDDEN = {"authorization", "proxy-authorization", "cookie", "host", "forwarded", "connection", "content-length",
             "transfer-encoding", "te", "trailer", "upgrade", "proxy-connection", "keep-alive"}


def refused(message):
    return PlatformError(PlatformCode.ROUTE_REFUSED, message)


def loopback(host):
    if host.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def destination_target(spec, config):
    value = config.destination(spec["hostSetting"])
    if not value:
        raise ToolConfigurationError("Missing destination " + spec["hostSetting"])
    plain = value.startswith("http://")
    authority = value[7:] if plain else value
    authority = authority.rstrip("/")
    if any(c in authority for c in "/@?#\\") or "://" in authority:
        raise ToolConfigurationError("A destination must be host or host:port")
    parsed = urlsplit("//" + authority)
    if not parsed.hostname:
        raise ToolConfigurationError("A destination must have a host")
    relaxed = config.mode is not Mode.PRODUCTION and loopback(parsed.hostname)
    if plain and not relaxed:
        raise ToolConfigurationError("Plain HTTP is allowed only on loopback in development")
    port = parsed.port or spec["port"]
    if port != spec["port"] and not relaxed:
        raise ToolConfigurationError("The destination port differs from its declaration")
    return parsed.hostname, port, plain


def request_url(url, method, headers, body, timeout, limit=4194304):
    parsed = urlsplit(url)
    if parsed.scheme not in {"http", "https"} or parsed.username or parsed.fragment:
        raise ToolConfigurationError("Invalid upstream URL")
    cls = http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
    conn = cls(parsed.hostname, parsed.port, timeout=timeout)
    try:
        conn.request(method, parsed.path + ("?" + parsed.query if parsed.query else ""), body, headers)
        response = conn.getresponse()
        data = response.read(limit + 1)
        if len(data) > limit:
            raise OSError("Upstream response exceeds its byte limit")
        return response.status, data
    finally:
        conn.close()


class HttpClient:
    def __init__(self, descriptor, config, context, exchanges=None):
        self.d = descriptor
        self.config = config
        self.context = context
        self.exchanges = exchanges
        self.routes = {r["id"]: r for r in descriptor["routes"]}
        self.destinations = {r["id"]: r for r in descriptor["destinations"]}
        self.auths = {r["id"]: r for r in descriptor["httpAuth"]}

    def route(self, route_id):
        if route_id not in self.routes:
            raise refused("Route " + route_id + " is not declared for this tool")
        return RouteCall(self, self.routes[route_id])

    def _convenience(self, method, destination_id, path_template):
        matches = [r for r in self.routes.values() if r["method"] == method and r["destination"] == destination_id
                   and without_patterns(r["path"]) == without_patterns(path_template)]
        if len(matches) != 1:
            raise refused("The convenience call must match exactly one declared route")
        return self.route(matches[0]["id"])

    def get(self, destination_id, path_template):
        return self._convenience("GET", destination_id, path_template)

    def post(self, destination_id, path_template):
        return self._convenience("POST", destination_id, path_template)

    def put(self, destination_id, path_template):
        return self._convenience("PUT", destination_id, path_template)

    def patch(self, destination_id, path_template):
        return self._convenience("PATCH", destination_id, path_template)

    def delete(self, destination_id, path_template):
        return self._convenience("DELETE", destination_id, path_template)

    def head(self, destination_id, path_template):
        return self._convenience("HEAD", destination_id, path_template)


class RouteCall:
    def __init__(self, client, route):
        self.client, self.route = client, route
        self.variables, self.queries, self.headers = {}, {}, {}
        self.patterns = {m.group(1): m.group(2)[1:] for m in _VARIABLE.finditer(route["path"])}
        self.data, self.content_type = None, None
        self.seconds = route["timeoutMs"] / 1000

    def path_var(self, name, value):
        text = json_text(to_json(value))
        decoded = text
        from urllib.parse import unquote
        for _ in range(3):
            decoded = unquote(decoded)
        if name not in self.patterns or value is None or not text or any(c in decoded for c in "/\\") \
                or decoded in {".", ".."} or re.fullmatch(self.patterns[name], text) is None:
            raise refused("Invalid path variable " + name)
        self.variables[name] = text
        return self

    def query(self, name, value):
        if name not in self.route["query"]:
            raise refused("Query name is not allowed: " + name)
        if value is not None:
            items = value if isinstance(value, (list, tuple)) else [value]
            self.queries.setdefault(name, []).extend(json_text(to_json(v)) for v in items if v is not None)
        return self

    def header(self, name, value):
        lower = name.lower()
        auth = self.client.auths.get(self.route.get("auth"), {})
        if lower in FORBIDDEN or lower.startswith("x-forwarded-") or lower == auth.get("header", "").lower() \
                or lower not in {h.lower() for h in self.route["headers"]}:
            raise refused("Header is not allowed: " + name)
        if value is None:
            self.headers.pop(lower, None)
        elif not isinstance(value, str) or "\r" in value or "\n" in value:
            raise refused("Invalid header value")
        else:
            self.headers[lower] = value
        return self

    def body(self, value):
        if self.route["method"] in {"GET", "HEAD"}:
            raise refused("This route takes no body")
        types = self.route["requestContentTypes"]
        json_type = next((t for t in types if t == "application/json" or t.endswith("+json")), None)
        if value is None:
            self.data, self.content_type = None, None
        elif isinstance(value, bytes) and types:
            self.data, self.content_type = value, types[0]
        elif json_type:
            self.data, self.content_type = dumpb(to_json(value)), json_type
        elif isinstance(value, str) and types:
            self.data, self.content_type = value.encode("utf-8"), types[0]
        else:
            raise refused("The body does not match the route's content types")
        if self.data is not None and len(self.data) > self.route["maxRequestBytes"]:
            raise refused("The body exceeds maxRequestBytes")
        return self

    def timeout(self, seconds):
        if seconds is not None and seconds > 0:
            self.seconds = min(self.route["timeoutMs"]/1000, float(seconds))
        return self

    def send(self, response_type):
        result = self.send_raw()
        if response_type is bytes:
            return result.body
        if response_type is str:
            return result.body.decode("utf-8")
        if response_type in (None, type(None)):
            return None
        return from_json(loads(result.body), response_type)

    async def send_async(self, response_type):
        return await asyncio.to_thread(self.send, response_type)

    def _idempotency(self):
        key = self.client.d["businessKey"]
        header = key.get("idempotencyHeader")
        if not header or header.lower() in self.headers or header.lower() not in {h.lower() for h in self.route["headers"]}:
            return
        ctx = self.client.context
        value = "i." + ctx.invocation_id
        if key.get("idempotencyScope") == "BUSINESS_KEY" and ctx._business_key:
            namespace = key["namespace"]
            if namespace == "TENANT":
                value = ctx._business_key
            elif hasattr(ctx, "_business_salt"):
                prefix = "u." if namespace == "DELEGATED_USER" else "a."
                value = prefix + hmac.new(ctx._business_salt, ctx._business_owner.encode(), hashlib.sha256).hexdigest() + "." + ctx._business_key
        self.headers[header.lower()] = value

    def _auth(self, host, plain):
        spec = self.client.auths.get(self.route.get("auth"))
        if not spec:
            return
        entry = self.client.context.secrets._entries.get(spec.get("secret", ""))
        secret = entry.value if entry is not None else None
        self.client.context._redactor.add(secret)
        kind = spec["type"]
        if kind == "API_KEY" and isinstance(secret, str):
            name, value = spec.get("header", "Authorization"), secret
        elif kind == "BASIC" and isinstance(secret, dict) and "username" in secret and "password" in secret:
            name, value = "Authorization", "Basic " + base64.b64encode((secret["username"]+":"+secret["password"]).encode()).decode()
        elif kind == "BASIC" and isinstance(secret, str) and ":" in secret:
            name, value = "Authorization", "Basic " + base64.b64encode(secret.encode()).decode()
        elif kind == "OAUTH2_CLIENT_CREDENTIALS" and isinstance(secret, dict):
            cache_key = hashlib.sha256(dumpb([spec, secret])).digest()
            with self.client.config.token_lock:
                cached = self.client.config.tokens.get(cache_key)
            if cached and cached[0] > time.time():
                self.client.context._redactor.add(cached[1])
                self.headers["Authorization"] = "Bearer " + cached[1]
                return
            url = secret.get("tokenUrl", "")
            parsed = urlsplit(url)
            if parsed.scheme != "https" and not (self.client.config.mode is not Mode.PRODUCTION and parsed.scheme == "http" and loopback(parsed.hostname or "")):
                raise ToolConfigurationError("OAuth tokenUrl must use HTTPS")
            credentials = quote(secret["clientId"], safe="") + ":" + quote(secret["clientSecret"], safe="")
            form = {"grant_type": "client_credentials"}
            if spec.get("scopes"):
                form["scope"] = " ".join(spec["scopes"])
            if spec.get("audience"):
                form["audience"] = spec["audience"]
            status, body = request_url(url, "POST", {"Authorization": "Basic " + base64.b64encode(credentials.encode()).decode(),
                                                       "Content-Type": "application/x-www-form-urlencoded"}, urlencode(form), self.seconds)
            token = loads(body)
            if status != 200 or not isinstance(token.get("access_token"), str) or token.get("token_type", "").lower() != "bearer":
                raise ToolConfigurationError("The token endpoint refused the credential")
            name, value = "Authorization", "Bearer " + token["access_token"]
            expires = token.get("expires_in", 300)
            if not isinstance(expires, (int, float)) or isinstance(expires, bool) or expires <= 0:
                raise ToolConfigurationError("Invalid OAuth token expiry")
            with self.client.config.token_lock:
                self.client.config.tokens = {k: v for k, v in self.client.config.tokens.items() if v[0] > time.time()}
                if len(self.client.config.tokens) < 1024:
                    self.client.config.tokens[cache_key] = (time.time()+max(0, expires-10), token["access_token"])
        else:
            raise ToolConfigurationError("Missing or unsupported route credential " + spec["id"])
        self.client.context._redactor.add(value)
        if "\r" in value or "\n" in value:
            raise ToolConfigurationError("Invalid credential header")
        self.headers[name] = value

    def send_raw(self):
        ctx, config = self.client.context, self.client.config
        if ctx.is_cancelled():
            raise PlatformError(PlatformCode.CANCELLED, "The invocation was cancelled")
        left = (ctx.deadline-now()).total_seconds()
        if left <= 0:
            raise PlatformError(PlatformCode.DEADLINE_EXCEEDED, "The invocation deadline passed")
        if set(self.variables) != set(self.patterns):
            raise refused("Every route path variable is required")
        self._idempotency()
        if config.mode.transported:
            if self.client.exchanges is None:
                raise refused("No recorded exchange answered the route call")
            request = {"route": self.route["id"], "method": self.route["method"], "destination": self.route["destination"],
                       "variables": dict(self.variables), "query": copy_queries(self.queries), "headers": dict(self.headers),
                       "auth": self.route.get("auth")}
            if self.data is not None:
                request["contentType"] = self.content_type
                if self.content_type == "application/json" or self.content_type.endswith("+json"):
                    request["body"] = loads(self.data)
                else:
                    request["bodyBase64"] = base64.b64encode(self.data).decode()
            response = self.client.exchanges(request)
            if "refused" in response:
                raise refused(str(response["refused"]))
            problem = response.get("failure")
            if problem:
                raise UpstreamError("UPSTREAM_TIMEOUT" if problem == "TIMEOUT_AFTER_SEND" else "UPSTREAM_CONNECTION",
                                    self.route["id"], effect_uncertain=problem in {"TIMEOUT_AFTER_SEND", "RESET_AFTER_SEND"})
            body = base64.b64decode(response["bodyBase64"], validate=True) if "bodyBase64" in response else (
                dumpb(response["body"]) if "body" in response else response.get("bodyText", "").encode("utf-8"))
            return self._answer(response.get("status", 200), response.get("headers", {}), body)
        host, port, plain = destination_target(self.client.destinations[self.route["destination"]], config)
        spec = self.client.destinations[self.route["destination"]]
        pins = spec.get("spkiPins", [])
        tls = ssl.create_default_context(cafile=spec.get("caBundle")) if not plain else None
        if pins:
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            tls.check_hostname = False
            tls.verify_mode = ssl.CERT_NONE
        seconds = min(self.seconds, left)
        conn = http.client.HTTPConnection(host, port, timeout=seconds) if plain else http.client.HTTPSConnection(host, port, timeout=seconds, context=tls)
        ctx.on_cancel(conn.close)
        sent = False
        try:
            self._auth(host, plain)
            path = _VARIABLE.sub(lambda m: quote(self.variables[m.group(1)], safe=""), self.route["path"])
            if self.queries:
                path += "?" + urlencode(self.queries, doseq=True)
            headers = {"User-Agent": "toolgate-sdk-python", **self.headers}
            if self.content_type:
                headers["Content-Type"] = self.content_type
            conn.connect()
            if pins:
                from cryptography import x509
                from cryptography.hazmat.primitives import serialization
                certificates = [x509.load_der_x509_certificate(conn.sock.getpeercert(binary_form=True))]
                # The public chain API is used where available; older Python exposes the same chain on its SSL object.
                chain = getattr(conn.sock, "get_unverified_chain", None) or getattr(conn.sock._sslobj, "get_unverified_chain", None)
                for entry in chain() if chain else []:
                    raw = entry if isinstance(entry, bytes) else entry.public_bytes()
                    certificates.append(x509.load_pem_x509_certificate(raw.encode() if isinstance(raw, str) else raw)
                                        if isinstance(raw, str) or raw.startswith(b"-----") else x509.load_der_x509_certificate(raw))
                actual = {"sha256/" + base64.b64encode(hashlib.sha256(cert.public_key().public_bytes(
                    serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).digest()).decode()
                          for cert in certificates}
                if not actual.intersection(pins):
                    raise ssl.SSLError("SPKI pin mismatch")
            conn.request(self.route["method"], path, self.data, headers)
            sent = True
            response = conn.getresponse()
            body = response.read(self.route["maxResponseBytes"] + 1)
            headers = {}
            for k, v in response.getheaders():
                headers.setdefault(k.lower(), []).append(v)
            return self._answer(response.status, headers, body)
        except (TimeoutError, socket.timeout):
            raise UpstreamError("UPSTREAM_TIMEOUT", self.route["id"], effect_uncertain=sent)
        except (OSError, http.client.HTTPException):
            if ctx.is_cancelled():
                raise PlatformError(PlatformCode.CANCELLED, "The invocation was cancelled")
            raise UpstreamError("UPSTREAM_CONNECTION", self.route["id"], effect_uncertain=sent)
        finally:
            conn.close()

    def _answer(self, status, headers, body):
        if len(body) > self.route["maxResponseBytes"]:
            raise UpstreamError("UPSTREAM_CONNECTION", self.route["id"], effect_uncertain=True)
        normalized = {k.lower(): [v] if isinstance(v, str) else list(v) for k, v in headers.items()}
        if not 200 <= status <= 299:
            request_id = normalized.get("x-request-id", normalized.get("request-id", [None]))[0]
            raise UpstreamError("UPSTREAM_5XX" if status >= 500 else "UPSTREAM_4XX", self.route["id"], status,
                                request_id, False, body[:4096].decode("utf-8", "replace"))
        return RawResponse(status, normalized, body)


def copy_queries(queries):
    return {k: list(v) for k, v in queries.items()}


class TcpConnector:
    def __init__(self, descriptor, config, context):
        self.destinations = {r["id"]: r for r in descriptor["tcp"]}
        self.config, self.context = config, context

    def connect(self, destination_id):
        if destination_id not in self.destinations:
            raise refused("The TCP destination is not declared for this tool")
        if self.config.mode.transported:
            raise refused("TCP is unavailable in test or governed mode")
        spec = self.destinations[destination_id]
        host, port, _ = destination_target(spec, self.config)
        if self.context.is_cancelled():
            raise PlatformError(PlatformCode.CANCELLED, "The invocation was cancelled")
        left = (self.context.deadline-now()).total_seconds()
        if left <= 0:
            raise PlatformError(PlatformCode.DEADLINE_EXCEEDED, "The invocation deadline passed")
        stream = socket.create_connection((host, port), timeout=min(left, 10))
        self.context.on_cancel(stream.close)
        return stream
