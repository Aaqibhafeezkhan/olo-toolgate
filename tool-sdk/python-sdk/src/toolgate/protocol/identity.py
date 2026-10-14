# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Adapter A4: who a request comes from. The verifiers themselves live in ``jwt`` and ``introspection``."""

from __future__ import annotations

import abc
import datetime as _dt
from typing import Any, Dict, Optional

__all__ = ["VerifiedIdentity", "IdentityVerifier", "Rejected"]


class VerifiedIdentity:
    """A verified caller: issuer, subject, every claim, and when the credential expires."""

    def __init__(self, issuer: str, subject: str, claims: Dict[str, Any], expires_at: Optional[_dt.datetime]) -> None:
        self.issuer = issuer
        self.subject = subject
        self.claims = claims
        self.expires_at = expires_at

    @property
    def owner(self) -> str:
        """The owner of the tasks and request states this caller creates."""
        return "identity:" + self.issuer + "|" + self.subject

    def __eq__(self, other: object) -> bool:
        return isinstance(other, VerifiedIdentity) and (self.issuer, self.subject, self.claims, self.expires_at) == \
            (other.issuer, other.subject, other.claims, other.expires_at)

    def __repr__(self) -> str:
        return "VerifiedIdentity(%r, %r)" % (self.issuer, self.subject)


class Rejected(Exception):
    """A refused credential. The transport answers 401 whatever the reason."""


class IdentityVerifier(abc.ABC):
    @abc.abstractmethod
    def verify(self, bearer: str) -> VerifiedIdentity:
        """Verifies one bearer credential, or raises :class:`Rejected`; the reason is for local diagnostics only."""
