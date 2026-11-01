#!/usr/bin/env python3
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Validate repository-relative Markdown links without making network requests."""
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
TICK = chr(96)
FENCE = re.compile(r"^ {0,3}(" + re.escape(TICK) + r"{3,}|~{3,})(.*)$")
REFERENCE = re.compile(r"^ {0,3}\[([^\]]+)\]:[ \t]*(?:<([^>]+)>|(\S+))", re.M)
REF_USE = re.compile(r"! ?\[([^\]]+)\](?:\[([^\]]*)\])?".replace("! ?", "!?"))
LINK_START = re.compile(r"! ?\[[^\]]*\]\(".replace("! ?", "!?"))
HTML_ATTR = re.compile(r"""(?:href|src)\s*=\s*["']([^"']+)["']""", re.I)
ATX_HEADING = re.compile(r"^ {0,3}#{1,6}[ \t]+(.+?)[ \t]*#*[ \t]*$")
SETEXT_UNDERLINE = re.compile(r"^ {0,3}(=+|-+)[ \t]*$")
EXPLICIT_ID = re.compile(r"""<(?:a|[^>]+)\s+[^>]*id=["']([^"']+)["']""", re.I)


def clean_markdown(text):
    """Blank fenced code blocks without changing source offsets or line numbers."""
    output, fence_char, fence_size = [], None, 0
    for line in text.splitlines(keepends=True):
        content = line.rstrip("\r\n")
        newline = line[len(content):]
        match = FENCE.match(content)
        if fence_char is None:
            if match:
                token, _info = match.groups()
                fence_char, fence_size = token[0], len(token)
                output.append(newline)
            else:
                output.append(line)
            continue
        if (match and match.group(1)[0] == fence_char
                and len(match.group(1)) >= fence_size
                and not match.group(2).strip()):
            fence_char, fence_size = None, 0
        output.append(newline)
    return "".join(output)


def mask_inline_code(text):
    """Hide inline-code spans from link scanning while preserving every offset."""
    chars = list(text)
    runs = list(re.finditer(re.escape(TICK) + r"+", text))
    index = 0
    while index < len(runs):
        opening = runs[index]
        size = len(opening.group())
        closing_index = index + 1
        while closing_index < len(runs) and len(runs[closing_index].group()) != size:
            closing_index += 1
        if closing_index == len(runs):
            index += 1
            continue
        closing = runs[closing_index]
        for position in range(opening.start(), closing.end()):
            if chars[position] not in "\r\n":
                chars[position] = " "
        index = closing_index + 1
    return "".join(chars)


def slug(text):
    text = re.sub(r"<[^>]+>", "", text).strip().lower()
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


def heading_ids(path):
    try:
        text = clean_markdown(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError):
        return set()
    ids, counts = set(EXPLICIT_ID.findall(text)), {}
    lines = text.splitlines()
    headings = []
    for index, line in enumerate(lines):
        match = ATX_HEADING.match(line)
        if match:
            headings.append(match.group(1))
        elif (line.strip() and index + 1 < len(lines)
              and SETEXT_UNDERLINE.match(lines[index + 1])):
            headings.append(line.strip())
    for heading in headings:
        base = slug(heading)
        number = counts.get(base, 0)
        counts[base] = number + 1
        ids.add(base if number == 0 else f"{base}-{number}")
    return ids


def inline_link_targets(text):
    """Yield inline-link destinations, balancing nested parentheses in paths."""
    for match in LINK_START.finditer(text):
        index = match.end()
        while index < len(text) and text[index].isspace():
            index += 1
        if index >= len(text):
            continue
        if text[index] == "<":
            start = index + 1
            index = start
            while index < len(text):
                if text[index] == "\\":
                    index += 2
                    continue
                if text[index] == ">":
                    break
                index += 1
            if index >= len(text):
                continue
            destination = text[start:index]
            index += 1
        else:
            start, depth = index, 0
            while index < len(text):
                char = text[index]
                if char == "\\":
                    index += 2
                    continue
                if char == "(":
                    depth += 1
                elif char == ")":
                    if depth == 0:
                        break
                    depth -= 1
                elif char.isspace() and depth == 0:
                    break
                index += 1
            destination = text[start:index]
        while index < len(text) and text[index].isspace():
            index += 1
        if index < len(text) and text[index] == ")":
            yield re.sub(r"\\([()\\])", r"\1", destination), match.start()
            continue
        if index < len(text) and text[index] in "\"'":
            quote = text[index]
            index += 1
            while index < len(text):
                if text[index] == "\\":
                    index += 2
                    continue
                if text[index] == quote:
                    index += 1
                    break
                index += 1
            while index < len(text) and text[index].isspace():
                index += 1
            if index < len(text) and text[index] == ")":
                yield re.sub(r"\\([()\\])", r"\1", destination), match.start()


def reference_link_targets(text):
    """Yield destinations only for rendered uses of defined reference links."""
    definitions = {
        match.group(1).strip().lower(): (match.group(2) or match.group(3))
        for match in REFERENCE.finditer(text)
    }
    definition_lines = []
    for match in REFERENCE.finditer(text):
        end = text.find("\n", match.start())
        definition_lines.append((match.start(), len(text) if end < 0 else end + 1))
    for match in REF_USE.finditer(text):
        if any(start <= match.start() < end for start, end in definition_lines):
            continue
        if match.end() < len(text) and text[match.end()] == "(":
            continue
        label = (match.group(2) or match.group(1)).strip().lower()
        if label in definitions:
            yield definitions[label], match.start()


def markdown_files():
    roots = [ROOT / "docs"]
    roots.extend(ROOT / name for name in ("README.md", "CONTRIBUTING.md", "ARCHITECTURE.md", "SECURITY.md"))
    files = set()
    for item in roots:
        if item.is_file() and item.suffix.lower() == ".md":
            files.add(item)
        elif item.is_dir():
            files.update(path for path in item.rglob("*.md") if ".git" not in path.parts)
    return sorted(files)


def main():
    files, errors = markdown_files(), []
    for source in files:
        try:
            text = clean_markdown(source.read_text(encoding="utf-8"))
        except (OSError, UnicodeError) as error:
            errors.append(f"{source.relative_to(ROOT)}: cannot read Markdown: {error}")
            continue
        scan_text = mask_inline_code(text)
        candidates = list(inline_link_targets(scan_text))
        candidates.extend((match.group(1), match.start())
                          for match in HTML_ATTR.finditer(scan_text))
        candidates.extend(reference_link_targets(scan_text))
        for raw_url, offset in candidates:
            if not raw_url:
                continue
            url = raw_url.strip().replace("&amp;", "&")
            parsed = urlsplit(url)
            if parsed.scheme or parsed.netloc or url.startswith("//"):
                continue
            target, fragment = unquote(parsed.path), unquote(parsed.fragment)
            target_path = (source if not target else
                           ROOT / target.lstrip("/") if target.startswith("/") else
                           source.parent / target)
            target_path = target_path.resolve()
            if target_path.is_dir():
                target_path = next((target_path / name for name in ("README.md", "index.md")
                                    if (target_path / name).is_file()), target_path / "README.md")
            if not target_path.is_file():
                line = text.count("\n", 0, offset) + 1
                errors.append(f"{source.relative_to(ROOT)}:{line}: broken link target '{url}'")
                continue
            if (fragment and target_path.suffix.lower() == ".md"
                    and fragment not in heading_ids(target_path)):
                line = text.count("\n", 0, offset) + 1
                errors.append(f"{source.relative_to(ROOT)}:{line}: missing anchor '{fragment}' in '{url.split('#', 1)[0] or target_path.name}'")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        print(f"Markdown link check failed: {len(errors)} issue(s) across {len(files)} file(s).", file=sys.stderr)
        return 1
    print(f"Markdown link check passed: {len(files)} files, all relative links resolve.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
