#!/usr/bin/env python3
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Statelessness test for OLO Orgs (implementation prompt §6.2), with no sticky sessions anywhere.

  1. log in through replica A, create a publisher profile and upload a package through replica B;
  2. describe and publish through A, then read the listing and download the exact bytes through every replica;
  3. kill a replica mid-session and check that the user is still logged in on the others;
  4. scale to 3 replicas and back to 2 with no data loss.

  scripts/stateless-test.py --compose                       # the Compose stack (scripts/compose.sh up -d first)
  scripts/stateless-test.py --replica URL --replica URL     # any two replicas of one site; steps 3 and 4 skipped

Environment: MARKETPLACE_ADMIN_USER (admin) and MARKETPLACE_ADMIN_PASSWORD (default: deploy/secrets/admin_password),
MARKETPLACE_PUBLIC_HOST (Host header for replicas addressed by IP; default: the MARKETPLACE_BASE_URL host in
deploy/.env), TOOLGATE_CLI (default: <toolgate>/target/release/toolgate).
"""
import argparse
import hashlib
import html
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

MARKETPLACE_DIR = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
API = "/api/toolgate/v1"


def env_file():
    values = {}
    path = os.path.join(MARKETPLACE_DIR, "deploy", ".env")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            match = re.match(r"^([A-Z0-9_]+)=(.*)$", line.strip())
            if match:
                values[match.group(1)] = match.group(2).strip('"')
    return values


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Replica:
    """One replica, addressed directly. The session cookie is shared by every Replica (one browser)."""

    jar = {}

    def __init__(self, name, base, host=None, https=False):
        self.name, self.base, self.host, self.https = name, base.rstrip("/"), host, https
        self.opener = urllib.request.build_opener(NoRedirect, urllib.request.ProxyHandler({}))

    def request(self, path, data=None, content_type=None, follow=True):
        for _ in range(5):
            headers = {"User-Agent": "olo-orgs-stateless-test"}
            if self.host:
                headers["Host"] = self.host
            if self.https:
                headers["X-Forwarded-Proto"] = "https"
            if Replica.jar:
                headers["Cookie"] = "; ".join(f"{k}={v}" for k, v in Replica.jar.items())
            if content_type:
                headers["Content-Type"] = content_type
            request = urllib.request.Request(self.base + path, data=data, headers=headers)
            try:
                response = self.opener.open(request, timeout=60)
                status, body, location, cookies = response.status, response.read(), None, response.headers.get_all("Set-Cookie") or []
            except urllib.error.HTTPError as error:
                status, body, location, cookies = error.code, error.read(), error.headers.get("Location"), error.headers.get_all("Set-Cookie") or []
            for cookie in cookies:
                name, _, value = cookie.split(";", 1)[0].partition("=")
                if "deleted" == value or "Max-Age=0" in cookie:
                    Replica.jar.pop(name, None)
                else:
                    Replica.jar[name] = value
            if status in (301, 302, 303) and follow and location:
                parsed = urllib.parse.urlsplit(location)
                path = parsed.path + ("?" + parsed.query if parsed.query else "")
                data, content_type = None, None
                continue
            return status, body
        raise AssertionError(f"{self.name}: too many redirects")

    def get(self, path, expect=200):
        status, body = self.request(path)
        check(status == expect, f"{self.name} GET {path}: {status}, expected {expect}")
        return body.decode("utf-8", "replace") if expect == 200 and not path.endswith("/package") else body

    def submit(self, path, fields, button, files=None):
        page = self.get(path)
        form = next((f for f in re.findall(r"<form\b.*?</form>", page, re.S) if f'value="{button}"' in f), None)
        check(form is not None, f"{self.name} {path}: no form with a '{button}' button")
        values = dict(fields)
        for name in ("form_build_id", "form_token", "form_id"):
            match = re.search(rf'name="{name}" value="([^"]*)"', form) or re.search(rf'value="([^"]*)" name="{name}"', form)
            if match:
                values[name] = html.unescape(match.group(1))
        values["op"] = button
        boundary = "----olo" + secrets.token_hex(12)
        parts = []
        for name, value in values.items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
        for name, (filename, content) in (files or {}).items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'
                         f'Content-Type: application/octet-stream\r\n\r\n'.encode() + content + b"\r\n")
        body = b"".join(parts) + f"--{boundary}--\r\n".encode()
        status, response = self.request(path, body, f"multipart/form-data; boundary={boundary}")
        check(status == 200, f"{self.name} POST {path}: {status}")
        return response.decode("utf-8", "replace")

    def json(self, path, expect=200):
        status, body = self.request(path)
        check(status == expect, f"{self.name} GET {path}: {status}, expected {expect}")
        return json.loads(body)


def check(condition, message):
    if not condition:
        print(f"FAIL {message}", file=sys.stderr)
        sys.exit(1)


def step(message):
    print(f"--- {message}", flush=True)


def compose(*args, capture=True):
    command = [os.path.join(MARKETPLACE_DIR, "scripts", "compose.sh"), *args]
    environment = dict(os.environ, MARKETPLACE_SKIP_SEED_BUILD="true")
    result = subprocess.run(command, check=True, text=True, capture_output=capture, env=environment)
    return result.stdout if capture else ""


def compose_replicas(host, https):
    replicas = []
    for container in compose("ps", "-q", "drupal").split():
        info = json.loads(subprocess.run(["docker", "inspect", container], check=True, text=True, capture_output=True).stdout)[0]
        networks = info["NetworkSettings"]["Networks"]
        address = next(v["IPAddress"] for k, v in networks.items() if k.endswith("_web"))
        replicas.append(Replica(info["Name"].lstrip("/"), f"http://{address}:8080", host, https))
    return replicas


def wait_healthy(count, host, https):
    for _ in range(90):
        replicas = compose_replicas(host, https)
        healthy = [r for r in replicas if r.request("/health")[0] == 200] if len(replicas) == count else []
        if len(healthy) == count:
            return healthy
        time.sleep(2)
    check(False, f"{count} healthy Drupal replicas did not appear")


def pack(slug, version, toolgate_cli):
    template = json.load(open(os.path.join(MARKETPLACE_DIR, "helper", "src", "test", "resources", "fixture-manifest.json")))
    package_id = f"{slug}.notify"
    template["id"], template["version"] = package_id, version
    for tool in template["tools"]:
        tool_id = tool["descriptor"]["toolId"]
        tool["descriptor"]["toolId"] = package_id + tool_id[tool_id.index("/"):]
    work = tempfile.mkdtemp(prefix="olo-stateless-")
    os.makedirs(os.path.join(work, "artifacts"))
    json.dump(template, open(os.path.join(work, "toolgate-package.json"), "w"), indent=2)
    open(os.path.join(work, "artifacts", "tool.jar"), "w").write("not really a jar")
    out = os.path.join(work, f"{slug}-notify-{version}.tgpkg")
    subprocess.run([toolgate_cli, "pack", work, "-o", out], check=True, capture_output=True)
    data = open(out, "rb").read()
    shutil.rmtree(work)
    return package_id, data


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--compose", action="store_true")
    parser.add_argument("--replica", action="append", default=[])
    args = parser.parse_args()
    settings = env_file()
    base_url = os.environ.get("MARKETPLACE_BASE_URL", settings.get("MARKETPLACE_BASE_URL", "http://localhost"))
    host = os.environ.get("MARKETPLACE_PUBLIC_HOST") or urllib.parse.urlsplit(base_url).hostname
    https = base_url.startswith("https:")
    user = os.environ.get("MARKETPLACE_ADMIN_USER", settings.get("MARKETPLACE_ADMIN_USER", "admin"))
    password = os.environ.get("MARKETPLACE_ADMIN_PASSWORD")
    if password is None:
        password = open(os.path.join(MARKETPLACE_DIR, "deploy", "secrets", "admin_password")).read().strip()
    toolgate = subprocess.run([os.path.join(MARKETPLACE_DIR, "scripts", "toolgate-dir.sh")], check=True, text=True, capture_output=True).stdout.strip()
    toolgate_cli = os.environ.get("TOOLGATE_CLI", os.path.join(toolgate, "target", "release", "toolgate"))

    if args.compose:
        replicas = wait_healthy(2, host, https)
    else:
        check(len(args.replica) >= 2, "give --compose or at least two --replica URLs")
        explicit = os.environ.get("MARKETPLACE_PUBLIC_HOST")
        replicas = [Replica(f"replica-{i + 1}", url, explicit, https and bool(explicit)) for i, url in enumerate(args.replica)]
    a, b = replicas[0], replicas[1]

    step(f"log in on {a.name}")
    page = a.submit("/user/login", {"name": user, "pass": password}, "Log in")
    check("Log out" in page or "/user/logout" in page, f"{a.name}: login failed")

    step(f"publisher profile and upload on {b.name}")
    profile = b.get("/my/profile")
    if 'name="slug"' in profile:
        b.submit("/my/profile", {"slug": "statecheck", "display_name": "State check", "bio": "Statelessness test."}, "Create profile")
    slug = re.search(r"<code>([a-z0-9-]+)</code>", b.get("/my/profile"))
    check(slug is not None, f"{b.name}: no publisher profile")
    slug = slug.group(1)
    version = f"0.1.{int(time.time())}"
    package_id, data = pack(slug, version, toolgate_cli)
    page = b.submit("/publish", {}, "Upload and check", {"files[package]": (f"{slug}-notify-{version}.tgpkg", data)})
    check("All checks passed and the package is signed" in page, f"{b.name}: upload did not validate")
    draft = re.search(r"/publish/(\d+)/describe", page)
    check(draft is not None, f"{b.name}: no describe link after the checks")

    step(f"describe and publish on {a.name}")
    page = a.submit(f"/publish/{draft.group(1)}/describe", {
        "title": "State check notify", "summary": "Statelessness test package.", "description": "Published by stateless-test.py.",
        "category": "Operations", "tags": "test", "repository_url": ""}, "Publish")
    check(f"{package_id} {version} is published." in page, f"{a.name}: publish failed")

    step("listing and exact bytes through every replica")
    expected = hashlib.sha256(data).hexdigest()
    for replica in replicas:
        detail = replica.json(f"{API}/tools/{package_id}/{version}")
        check(detail["archiveSha256"] == expected, f"{replica.name}: archiveSha256 differs from the uploaded bytes")
        status, body = replica.request(f"{API}/tools/{package_id}/{version}/package")
        check(status == 200 and hashlib.sha256(body).hexdigest() == expected, f"{replica.name}: download differs")
        check(package_id in replica.get("/my/packages"), f"{replica.name}: session or package missing")

    if not args.compose:
        print("PASS (steps 3 and 4 need --compose)")
        return

    step(f"kill {a.name} mid-session")
    subprocess.run(["docker", "kill", a.name], check=True, capture_output=True)
    check(package_id in b.get("/my/packages"), f"{b.name}: user logged out after {a.name} died")
    compose("up", "-d", "--no-build", "drupal", capture=False)

    step("scale to 3 and back to 2")
    compose("up", "-d", "--no-build", "--no-recreate", "--scale", "drupal=3", "drupal", capture=False)
    for replica in wait_healthy(3, host, https):
        check(package_id in replica.get("/my/packages"), f"{replica.name}: session or package missing at 3 replicas")
    compose("up", "-d", "--no-build", "--no-recreate", "--scale", "drupal=2", "drupal", capture=False)
    for replica in wait_healthy(2, host, https):
        detail = replica.json(f"{API}/tools/{package_id}/{version}")
        check(detail["archiveSha256"] == expected, f"{replica.name}: package changed after scaling")
        check(package_id in replica.get("/my/packages"), f"{replica.name}: session lost after scaling")
    print("PASS")


if __name__ == "__main__":
    main()
