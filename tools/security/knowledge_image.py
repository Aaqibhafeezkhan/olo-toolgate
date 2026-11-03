# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Build an owned knowledge fixture image bound to current committed or stage source."""
import argparse
import json
from pathlib import Path
import subprocess

from knowledge import ROOT, runtime_source_digest, stack_image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/security/knowledge-image")
    args = parser.parse_args()
    before = runtime_source_digest()
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    version = (ROOT / "VERSION").read_text().strip()
    suffix = before[:24]
    control = "olo-toolgate-control:knowledge-source-" + suffix
    image = "olo-toolgate-quickstart:knowledge-source-" + suffix
    common = ["--build-arg", "VERSION=" + version, "--build-arg", "REVISION=" + revision]
    subprocess.run(["docker", "build", "-f", "apps/control-plane/Dockerfile", "-t", control,
                    *common, "."], cwd=ROOT, check=True)
    subprocess.run(["docker", "build", "-f", "apps/quickstart/Dockerfile", "-t", image,
                    *common, "--build-arg", "CONTROL_IMAGE=" + control,
                    "--label", "io.olo.qualification.owner=olo-knowledge",
                    "--label", "io.olo.qualification.runtime-source-sha256=" + before,
                    "."], cwd=ROOT, check=True)
    if runtime_source_digest() != before:
        raise ValueError("Runtime source changed during image build; no qualification evidence emitted")
    selected = stack_image(image)
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"revision": revision, "runtimeSourceSha256": before, "image": image,
              "imageId": selected["Id"], "scope": "owned image preparation; no runtime or stack acceptance"}
    (args.output / "image.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(image)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
