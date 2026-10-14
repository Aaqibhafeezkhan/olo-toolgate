# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The text forms of DATE, DATE_TIME, DURATION, URI and BINARY values, parsed and written as every SDK does.

The rules are those of the Java SDK, which the descriptor formats follow: ISO-8601 dates (``2026-10-11``), date-times
with an offset (``2026-10-11T10:00:00Z``), durations of days, hours, minutes and seconds (``PT1H30M``), absolute URIs,
and standard base64 with optional padding.
"""

from __future__ import annotations

import base64
import binascii
import datetime as _dt
import re
from decimal import Decimal

__all__ = ["parse_date", "parse_datetime", "parse_duration", "is_absolute_uri", "decode_base64", "format_date",
           "format_datetime", "format_duration", "FormatError"]


class FormatError(ValueError):
    pass


_DATE = re.compile(r"^([0-9]{4})-([0-9]{2})-([0-9]{2})$")
_DATETIME = re.compile(r"^([0-9]{4})-([0-9]{2})-([0-9]{2})[Tt]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\.([0-9]{0,9}))?)?"
                       r"([Zz]|[+-][0-9]{2}:[0-9]{2}(?::[0-9]{2})?)$")
_DURATION = re.compile(r"^([-+]?)P(?:([-+]?[0-9]+)D)?(T(?:([-+]?[0-9]+)H)?(?:([-+]?[0-9]+)M)?"
                       r"(?:([-+]?[0-9]+)(?:[.,]([0-9]{0,9}))?S)?)?$", re.IGNORECASE)
_SCHEME = re.compile(r"^[A-Za-z][A-Za-z0-9+.\-]*:")
_URI_ILLEGAL = re.compile(r"[\x00-\x20\"<>\\^`{|}\x7f]")
_BASE64 = re.compile(r"^[A-Za-z0-9+/]*={0,2}$")


def parse_date(s: str) -> _dt.date:
    m = _DATE.match(s)
    if not m:
        raise FormatError("not a date: " + s)
    try:
        return _dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError as e:
        raise FormatError(str(e))


def parse_datetime(s: str) -> _dt.datetime:
    m = _DATETIME.match(s)
    if not m:
        raise FormatError("not a date-time: " + s)
    y, mo, d, h, mi, sec, frac, offset = m.groups()
    micro = int((frac or "").ljust(9, "0")[:6] or "0")
    if offset in ("Z", "z"):
        tz = _dt.timezone.utc
    else:
        sign = -1 if offset[0] == "-" else 1
        parts = [int(x) for x in offset[1:].split(":")]
        hours, minutes, seconds = parts[0], parts[1], parts[2] if len(parts) > 2 else 0
        if hours > 18 or minutes > 59 or seconds > 59 or (hours == 18 and (minutes or seconds)):
            raise FormatError("offset out of range: " + offset)
        tz = _dt.timezone(sign * _dt.timedelta(hours=hours, minutes=minutes, seconds=seconds))
    try:
        return _dt.datetime(int(y), int(mo), int(d), int(h), int(mi), int(sec or 0), micro, tz)
    except ValueError as e:
        raise FormatError(str(e))


def parse_duration(s: str) -> _dt.timedelta:
    m = _DURATION.match(s)
    if not m:
        raise FormatError("not a duration: " + s)
    negate, days, t, hours, minutes, seconds, fraction = m.groups()
    if t is not None and t.upper() == "T" or (days is None and hours is None and minutes is None and seconds is None):
        raise FormatError("not a duration: " + s)
    total = Decimal(0)
    if days:
        total += Decimal(int(days)) * 86400
    if hours:
        total += Decimal(int(hours)) * 3600
    if minutes:
        total += Decimal(int(minutes)) * 60
    if seconds:
        whole = int(seconds)
        frac = Decimal("0." + fraction) if fraction else Decimal(0)
        total += Decimal(whole) + (-frac if seconds.startswith("-") else frac)
    if negate == "-":
        total = -total
    try:
        return _dt.timedelta(seconds=int(total), microseconds=int((total - int(total)) * 1_000_000))
    except OverflowError as e:
        raise FormatError(str(e))


def is_absolute_uri(s: str) -> bool:
    if not _SCHEME.match(s) or _URI_ILLEGAL.search(s):
        return False
    rest = s.split(":", 1)[1]
    if not rest:
        return False
    i = s.find("%")
    while i >= 0:
        if not re.match(r"%[0-9A-Fa-f]{2}", s[i:i + 3]):
            return False
        i = s.find("%", i + 1)
    if s.count("#") > 1:
        return False
    return True


def decode_base64(s: str) -> bytes:
    """Standard base64; the padding is optional, anything else outside the alphabet is refused."""
    if not _BASE64.match(s):
        raise FormatError("not valid base64")
    body = s.rstrip("=")
    if len(body) % 4 == 1 or (s.endswith("=") and len(s) % 4 != 0):
        raise FormatError("not valid base64")
    try:
        return base64.b64decode(body + "=" * (-len(body) % 4), validate=True)
    except binascii.Error as e:
        raise FormatError(str(e))


def format_date(d: _dt.date) -> str:
    return d.isoformat()


def _fraction(us: int) -> str:
    if us == 0:
        return ""
    if us % 1000 == 0:
        return ".%03d" % (us // 1000)
    return ".%06d" % us


def format_datetime(t: _dt.datetime) -> str:
    """As Java's OffsetDateTime.toString: seconds only when not zero, the offset as Z or +HH:MM."""
    if t.tzinfo is None:
        t = t.replace(tzinfo=_dt.timezone.utc)
    out = t.strftime("%Y-%m-%dT%H:%M")
    if t.second or t.microsecond:
        out += ":%02d" % t.second + _fraction(t.microsecond)
    offset = t.utcoffset() or _dt.timedelta(0)
    if offset == _dt.timedelta(0):
        return out + "Z"
    total = int(offset.total_seconds())
    sign = "-" if total < 0 else "+"
    total = abs(total)
    out += "%s%02d:%02d" % (sign, total // 3600, (total % 3600) // 60)
    if total % 60:
        out += ":%02d" % (total % 60)
    return out


def _tdiv(a: int, b: int) -> int:
    q = abs(a) // abs(b)
    return q if (a >= 0) == (b > 0) else -q


def _trem(a: int, b: int) -> int:
    return a - b * _tdiv(a, b)


def format_duration(d: _dt.timedelta) -> str:
    """As Java's Duration.toString: ``PT8H6M12.345S``, ``PT0S``, ``PT-0.5S``."""
    total_us = (d.days * 86400 + d.seconds) * 1_000_000 + d.microseconds
    if total_us == 0:
        return "PT0S"
    seconds, micros = divmod(total_us, 1_000_000)  # micros stay positive, as Java's nanos do
    nanos = micros * 1000
    effective = seconds + 1 if seconds < 0 and nanos > 0 else seconds
    hours = _tdiv(effective, 3600)
    minutes = _tdiv(_trem(effective, 3600), 60)
    secs = _trem(effective, 60)
    out = "PT"
    if hours:
        out += "%dH" % hours
    if minutes:
        out += "%dM" % minutes
    if secs == 0 and nanos == 0 and len(out) > 2:
        return out
    if seconds < 0 and nanos > 0:
        out += "-0" if secs == 0 else "%d" % secs
    else:
        out += "%d" % secs
    if nanos > 0:
        digits = str(2 * 1_000_000_000 - nanos if seconds < 0 else nanos + 1_000_000_000).rstrip("0")
        out += "." + digits[1:]
    return out + "S"
