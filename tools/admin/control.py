# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Authenticated Control API client for operator scripts; no Docker or database access."""
import argparse
import http.client
import json
from pathlib import Path
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

MAX_JSON = 2 * 1024 * 1024
MAX_PACKAGE = 64 * 1024 * 1024


class Refused(ValueError):
    pass


class HttpRefused(Refused):
    def __init__(self,status):
        self.status=status
        super().__init__('Control refused the request (HTTP ' + str(status) + '); no automatic retry')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        fp.close()
        raise Refused('API redirects are refused; configure the final trusted origin')


def read_file(path, maximum):
    with Path(path).open('rb') as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise Refused('Input exceeds the operation size limit')
    return data


def api_origin(value):
    parsed = urllib.parse.urlsplit(value)
    if (any(character.isspace() for character in value) or parsed.scheme != 'https' or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.path not in ('', '/') or parsed.query or parsed.fragment):
        raise Refused('Use an HTTPS origin without credentials, path, query or fragment')
    # Validate malformed ports before loading credentials.
    parsed.port
    return value.rstrip('/')


def api_path(value):
    # Never allow a URL, traversal or encoded path to redirect bearer credentials.
    if not re.fullmatch(r'/api/control/v1/[A-Za-z0-9_./:{}-]+(?:\?[A-Za-z0-9_.~=%&:+/-]+)?', value):
        raise Refused('Use an absolute /api/control/v1/ path')
    path = value.split('?', 1)[0]
    if any(part in ('', '.', '..') for part in path.split('/')[1:]):
        raise Refused('Invalid API path')
    return value


class Control:
    def __init__(self, origin, token_file, ca_file=None):
        self.origin = api_origin(origin)
        token = read_file(token_file, 16384).decode('ascii').strip()
        if not re.fullmatch(r'[A-Za-z0-9._~-]+', token):
            raise Refused('Invalid bearer token file')
        context = ssl.create_default_context(cafile=str(ca_file) if ca_file else None)
        self.token = token
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context), NoRedirect())

    def request(self, method, path, data=None, content_type='application/json', key=None, revision=None):
        path = api_path(path)
        if method not in ('GET', 'POST', 'PUT', 'PATCH', 'DELETE') or method == 'GET' and data is not None:
            raise Refused('Invalid request method or GET body')
        headers = {'Authorization': 'Bearer ' + self.token, 'Accept': 'application/json'}
        if data is not None:
            headers['Content-Type'] = content_type
        if key is not None:
            if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}', key):
                raise Refused('Invalid idempotency key')
            headers['Idempotency-Key'] = key
        if revision is not None:
            if isinstance(revision,str) and re.fullmatch(r'W/"[1-9][0-9]{0,15}"',revision):
                headers['If-Match'] = revision
            elif isinstance(revision,int) and not isinstance(revision,bool) and 0 <= revision <= 9007199254740991:
                headers['If-Match'] = '"' + str(revision) + '"'
            else:
                raise Refused('Invalid expected revision')
        request = urllib.request.Request(self.origin + path, data, headers, method=method)
        try:
            with self.opener.open(request, timeout=120) as response:
                status = response.status
                raw = response.read(MAX_JSON + 1)
        except urllib.error.HTTPError as error:
            # Do not echo bodies, URLs or headers: a server can reflect secrets.
            status = error.code
            error.close()
            raise HttpRefused(status) from None
        except (urllib.error.URLError, TimeoutError, OSError, http.client.HTTPException):
            raise Refused('Control transport failed; outcome may be unknown; no automatic retry') from None
        if len(raw) > MAX_JSON or status not in (200, 201, 202, 204):
            raise Refused('Unexpected Control response')
        if status == 204:
            if raw:
                raise Refused('Unexpected response body')
            return status, None
        try:
            body = json.loads(raw)
        except (UnicodeError, ValueError):
            raise Refused('Control returned invalid JSON') from None
        if not isinstance(body, (dict, list)):
            raise Refused('Control returned an invalid document')
        return status, body


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', required=True, help='Control HTTPS origin')
    parser.add_argument('--token-file', required=True, type=Path, help='Private administrator bearer token file')
    parser.add_argument('--ca-file', type=Path, help='Trusted CA PEM; system trust is used if omitted')
    parser.add_argument('--method', choices=('GET', 'POST', 'PUT', 'PATCH', 'DELETE'), default='GET')
    parser.add_argument('--path', required=True, help='Canonical /api/control/v1/ endpoint, including query if needed')
    inputs = parser.add_mutually_exclusive_group()
    inputs.add_argument('--json-file', type=Path)
    inputs.add_argument('--package-file', type=Path, help='Exact .olotool archive for packages/uploads')
    parser.add_argument('--idempotency-key', help='Keep this key for an exact retry; never retried automatically')
    parser.add_argument('--revision', type=int, help='Current expected revision for If-Match')
    args = parser.parse_args(argv)
    try:
        api_origin(args.origin)
        api_path(args.path)
        data = None
        content_type = 'application/json'
        if args.method=='PATCH':content_type='application/merge-patch+json'
        if args.json_file:
            data = read_file(args.json_file, MAX_JSON)
            if not isinstance(json.loads(data), (dict, list)):
                raise Refused('JSON input must be an object or array')
        if args.package_file:
            if (args.package_file.suffix != '.olotool' or args.method != 'POST'
                    or args.path != '/api/control/v1/packages/uploads'):
                raise Refused('Package uploads require POST packages/uploads and an .olotool archive')
            data = read_file(args.package_file, MAX_PACKAGE)
            content_type = 'application/vnd.ololabs.toolgate.package+zip'
        if args.method == 'GET' and data is not None:
            raise Refused('GET cannot have an input document')
        client = Control(args.origin, args.token_file, args.ca_file)
        status, body = client.request(args.method, args.path, data, content_type, args.idempotency_key, args.revision)
        # Explicit stdout is suitable for private redirection. Never print credentials to stderr.
        print(json.dumps({'httpStatus': status, 'body': body}, ensure_ascii=True))
        return 0
    except Refused as error:
        print(str(error), file=sys.stderr)
        return 1
    except (ValueError, OSError, UnicodeError):
        # Even malformed local input exceptions can include private file contents.
        print('Administrative request failed. Check the trusted origin, private input files, authority and expected revision. No automatic retry.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
