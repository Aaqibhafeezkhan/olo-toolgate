# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Standalone configuration: ``TOOLGATE_MODE``, environment values and ``toolgate.dev.json`` (runtime-modes.md
sections 4 and 5)."""

from __future__ import annotations

import enum
import os
from pathlib import Path
from typing import Any, Callable, Dict, List, Mapping, Optional

from .._json import loads

__all__ = ["Mode", "Config", "StartupRefused"]


class StartupRefused(Exception):
    """The runtime refuses to start, with a fixed code (runtime-modes.md section 3, item 3)."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(code + ": " + message)
        self.code = code
        self.message = code + ": " + message


class Mode(enum.Enum):
    PRODUCTION = "production"
    DEVELOPMENT = "development"
    TEST = "test"
    #: One invocation under a Tool Host, over tool protocol v2: values and claims come from the host.
    GOVERNED = "governed"

    @property
    def fixtures(self) -> bool:
        """Whether claims come from fixtures: toolgate.dev.json in development, the harness in test."""
        return self in (Mode.DEVELOPMENT, Mode.TEST)

    @property
    def configured_claims(self) -> bool:
        """Whether claims come from this configuration rather than a verified credential on the request."""
        return self.fixtures or self is Mode.GOVERNED

    @property
    def transported(self) -> bool:
        """Whether route calls go to a transport in place of the network: recorded exchanges in test, the broker
        governed."""
        return self in (Mode.TEST, Mode.GOVERNED)


class Config:
    def __init__(self, mode: Mode, env: Mapping[str, str], dev_json: Optional[Dict[str, Any]]) -> None:
        self.mode = mode
        self.env: Dict[str, str] = dict(env)
        self.dev_json = dev_json
        self.warnings: List[str] = []
        self._secrets: Optional[Callable[[str], Any]] = None

    @classmethod
    def load(cls, env: Mapping[str, str], working_directory: os.PathLike) -> "Config":
        raw = env.get("TOOLGATE_MODE", "production").strip().lower()
        modes = {"production": Mode.PRODUCTION, "development": Mode.DEVELOPMENT, "test": Mode.TEST}
        if raw not in modes:
            raise StartupRefused("CONFIGURATION", "TOOLGATE_MODE must be production or development, not " + raw)
        mode = modes[raw]
        file = Path(env["TOOLGATE_DEV_JSON"]) if "TOOLGATE_DEV_JSON" in env \
            else Path(working_directory) / "toolgate.dev.json"
        dev = None
        if file.is_file():
            try:
                dev = loads(file.read_bytes())
            except (ValueError, OSError):
                raise StartupRefused("CONFIGURATION", str(file) + " is not valid JSON")
            if not isinstance(dev, dict):
                raise StartupRefused("CONFIGURATION", str(file) + " must hold a JSON object")
        config = cls(mode, env, dev)
        if dev is not None and "claims" in dev and not mode.fixtures:
            config.warnings.append("toolgate.dev.json has claims, which are read only with TOOLGATE_MODE=development;"
                                   " ignoring them")
        return config

    @classmethod
    def test(cls, fixtures: Optional[Dict[str, Any]]) -> "Config":
        """Test mode: the harness fixtures, in the shape of toolgate.dev.json, and no environment."""
        return cls(Mode.TEST, {}, fixtures)

    @classmethod
    def governed(cls, values: Optional[Dict[str, Any]], secrets: Callable[[str], Any]) -> "Config":
        """Governed mode: ``values`` holds the globals and claims the Tool Host sent; ``secrets`` redeems a declared
        secret through the host, or returns None."""
        config = cls(Mode.GOVERNED, {}, values)
        config._secrets = secrets
        return config

    def _dev(self, section: str) -> Dict[str, Any]:
        v = self.dev_json.get(section) if self.dev_json is not None else None
        return v if isinstance(v, dict) else {}

    def global_value(self, name: str) -> Any:
        """A global from TOOLGATE_GLOBAL_<NAME> (as text, typed later) or the dev file; None when neither sets it."""
        from_env = self.env.get("TOOLGATE_GLOBAL_" + name.upper())
        if from_env is not None:
            return from_env
        return self._dev("globals").get(name)

    def secret(self, name: str) -> Any:
        if self._secrets is not None:
            return self._secrets(name)
        from_env = self.env.get("TOOLGATE_SECRET_" + name.upper())
        if from_env is not None:
            return from_env
        return self._dev("secrets").get(name)

    def destination(self, host_setting: str) -> Optional[str]:
        from_env = self.env.get("TOOLGATE_DESTINATION_" + host_setting.upper())
        if from_env is not None:
            return from_env
        v = self._dev("destinations").get(host_setting)
        return v if isinstance(v, str) else None

    def claims(self) -> Optional[Dict[str, Any]]:
        """Fixture claims in development and test mode, and the host's claims in governed mode."""
        if self.mode.configured_claims and self.dev_json is not None and isinstance(self.dev_json.get("claims"), dict):
            return self.dev_json["claims"]
        return None
