# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Build and pin the current trusted broker into a local rootless scratch image.

Owns the locked musl build; arbitrary prebuilt binaries are never accepted as
current-source evidence. No registry upload, workload identity or host mounts.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from sandbox import ROOT, GIT, source_digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cargo", default=shutil.which("cargo"))
    parser.add_argument("--podman", default="/usr/bin/podman")
    parser.add_argument("--output", type=Path, default=ROOT / "build/security/broker-image")
    args = parser.parse_args()
    if os.getuid() == 0:
        parser.error("the image must be built by the unprivileged service user")
    if not args.cargo or not Path(args.cargo).is_absolute() or not Path(args.podman).is_absolute():
        parser.error("absolute cargo and Podman executables required")
    engine_env = {k: os.environ[k] for k in ("HOME", "PATH", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS") if k in os.environ}
    engine_env.setdefault("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}")
    engine = [args.podman, "--remote=false", "--runtime=crun", "--cgroup-manager=cgroupfs"]
    info = json.loads(subprocess.check_output(engine + ["info", "--format=json"], env=engine_env))
    if info["host"]["security"]["rootless"] is not True or info["host"]["serviceIsRemote"] is not False:
        parser.error("a local rootless engine is required")
    before = source_digest()
    target = "x86_64-unknown-linux-musl"
    env = os.environ.copy()
    env.update(CARGO_BUILD_JOBS="2", CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER="musl-gcc", CC_x86_64_unknown_linux_musl="musl-gcc")
    subprocess.run([args.cargo, "build", "-p", "olo-toolgate-tool-host", "--bin", "olo-toolgate-tool-host",
                    "--release", "--locked", "--target", target], cwd=ROOT, env=env, check=True)
    binary = ROOT / "target" / target / "release/olo-toolgate-tool-host"
    headers = subprocess.check_output(["readelf", "--program-headers", str(binary)], text=True)
    if "INTERP" in headers:
        raise RuntimeError("a static broker binary is required")
    binary_digest = hashlib.sha256(binary.read_bytes()).hexdigest()
    tag = "localhost/olo-toolgate-invocation-broker:source-" + before[:24]
    with tempfile.TemporaryDirectory(prefix="toolgate-broker-image-") as directory:
        context = Path(directory)
        shutil.copyfile(binary, context / "broker")
        (context / "broker").chmod(0o755)
        shutil.copyfile("/etc/ssl/certs/ca-certificates.crt", context / "ca-certificates.crt")
        for name in ("LICENSE", "NOTICE.md"):
            shutil.copyfile(ROOT / name, context / name)
        (context / "work").mkdir()
        (context / "work/.keep").touch()
        subprocess.run(engine + ["build", "--format=oci", "--network=none", "--pull=never", "-t", tag,
                       "-f", str(ROOT / "apps/tool-host/packaging/broker.Containerfile"), str(context)], env=engine_env, check=True)
    image = json.loads(subprocess.check_output(engine + ["image", "inspect", tag], env=engine_env))[0]
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", image["Digest"]):
        raise RuntimeError("immutable image digest unavailable")
    reference = "localhost/olo-toolgate-invocation-broker@" + image["Digest"]
    subprocess.run(engine + ["image", "inspect", reference], env=engine_env, check=True, stdout=subprocess.DEVNULL)
    if before != source_digest():
        raise RuntimeError("source changed during broker image build; no qualification artifact emitted")
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"revision": subprocess.check_output(GIT + ["rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "sourceSha256": before, "binarySha256": binary_digest, "image": reference,
              "scope": "current-source image preparation; not runtime containment acceptance"}
    (args.output / "image.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (args.output / "image-ref.txt").write_text(reference + "\n", encoding="utf-8")
    print(reference)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
