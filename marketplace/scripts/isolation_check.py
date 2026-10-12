#!/usr/bin/env python3
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Isolation check (implementation prompt §1.2, §1.3).

The rule is about dependencies, not mentions. It reads build and package files, not prose:

- ToolGate -> marketplace/: no Gradle include/includeBuild, Cargo path or workspace member, npm workspace or file:
  dependency, Composer path repository, Dockerfile COPY/ADD source, Compose build context or volume, workflow step or
  shell source/exec/cd that reaches into the Marketplace folder. The one allowed exception is a release workflow step
  that runs marketplace/ci/publish-images.sh (§7).
- marketplace/ -> ToolGate: no path that leaves the Marketplace folder except through $TOOLGATE_DIR, which only
  scripts/toolgate-dir.sh resolves (from TOOLGATE_SRC).

Markdown, comments and http(s) URLs are ignored.

    isolation_check.py <MARKETPLACE_DIR> <TOOLGATE_DIR>
    isolation_check.py --self-test
"""
import json
import os
import re
import sys
import tempfile

ALLOWED_WORKFLOW_COMMAND = "bash marketplace/ci/publish-images.sh"
SKIP_DIRS = {".git", "node_modules", "build", "target", ".gradle", "vendor", "dist", ".dev", ".data"}
URL = re.compile(r"\b[a-z][a-z0-9+.-]*://\S+", re.I)


def strip_comments(text, style):
    """Removes comments so prose in them never counts. style: 'hash' or 'slash'."""
    out = []
    for line in text.splitlines():
        if style == "hash":
            stripped = line.lstrip()
            if stripped.startswith("#"):
                continue
            line = re.sub(r"\s#.*$", "", line)
        else:
            line = re.sub(r"(^|\s)//.*$", "", line)
        out.append(URL.sub("", line))
    return "\n".join(out)


def tokens(text):
    """Path-like tokens: quoted strings and bare words containing a slash or a dot-dot."""
    found = re.findall(r"\"([^\"\n]*)\"|'([^'\n]*)'", text)
    words = [a or b for a, b in found]
    unquoted = re.sub(r"\"[^\"\n]*\"|'[^'\n]*'", " ", text)
    words += re.findall(r"[^\s=,;()\[\]{}<>|&\"']+", unquoted)
    return [w for w in words if "/" in w or ".." in w or w == "marketplace"]


def inside(path, root):
    path = os.path.normpath(path)
    root = os.path.normpath(root)
    return path == root or path.startswith(root + os.sep)


class Checker:
    def __init__(self, marketplace, toolgate):
        self.marketplace = os.path.realpath(marketplace)
        self.toolgate = os.path.realpath(toolgate)
        self.problems = []

    def problem(self, path, message):
        self.problems.append(f"{os.path.relpath(path, self.toolgate)}: {message}")

    # ---- ToolGate -> marketplace/

    def into_marketplace(self, token, base_dirs):
        """True when a path token, resolved against any of base_dirs, lands in the Marketplace folder."""
        stripped = re.sub(r"^(\$\{?[A-Za-z_][A-Za-z0-9_]*\}?|\$\([^)]*\))/?", "", token)
        relative = stripped != token
        if stripped.startswith(")"):
            # The tail of a command substitution such as $(dirname "$x")/../y: its base is only known at run time.
            return False
        token = stripped
        token = token.split(":", 1)[-1] if token.startswith(("file:", "link:")) else token
        if not token or token.startswith("-"):
            return False
        if relative or token in (".", "..") or token.startswith(("./", "../")):
            # Explicitly relative: only against the file's own folder.
            base_dirs = base_dirs[:1]
        for base in base_dirs:
            if self.in_marketplace(os.path.join(base, token)):
                return True
        return False

    def in_marketplace(self, path):
        """In the Marketplace folder proper; after the split ToolGate itself sits inside it, at toolgate/."""
        if not inside(path, self.marketplace):
            return False
        split = inside(self.toolgate, self.marketplace)
        return not (split and inside(path, self.toolgate))

    def toolgate_files(self):
        for root, dirs, files in os.walk(self.toolgate):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS
                       and not self.in_marketplace(os.path.join(root, d))]
            for name in files:
                yield os.path.join(root, name)

    def check_toolgate(self):
        for path in self.toolgate_files():
            name = os.path.basename(path)
            here = os.path.dirname(path)
            bases = [here, self.toolgate]
            try:
                text = open(path, encoding="utf-8").read()
            except (UnicodeDecodeError, OSError):
                continue
            if name.endswith(".md"):
                continue
            if name.endswith((".gradle.kts", ".gradle")):
                body = strip_comments(text, "slash")
                for line in body.splitlines():
                    if re.search(r"\b(include|includeBuild|includeFlat|project|files|file|srcDir|from)\s*\(", line):
                        for t in tokens(line):
                            if self.into_marketplace(t, bases) or re.search(r"(^|[:/])marketplace([:/]|$)", t):
                                self.problem(path, f"Gradle build depends on the Marketplace: {line.strip()}")
            elif name == "Cargo.toml":
                body = strip_comments(text, "hash")
                for t in tokens(body):
                    if self.into_marketplace(t, bases):
                        self.problem(path, f"Cargo depends on the Marketplace: {t}")
            elif name in ("package.json", "composer.json"):
                try:
                    data = json.loads(text)
                except ValueError:
                    continue
                for t in self.json_strings(data):
                    if self.into_marketplace(t, bases):
                        self.problem(path, f"{name} depends on the Marketplace: {t}")
            elif name.startswith("Dockerfile") or name.endswith(".Dockerfile"):
                body = strip_comments(text, "hash")
                for line in body.splitlines():
                    if re.match(r"\s*(COPY|ADD)\b", line, re.I):
                        for t in tokens(line):
                            if self.into_marketplace(t, bases):
                                self.problem(path, f"Dockerfile copies from the Marketplace: {line.strip()}")
            elif name.endswith((".yml", ".yaml")):
                self.check_yaml(path, text, bases)
            elif name.endswith(".sh") or text.startswith("#!/usr/bin/env bash") or text.startswith("#!/bin/sh"):
                self.check_shell(path, text, bases, outbound=False)

    def json_strings(self, data):
        if isinstance(data, dict):
            for v in data.values():
                yield from self.json_strings(v)
        elif isinstance(data, list):
            for v in data:
                yield from self.json_strings(v)
        elif isinstance(data, str):
            yield data

    def check_yaml(self, path, text, bases):
        body = strip_comments(text, "hash")
        workflow = os.sep + os.path.join(".github", "workflows") + os.sep in path
        for line in body.splitlines():
            if ALLOWED_WORKFLOW_COMMAND in line and workflow:
                continue
            for t in tokens(line):
                if self.into_marketplace(t, bases):
                    self.problem(path, f"depends on the Marketplace: {line.strip()}")

    def check_shell(self, path, text, bases, outbound):
        body = strip_comments(text, "hash")
        for line in body.splitlines():
            if not re.search(r"(^|[\s;&|(])(source|\.|bash|sh|exec|cd|python3?|java|node)\s", line) and "./" not in line:
                continue
            for t in tokens(line):
                if outbound:
                    if self.leaves_marketplace(t, os.path.dirname(path)):
                        self.problem(path, f"reaches ToolGate without TOOLGATE_DIR: {line.strip()}")
                elif self.into_marketplace(t, bases):
                    self.problem(path, f"script depends on the Marketplace: {line.strip()}")

    # ---- marketplace/ -> ToolGate

    def leaves_marketplace(self, token, here):
        """True when a path token leaves the Marketplace folder other than through $TOOLGATE_DIR."""
        if re.match(r"^\$\{?TOOLGATE_DIR\b", token):
            return False
        if ".." not in token.split("/"):
            return False
        m = re.match(r"^\$\{?MARKETPLACE_DIR\}?/?(.*)$", token)
        if m:
            return not self.in_marketplace(os.path.join(self.marketplace, m.group(1)))
        # Any other leading variable ($here, $(dirname "$0"), ...) is taken as the file's own folder.
        rest = re.sub(r"^(\$\{?[A-Za-z_][A-Za-z0-9_]*\}?|\$\([^)]*\))/?", "", token)
        return not self.in_marketplace(os.path.join(here, rest))

    def marketplace_files(self):
        nested_toolgate = os.path.realpath(self.toolgate)
        for root, dirs, files in os.walk(self.marketplace):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS and d != "docs"
                       and os.path.realpath(os.path.join(root, d)) != nested_toolgate]
            for name in files:
                yield os.path.join(root, name)

    def check_marketplace(self):
        for path in self.marketplace_files():
            name = os.path.basename(path)
            here = os.path.dirname(path)
            if name.endswith(".md") or name == "TOOLGATE_SRC":
                continue
            try:
                text = open(path, encoding="utf-8").read()
            except (UnicodeDecodeError, OSError):
                continue
            if name.endswith(".sh") or text.startswith("#!/usr/bin/env bash"):
                self.check_shell(path, text, [here], outbound=True)
            elif name.startswith("Dockerfile"):
                for line in strip_comments(text, "hash").splitlines():
                    if re.match(r"\s*(COPY|ADD)\b", line, re.I) and re.search(r"(^|\s)\.\./", line):
                        self.problem(path, f"Dockerfile copies from outside its build context: {line.strip()}")
            elif name.endswith((".gradle.kts", ".gradle")):
                for line in strip_comments(text, "slash").splitlines():
                    for t in tokens(line):
                        if self.leaves_marketplace(t, here):
                            self.problem(path, f"Gradle reaches ToolGate without TOOLGATE_DIR: {line.strip()}")
            elif name.endswith((".yml", ".yaml")) or name in ("composer.json", "package.json"):
                for line in strip_comments(text, "hash").splitlines():
                    for t in tokens(line):
                        if self.leaves_marketplace(t, here):
                            self.problem(path, f"reaches ToolGate without TOOLGATE_DIR: {line.strip()}")

    def run(self):
        self.check_toolgate()
        self.check_marketplace()
        return self.problems


def self_test():
    """Forbidden cases must fail and allowed ones pass (§1.3)."""
    cases = [
        ("forbidden includeBuild", {"settings.gradle.kts": 'includeBuild("marketplace/helper")\n'}, False),
        ("forbidden COPY ../apps", {"marketplace/helper/Dockerfile": "FROM x\nCOPY ../apps /apps\n"}, False),
        ("forbidden script path", {"marketplace/scripts/x.sh": '#!/usr/bin/env bash\nsource "$here/../../apps/x.sh"\n'}, False),
        ("forbidden ToolGate script into marketplace", {"tools/x.sh": "#!/usr/bin/env bash\nbash marketplace/scripts/x.sh\n"}, False),
        ("README link passes", {"README.md": "See [the Marketplace](marketplace/README.md).\n"}, True),
        ("HTTP base URL passes", {"settings.gradle.kts": '// https://olo-labs.duckdns.org/marketplace/\nval u = "https://olo-labs.duckdns.org/marketplace/x"\n'}, True),
        ("TOOLGATE_DIR passes", {"marketplace/scripts/y.sh": '#!/usr/bin/env bash\ncd "$TOOLGATE_DIR/examples/java-tools"\n'}, True),
        ("split layout: ToolGate's own paths pass", {"toolgate/Cargo.toml": '[workspace]\nmembers = ["apps/gateway"]\n',
                                                   "toolgate/settings.gradle.kts": 'include("x")\nproject(":x").projectDir = file("apps/x")\n'}, True),
        ("split layout: reaching ToolGate around TOOLGATE_DIR fails", {"scripts/z.sh": '#!/usr/bin/env bash\nsource "$here/../toolgate/x.sh"\n'}, False),
        ("split layout: ToolGate scripts climbing within ToolGate pass", {"toolgate/examples/run.sh": '#!/usr/bin/env bash\nroot="$(cd "$here/.." && pwd)"\ncd "$(dirname "$p")/../../x"\n'}, True),
        ("publish step passes", {".github/workflows/r.yml": "steps:\n  - run: bash marketplace/ci/publish-images.sh\n"}, True),
    ]
    failed = 0
    for title, files, should_pass in cases:
        split = title.startswith("split layout")
        with tempfile.TemporaryDirectory() as root:
            # In-repo layout: ToolGate at root, Marketplace at root/marketplace. Split: the reverse.
            marketplace, toolgate = (root, os.path.join(root, "toolgate")) if split else (os.path.join(root, "marketplace"), root)
            os.makedirs(marketplace, exist_ok=True)
            os.makedirs(toolgate, exist_ok=True)
            for rel, content in files.items():
                full = os.path.join(root, rel)
                os.makedirs(os.path.dirname(full), exist_ok=True)
                with open(full, "w", encoding="utf-8") as f:
                    f.write(content)
            problems = Checker(marketplace, toolgate).run()
            ok = (not problems) == should_pass
            print(("ok   " if ok else "FAIL ") + title + ("" if ok else f": {problems}"))
            failed += 0 if ok else 1
    return failed == 0


def main(argv):
    if argv[1:] == ["--self-test"]:
        return 0 if self_test() else 1
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    problems = Checker(argv[1], argv[2]).run()
    for p in problems:
        print("isolation: " + p)
    print(f"isolation: {'FAILED' if problems else 'passed'} ({len(problems)} problems)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
