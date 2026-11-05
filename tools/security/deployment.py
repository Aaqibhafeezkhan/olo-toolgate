# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Owned systemd deployment crash/restart qualification for the declared OCI tier.

Runs only temporary named test units, as the unprivileged image-owning account.
It never deploys a production service, uses workload credentials or calls Control.
Observed local timing ceilings are not production revocation SLOs.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import time
import uuid

from sandbox import ROOT, GIT, source_digest
from block_device import container_storage_path, io_device_for_storage


def run(command, **kwargs):
    kwargs.setdefault("timeout", 30)
    return subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, **kwargs).stdout


def until(action, seconds=25):
    deadline = time.monotonic() + seconds
    while True:
        value = action()
        if value:
            return value
        if time.monotonic() >= deadline:
            raise RuntimeError("owned deployment observation timed out")
        time.sleep(0.025)


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return None


def process(pid):
    try:
        stat = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
        status = dict(line.split(":", 1) for line in Path(f"/proc/{pid}/status").read_text().splitlines() if ":" in line)
        return {"pid": pid, "startTicks": stat[19], "state": stat[0],
                "uid": int(status["Uid"].split()[0]), "gid": int(status["Gid"].split()[0]),
                "sigIgn": int(status["SigIgn"].strip(), 16),
                "cgroup": Path(f"/proc/{pid}/cgroup").read_text().strip(),
                "namespaces": {name: os.readlink(f"/proc/{pid}/ns/{name}") for name in ("user", "net", "pid", "mnt")}}
    except (FileNotFoundError, ProcessLookupError):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service-user", required=True)
    parser.add_argument("--cargo", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--broker-image", required=True)
    parser.add_argument("--broker-image-evidence", type=Path, required=True)
    parser.add_argument("--io-device", help="whole-block major:minor backing rootless container storage")
    parser.add_argument("--iterations", type=int, default=3)
    parser.add_argument("--output", type=Path, default=ROOT / "build/security/deployment")
    args = parser.parse_args()
    if os.geteuid() != 0 or platform.system() != "Linux" or platform.machine() != "x86_64":
        parser.error("an administrator must launch the Linux x86_64 owned drill")
    import pwd
    account = pwd.getpwnam(args.service_user)
    if account.pw_uid == 0 or not 3 <= args.iterations <= 10 or not Path(args.cargo).is_absolute():
        parser.error("non-root service account, absolute cargo and 3-10 iterations required")
    for reference in (args.image, args.broker_image):
        if not re.fullmatch(r"[A-Za-z0-9./_:-]+@sha256:[a-f0-9]{64}", reference):
            parser.error("prepared immutable image references required")
    try:
        storage = container_storage_path(account)
        io_device = io_device_for_storage(storage, args.io_device)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    print(f"Rootless container storage: {storage}; whole I/O device: {io_device}", flush=True)
    before = source_digest()
    revision = run(GIT + ["rev-parse", "HEAD"], cwd=ROOT).strip()
    evidence = read_json(args.broker_image_evidence)
    binary = ROOT / "target/x86_64-unknown-linux-musl/release/olo-toolgate-tool-host"
    import hashlib
    if (not evidence or evidence.get("sourceSha256") != before or evidence.get("revision") != revision
            or evidence.get("image") != args.broker_image
            or evidence.get("binarySha256") != hashlib.sha256(binary.read_bytes()).hexdigest()):
        parser.error("current-source/revision/binary/image evidence required")
    env = {"HOME": account.pw_dir, "XDG_RUNTIME_DIR": f"/run/user/{account.pw_uid}",
           "PATH": str(Path(args.cargo).parent) + ":" + os.environ.get("PATH", "/usr/bin:/bin")}
    for key in ("CARGO_HOME", "RUSTUP_HOME"):
        if key in os.environ:
            env[key] = os.environ[key]
    as_user = ["/usr/sbin/runuser", "-u", account.pw_name, "--", "/usr/bin/env", "-i"] + [k + "=" + v for k, v in env.items()]
    build = run(as_user + [args.cargo, "test", "-p", "olo-toolgate-tool-host", "--locked", "--test",
                          "deployment_fixture", "--no-run", "--message-format=json"], cwd=ROOT, timeout=300)
    artifacts = [json.loads(line) for line in build.splitlines() if line.startswith("{")]
    executables = [row["executable"] for row in artifacts if row.get("reason") == "compiler-artifact"
                   and row.get("target", {}).get("name") == "deployment_fixture" and row.get("executable")]
    if len(executables) != 1:
        raise RuntimeError("exactly one owned compiled deployment fixture required")
    executable = executables[0]
    engine = as_user + ["/usr/bin/podman", "--remote=false", "--runtime=crun", "--cgroup-manager=cgroupfs"]
    owned_units = []
    logs = []
    handles = []
    collected = set()
    observations = []
    cleanup_issues = []
    prefix = "toolgate-deployment-test-" + uuid.uuid4().hex[:12]
    description = "ToolGate owned deployment qualification " + prefix

    def stop(unit):
        if unit not in owned_units or not re.fullmatch(r"toolgate-deployment-test-[a-f0-9]{12}-[a-z0-9-]+[.]service", unit):
            raise RuntimeError("refusing to touch a unit outside this drill")
        try:
            state = subprocess.run(["systemctl", "show", unit, "--property=LoadState", "--value"], text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5)
            if state.stdout.strip() == "not-found":
                return True
            owner = subprocess.run(["systemctl", "show", unit, "--property=Description", "--value"], text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5)
            if owner.stdout.strip() != description:
                cleanup_issues.append(unit + ": foreign description; untouched")
                return False
            stopped = subprocess.run(["systemctl", "stop", unit], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15, check=False)
            subprocess.run(["systemctl", "reset-failed", unit], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=False)
            if stopped.returncode == 0:
                return True
        except subprocess.TimeoutExpired:
            pass
        cleanup_issues.append(unit)
        return False

    def launch(area, suffix, scenario, mode="control-group", signal="SIGKILL", restart=False):
        unit = prefix + "-" + suffix + ".service"
        prior = subprocess.run(["systemctl", "show", unit, "--property=LoadState", "--value"],
                               text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5)
        if prior.stdout.strip() != "not-found":
            raise RuntimeError("refusing to replace a pre-existing service unit")
        owned_units.append(unit)
        parent = "/system.slice/" + unit
        values = {**env, "TOOLGATE_DEPLOYMENT_AREA": str(area), "TOOLGATE_DEPLOYMENT_SCENARIO": scenario,
                  "TOOLGATE_SANDBOX_CGROUP_PARENT": parent, "TOOLGATE_SANDBOX_IMAGE": args.image,
                  "TOOLGATE_SANDBOX_BROKER_IMAGE": args.broker_image, "TOOLGATE_SANDBOX_IO_DEVICE": io_device}
        command = ["systemd-run", "--wait", "--unit=" + unit, "--description=" + description, "--uid=" + account.pw_name, "--gid=" + str(account.pw_gid),
                   "--working-directory=" + str(ROOT), "--property=Delegate=yes", "--property=DelegateSubgroup=supervisor",
                   "--property=KillMode=" + mode, "--property=KillSignal=" + signal, "--property=SendSIGKILL=yes",
                   "--property=RuntimeMaxSec=90", "--property=StartLimitBurst=2", "--property=StartLimitIntervalSec=120"]
        if restart:
            command += ["--property=Restart=on-failure", "--property=RestartSec=1"]
        command += ["--setenv=" + k + "=" + v for k, v in values.items()]
        command += [executable, "--exact", "managed_deployment_fixture", "--ignored", "--nocapture", "--test-threads=1"]
        handle = subprocess.Popen(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        handles.append(handle)
        return unit, parent, handle

    def properties(unit):
        text = run(["systemctl", "show", unit, "--property=ControlGroup,Delegate,KillMode,KillSignal,SendSIGKILL,InvocationID,MainPID,NRestarts"])
        return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)

    args.output.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix="toolgate-deployment-")).resolve()
    expected_parent = Path(tempfile.gettempdir()).resolve()
    if root.parent != expected_parent or not root.name.startswith("toolgate-deployment-"):
        raise RuntimeError("unexpected owned temporary directory")
    (args.output / "conformance.json").write_text(json.dumps({"passed": False, "sourceSha256": before, "status": "RUNNING"}) + "\n", encoding="utf-8")
    try:
        # The outer controller directory remains root-owned; the unprivileged
        # fixture owns only its freshly created case directories.
        root.chmod(0o711)

        def area(name):
            path = root / name
            path.mkdir(mode=0o700)
            os.chown(path, account.pw_uid, account.pw_gid)
            return path

        for suffix, mode, signal in (("weak-mode", "process", "SIGKILL"), ("weak-signal", "control-group", "SIGTERM")):
            work = area(suffix)
            unit, _, handle = launch(work, suffix, "weak-unit", mode, signal)
            observed = until(lambda: read_json(work / "observation.json"))
            output, _ = handle.communicate(timeout=10)
            logs.append(output)
            collected.add(handle.pid)
            if handle.returncode != 0 or observed.get("refused") is not True:
                raise RuntimeError("weak deployment was not explicitly refused")
            observations.append({"case": suffix, **observed})
            stop(unit)

        for iteration in range(args.iterations):
            work = area("crash-" + str(iteration))
            started = time.monotonic()
            unit, parent, handle = launch(work, "crash-" + str(iteration), "crash", restart=True)
            qualified = until(lambda: read_json(work / "qualified.json"))
            def running_pair():
                for path in (work / "sandbox-spool").glob("*.json"):
                    record = read_json(path)
                    if not record or record.get("invocationId") != "deployment-crash":
                        continue
                    names = [record["name"], record["brokerName"]]
                    result = subprocess.run(engine + ["inspect", *names], text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5)
                    if result.returncode:
                        return None
                    containers = json.loads(result.stdout)
                    if not all(row.get("State", {}).get("Running") for row in containers):
                        return None
                    actors = [process(int(row["State"]["Pid"])) for row in containers]
                    if not all(actors) or not actors[0]["sigIgn"] & (1 << 14):
                        return None
                    return names, actors
                return None
            names, actors = until(running_pair)
            startup_ms = (time.monotonic() - started) * 1000
            supervisor = process(qualified["supervisorPid"])
            props = properties(unit)
            if not supervisor or props["MainPID"] != str(supervisor["pid"]) or props["InvocationID"] != qualified["unitInvocationId"]:
                raise RuntimeError("supervisor is not the observed service main process")
            for actor in [supervisor, *actors]:
                if not actor["cgroup"].startswith("0::" + parent + "/"):
                    raise RuntimeError("an owned process is outside the managed kill boundary")
            if any(actor["uid"] in (0, account.pw_uid) for actor in actors) or actors[0]["uid"] == actors[1]["uid"]:
                raise RuntimeError("worker/broker subordinate identities are not disjoint")
            for namespace in ("user", "net", "pid", "mnt"):
                if len({actor["namespaces"][namespace] for actor in [supervisor, *actors]}) != 3:
                    raise RuntimeError("worker/broker/supervisor namespace boundary missing")
            # Only the controller can create this host-side file. The new
            # service invocation takes recovery, never a new worker launch.
            (work / "recover-next").write_text("recover-only", encoding="utf-8")
            killed = time.monotonic()
            run(["systemctl", "kill", "--kill-whom=main", "--signal=KILL", unit], timeout=5)

            def old_pair_dead():
                for actor in actors:
                    current = process(actor["pid"])
                    if current and current["startTicks"] == actor["startTicks"] and current["state"] != "Z":
                        return False
                return True
            until(old_pair_dead, seconds=5)
            kill_ms = (time.monotonic() - killed) * 1000
            if kill_ms > 5000:
                raise RuntimeError("observed complete pair termination exceeded the local test ceiling")
            recovered = until(lambda: read_json(work / "observation.json"), seconds=25)
            recovery_ms = (time.monotonic() - killed) * 1000
            if recovery_ms > 25000:
                raise RuntimeError("observed recovery exceeded the local test ceiling")
            if (recovered.get("recovered") is not True or recovered.get("replayedWorker") is not False
                    or recovered["unitInvocationId"] == qualified["unitInvocationId"]
                    or recovered["supervisorPid"] == qualified["supervisorPid"]):
                raise RuntimeError("fresh managed restart did not reconcile without replay")
            for name in names:
                status = subprocess.run(engine + ["container", "exists", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5).returncode
                if status != 1:
                    raise RuntimeError("owned container absence was not confirmed after restart")
            if len(list((work / "sandbox-spool").iterdir())) != 1:
                raise RuntimeError("recovery left active lifecycle records")
            output, _ = handle.communicate(timeout=10)
            logs.append(output)
            collected.add(handle.pid)
            observations.append({"case": "supervisor-crash-auto-restart", "iteration": iteration,
                                 "startupMs": round(startup_ms, 3), "killMs": round(kill_ms, 3),
                                 "recoveryMs": round(recovery_ms, 3), "serviceProperties": props,
                                 "supervisor": supervisor, "terminatedPair": actors, "recovery": recovered,
                                 "ownedNames": names})
            stop(unit)
    finally:
        for unit in owned_units:
            stop(unit)
        for handle in handles:
            if handle.pid not in collected:
                try:
                    output, _ = handle.communicate(timeout=5)
                    logs.append(output)
                except subprocess.TimeoutExpired:
                    logs.append("owned unit launcher did not exit")
        for unit in owned_units:
            journal = subprocess.run(["journalctl", "--unit", unit, "--no-pager", "--lines=100", "--output=cat"],
                                     text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10, check=False)
            logs.append(journal.stdout)
        (args.output / "conformance.log").write_text("\n".join(logs), encoding="utf-8")
        # Never remove a possibly active fixture's state. The outer directory
        # is root owned, freshly allocated and verified within the temp root.
        if not cleanup_issues:
            shutil.rmtree(root)
        else:
            (args.output / "retained-area.txt").write_text(str(root) + "\n", encoding="utf-8")
    passed = before == source_digest() and len(observations) == args.iterations + 2 and not cleanup_issues
    report = {"revision": revision, "sourceSha256": before, "workerImage": args.image,
              "brokerImage": args.broker_image, "brokerBinarySha256": evidence["binarySha256"],
              "ioDevice": io_device, "containerStoragePath": str(storage),
              "architecture": platform.machine(), "kernel": platform.release(),
              "systemdVersion": run(["systemctl", "--version"]).splitlines()[0], "pollIntervalMs": 25,
              "passed": passed, "securityAccepted": False, "productionSloAccepted": False,
              "scope": "owned local managed OCI deployment; no Control credentials or production rollout",
              "assertionCeilingsMs": {"pairKill": 5000, "recovery": 25000},
              "observations": observations}
    (args.output / "conformance.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": passed, "ownedObservations": len(observations)}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
