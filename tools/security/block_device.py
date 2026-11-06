# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Resolve the actual rootless container-store device for mandatory cgroup I/O limits."""
import json
import os
from pathlib import Path
import re
import subprocess


def container_storage_path(account):
    environment = {
        "HOME": account.pw_dir,
        "XDG_RUNTIME_DIR": f"/run/user/{account.pw_uid}",
        "PATH": "/usr/local/bin:/usr/bin:/bin",
    }
    command = ["/usr/bin/podman", "--remote=false", "--runtime=crun",
               "--cgroup-manager=cgroupfs", "info", "--format=json"]
    if os.geteuid() != account.pw_uid:
        if os.geteuid() != 0:
            raise ValueError("container storage must be inspected as its service account")
        command = ["/usr/sbin/runuser", "-u", account.pw_name, "--", "/usr/bin/env", "-i",
                   *[key + "=" + value for key, value in environment.items()], *command]
    try:
        result = subprocess.run(command, env=environment, capture_output=True, text=True,
                                check=True, timeout=10)
        if len(result.stdout) > 1024 * 1024:
            raise ValueError("container storage metadata is oversized")
        info = json.loads(result.stdout)
        if not isinstance(info, dict):
            raise ValueError("container storage metadata must be an object")
        host = info.get("host", {})
        if not isinstance(host, dict) or not isinstance(host.get("security"), dict):
            raise ValueError("container storage host metadata is missing")
        if host["security"].get("rootless") is not True or host.get("serviceIsRemote") is not False:
            raise ValueError("local rootless container storage required")
        store = info.get("store")
        if not isinstance(store, dict):
            raise ValueError("container storage metadata is missing")
        graph_root = store.get("graphRoot")
        if not isinstance(graph_root, str) or not Path(graph_root).is_absolute():
            raise ValueError("absolute container storage path required")
        path = Path(graph_root).resolve(strict=True)
        if not path.is_dir():
            raise ValueError("container storage directory required")
        return path
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        raise ValueError("container storage inspection failed") from error


def _whole_device(reference, sysfs):
    if not re.fullmatch(r"[1-9][0-9]*:[0-9]+", reference):
        raise ValueError("a real block device is required; virtual filesystems need explicit --io-device")
    try:
        root = Path(sysfs).resolve(strict=True)
        node = (root / "dev/block" / reference).resolve(strict=True)
        if not node.is_relative_to(root) or (node / "dev").read_text().strip() != reference:
            raise ValueError("block-device topology does not match its device number")
        if (node / "partition").is_file():
            node = node.parent
        device = (node / "dev").read_text().strip()
        if (not re.fullmatch(r"[1-9][0-9]*:[0-9]+", device)
                or (node / "partition").exists() or not (node / "queue").is_dir()):
            raise ValueError("whole block device with an I/O queue required")
        return device
    except OSError as error:
        raise ValueError("backing block device unavailable; specify a verified whole --io-device") from error


def io_device_for_storage(storage, explicit=None, *, sysfs=Path("/sys")):
    if explicit is not None:
        device = _whole_device(explicit, sysfs)
        if device != explicit:
            raise ValueError(f"--io-device names a partition; use its whole block device {device}")
        return device
    number = Path(storage).stat().st_dev
    return _whole_device(f"{os.major(number)}:{os.minor(number)}", sysfs)
