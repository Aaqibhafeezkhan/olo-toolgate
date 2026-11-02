# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Qualify the declared Linux x86_64 Python OCI interface and egress matrix.

Consumes current-source actual OCI evidence and executes the required production
component/native-process checks. This is not acceptance of all original catalogs,
other transports/tiers, deployment SLOs or the complete C1-C8 security model.
"""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess

from sandbox import ROOT, GIT, source_digest

CASES = {
    "admission-no-fallback": {
        "catalog": ["sandbox/no-weaker-tier", "sandbox/cross-tenant-permit"],
        "native": ["an_image_bound_runtime_is_refused_by_a_native_host_before_consumption",
                   "bad_permits_are_refused_before_anything_runs"],
        "oci": ["isolated_pool_refuses_unsupported_profiles_before_consuming_authority"],
    },
    "single-use-private-state": {
        "catalog": ["sandbox/reuse-isolation"],
        "scope": "always fresh; reusable workers are disabled",
        "oci": ["concurrent_workers_have_distinct_ids_and_private_scratch",
                "sequential_invocations_reuse_no_scratch_environment_or_broker_authority"],
    },
    "syscall-resource-and-network": {
        "catalog": ["sandbox/escape-probes", "egress/no-route", "egress/udp-dropped"],
        "oci": ["rootless_workers_deny_ambient_authority_and_preserve_the_stdio_protocol"],
    },
    "private-broker-and-descriptors": {
        "catalog": ["egress/broker-isolation"],
        "scope": "local namespaces and narrow supervisor delegate; no Kubernetes NetworkPolicy claim",
        "native": ["real_helper_redeems_only_owned_handles_and_never_loads_a_supervisor_configuration",
                   "a_required_broker_cannot_fall_back_to_the_in_process_path"],
        "oci": ["worker_cannot_impersonate_the_private_broker_authority_channel",
                "worker_inherits_no_supervisor_file_descriptor_or_platform_credentials"],
    },
    "strict-worker-frames": {
        "catalog": ["protocol/frame-fuzz", "protocol/oversize"],
        "native": ["worker_protocol::tests::deterministic_mutations_never_accept_without_a_canonical_schema",
                   "worker_protocol::tests::a_broker_request_identifier_cannot_be_reused_or_follow_a_terminal_result",
                   "actual_child_malformed_output_cannot_be_repaired_by_later_success",
                   "actual_child_fragmented_frames_are_valid_but_truncated_or_duplicate_results_fail",
                   "actual_child_progress_and_log_floods_end_before_deadline"],
        "oci": ["qualified_worker_interface_attacks_fail_without_recovering_to_success",
                "frame_and_diagnostic_flooding_end_the_worker_before_the_deadline"],
    },
    "pending-call-order-and-cancellation": {
        "catalog": ["egress/kill-drops-connections"],
        "scope": "owned invocation cancellation/lease loss; generalized signed TERMINATE and tunnels remain separate",
        "native": ["a_result_printed_during_an_outstanding_broker_call_cannot_finish_the_run",
                   "cancelling_an_outstanding_broker_call_closes_its_target_socket",
                   "diagnostic_flooding_cancels_a_pending_broker_call_without_waiting_for_deadline"],
        "oci": ["broker_crash_cancels_worker_and_reclaims_both_owned_containers",
                "broker_works_but_cancellation_and_deadline_remove_the_entire_worker",
                "a_pinned_sdk_package_runs_in_oci_and_lease_revocation_tears_it_down",
                "sandbox::tests::restart_reconciles_an_owned_running_container_before_reopening_the_pool"],
    },
    "shared-mcp-tls-compatibility": {
        "catalog": ["egress/bad-cert"],
        "scope": "shared verified TLS compatibility only; native MCP is not a qualified OCI worker",
        "native": ["a_refresh_with_a_changed_key_fails_before_the_credential_is_sent",
                   "a_scan_pages_through_the_tools_and_reports_raw_definitions_with_the_pin",
                   "a_call_with_fresh_evidence_goes_straight_to_the_tool_and_is_marked_untrusted"],
    },
    "checked-transport": {
        "catalog": ["egress/rebinding", "egress/redirect-out", "egress/bad-cert"],
        "scope": "fresh HTTP/1.1, no redirects/retries, complete answer checks, standard TLS plus optional leaf pins",
        "native": ["egress::tests::every_new_or_mixed_dns_answer_is_rechecked_without_partial_acceptance",
                   "hostname_and_literal_address_refusals_contact_no_target",
                   "redirects_are_observed_without_contacting_same_or_foreign_redirect_targets",
                   "certificate_validation_and_leaf_pins_reject_forged_chains_before_application_contact",
                   "broker_uses_fresh_http1_connections_when_target_prefers_http2",
                   "a_refused_send_gate_contacts_no_target_and_an_allowed_gate_sends_once_over_mtls"],
    },
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--oci-report", type=Path, default=ROOT / "build/security/sandbox/conformance.json")
    parser.add_argument("--cargo", default=shutil.which("cargo"))
    parser.add_argument("--output", type=Path, default=ROOT / "build/security/containment")
    args = parser.parse_args()
    if not args.cargo or not Path(args.cargo).is_absolute():
        parser.error("an absolute cargo executable is required")
    before = source_digest()
    revision = subprocess.check_output(GIT + ["rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    oci = json.loads(args.oci_report.read_text(encoding="utf-8"))
    if (oci.get("passed") is not True or oci.get("sourceSha256") != before
            or oci.get("revision") != revision or oci.get("architecture") != "x86_64"
            or oci.get("tier") != "OCI_SANDBOX"):
        parser.error("a passing current-source/revision Linux x86_64 OCI report is required")
    command = [args.cargo, "test", "-p", "olo-toolgate-tool-host", "--locked", "--lib"]
    for name in ("dispatch", "worker_protocol", "egress_protocol", "broker_process", "service_dispatch", "mcp_proxy"):
        command += ["--test", name]
    command += ["--", "--test-threads=1", "--nocapture"]
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(result.stdout, end="")
    native = re.findall(r"^test (\S+) \.\.\. ok$", result.stdout, re.MULTILINE)
    # No successful case is credited twice; ignored OCI tests here are supplied
    # only by the separate mandatory OCI gate.
    unique = len(native) == len(set(native))
    rows = []
    for case, required in CASES.items():
        passed = (set(required.get("native", [])).issubset(native)
                  and set(required.get("oci", [])).issubset(oci.get("executedTests", [])))
        rows.append({"id": case, **required, "passed": passed})
    success = (result.returncode == 0 and unique and all(row["passed"] for row in rows)
               and before == source_digest() and not re.search(r"skipping.*(?:python|runtime)", result.stdout, re.IGNORECASE))
    report = {"revision": revision, "sourceSha256": before,
              "workerImage": oci["image"], "brokerImage": oci["brokerImage"],
              "brokerBinarySha256": oci["brokerBinarySha256"],
              "passed": success, "securityAccepted": False, "originalCatalogsAccepted": False,
              "scope": "declared Linux x86_64 Python OCI interface/egress matrix",
              "disabled": ["worker reuse", "TCP/tunnels/Unix socket transports", "other runtime/OS/VM tiers"],
              "outstanding": ["generalized signed kill/transport catalogs", "deployment SLOs", "full roadmap/release security acceptance"],
              "cases": rows, "nativeExecutedTests": native, "ociExecutedTests": oci["executedTests"]}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "conformance.log").write_text(result.stdout, encoding="utf-8")
    (args.output / "conformance.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return 0 if success else 1


if __name__ == "__main__":
    raise SystemExit(main())
