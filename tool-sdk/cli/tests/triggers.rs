// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Trigger exports in a package (ADR 058, plan 5.9): `pack` writes `triggers/<name>.json` and the
//! `triggerDescriptorDigest`, `descriptorSetDigest` covers tools and triggers, and `validate`
//! refuses what the processor's rules (plan 5.7 and 5.8) refuse.
mod common;
use common::{json, source_manifest, stage, ARTIFACT};
use serde_json::{json, Value};
use toolgate_cli::{archive, canonical, descriptor, package, Error};

/// A POLL server trigger without capabilities, from the v2 trigger descriptor fixture.
fn trigger(name: &str) -> Value {
    let mut d = json("tests/fixtures/contracts/v2/valid.json")["TriggerDescriptor"].clone();
    d["triggerId"] = json!(format!("acme.orders/{name}"));
    d["mode"] = json!("POLL");
    d["schedule"] = json!({"intervalMs": 60000});
    d["executors"] = json!(["CLIENT", "SERVER"]);
    d["tcpDestinations"] = json!([]);
    d["secrets"] = json!([]);
    d["permissions"] = json!([]);
    json!({"descriptor": d, "runtimeId": "jvm", "entryPoint": "io.ololabs.toolgate.runtime.Main", "artifactIds": ["app"]})
}

fn with_triggers(triggers: Vec<Value>) -> Value {
    let mut manifest = source_manifest();
    manifest["triggers"] = Value::Array(triggers);
    manifest
}

fn rejected(manifest: &Value, label: &str, fragment: &str) {
    match package::pack(&stage(label, manifest, ARTIFACT)) {
        Err(Error::Rejected(message)) | Err(Error::Invalid(message)) => {
            assert!(message.contains(fragment), "{label}: {message}")
        }
        other => panic!("{label} was not rejected: {other:?}"),
    }
}

#[test]
fn pack_writes_trigger_descriptors_and_covers_them_in_the_set_digest() {
    let manifest = with_triggers(vec![trigger("order-placed")]);
    let (data, report) = package::pack(&stage("triggers", &manifest, ARTIFACT)).unwrap();
    let verified = package::verify(&data).unwrap();
    let descriptor = &manifest["triggers"][0]["descriptor"];
    assert_eq!(
        verified.files["triggers/order-placed.json"],
        canonical::canonical_bytes(descriptor).unwrap()
    );
    let digest = descriptor::tool_descriptor_digest(descriptor).unwrap();
    assert_eq!(
        report.triggers,
        vec![("acme.orders/order-placed".to_owned(), digest.clone())]
    );
    assert_eq!(
        verified.manifest["triggers"][0]["triggerDescriptorDigest"],
        json!(digest)
    );
    // The set digest is over {toolId, toolDescriptorDigest} and {triggerId, triggerDescriptorDigest},
    // sorted by id: "acme.orders/order-placed" sorts before "acme.orders/orders.lookup".
    assert_eq!(report.tools[0].0, "acme.orders/orders.lookup");
    let set = canonical::digest(&json!([
        {"triggerId": "acme.orders/order-placed", "triggerDescriptorDigest": digest},
        {"toolId": report.tools[0].0, "toolDescriptorDigest": report.tools[0].1},
    ]))
    .unwrap();
    assert_eq!(report.descriptor_set_digest, set);
    let (_, tools_only) =
        package::pack(&stage("tools-only", &source_manifest(), ARTIFACT)).unwrap();
    assert_ne!(
        tools_only.descriptor_set_digest, set,
        "a trigger export changes the set digest"
    );
    assert_eq!(package::validate(&data).unwrap().descriptor_set_digest, set);
}

#[test]
fn a_package_may_export_triggers_only() {
    let mut manifest = with_triggers(vec![trigger("b-feed"), trigger("a-feed")]);
    manifest["tools"] = json!([]);
    let (data, report) = package::pack(&stage("trigger-only", &manifest, ARTIFACT)).unwrap();
    assert!(report.tools.is_empty());
    assert_eq!(report.triggers.len(), 2);
    let files = package::verify(&data).unwrap().files;
    assert!(
        files.contains_key("triggers/a-feed.json") && files.contains_key("triggers/b-feed.json")
    );
    assert!(!files.keys().any(|f| f.starts_with("tools/")));
}

#[test]
fn validate_refuses_tampered_or_stray_trigger_files() {
    let manifest = with_triggers(vec![trigger("order-placed")]);
    let (data, _) = package::pack(&stage("tamper", &manifest, ARTIFACT)).unwrap();
    let mut files = package::verify(&data).unwrap().files;
    files.remove(package::CHECKSUMS);
    let rebuild = |files: &std::collections::BTreeMap<String, Vec<u8>>| {
        let mut all = files.clone();
        all.insert(package::CHECKSUMS.into(), package::render_checksums(files));
        archive::write(&all).unwrap()
    };
    let mut stray = files.clone();
    stray.insert("triggers/other.json".into(), b"{}".to_vec());
    match package::validate(&rebuild(&stray)) {
        Err(Error::Rejected(m)) => assert!(m.contains("matches no trigger"), "{m}"),
        other => panic!("a stray trigger file was accepted: {other:?}"),
    }
    let mut edited = files.clone();
    edited.insert(
        "triggers/order-placed.json".into(),
        b"{\"kind\":\"SERVER_TRIGGER\"}".to_vec(),
    );
    match package::validate(&rebuild(&edited)) {
        Err(Error::Rejected(m)) => assert!(m.contains("not the canonical descriptor"), "{m}"),
        other => panic!("an edited trigger file was accepted: {other:?}"),
    }
    let mut stale = manifest.clone();
    stale["triggers"][0]["triggerDescriptorDigest"] = json!(format!("sha256:{}", "0".repeat(64)));
    rejected(&stale, "stale", "triggerDescriptorDigest");
}

#[test]
fn trigger_rules_are_enforced_like_the_processor() {
    let mut schema = trigger("x");
    schema["descriptor"]["eventType"] = json!("no-version");
    rejected(
        &with_triggers(vec![schema]),
        "event-type",
        "PackageManifest",
    );

    let mut client = trigger("files");
    client["descriptor"]["kind"] = json!("CLIENT_TRIGGER");
    rejected(
        &with_triggers(vec![client.clone()]),
        "client-executors",
        "CLIENT executors only",
    );
    client["descriptor"]["executors"] = json!(["CLIENT"]);
    client["descriptor"]["permissions"] = json!(["DEVICE_FILES_WRITE"]);
    rejected(
        &with_triggers(vec![client]),
        "client-write",
        "may only read the device files area",
    );

    let mut networked = trigger("mail");
    networked["descriptor"]["routes"] = json!(["graph-messages"]);
    rejected(
        &with_triggers(vec![networked]),
        "networked",
        "cannot run on CLIENT executors",
    );

    let mut push = trigger("push");
    push["descriptor"]["mode"] = json!("PUSH");
    rejected(
        &with_triggers(vec![push]),
        "push-schedule",
        "a PUSH trigger has schedule.resyncIntervalMs",
    );

    let mut drop = trigger("lossy");
    drop["descriptor"]["overflow"] = json!("DROP_OLDEST");
    rejected(&with_triggers(vec![drop.clone()]), "drop", "lossTolerant");
    drop["descriptor"]["lossTolerant"] = json!(true);
    rejected(
        &with_triggers(vec![drop.clone()]),
        "drop-halt",
        "SKIP_AND_RECORD",
    );
    drop["descriptor"]["onTerminalRejection"] = json!("SKIP_AND_RECORD");
    package::pack(&stage("drop-ok", &with_triggers(vec![drop]), ARTIFACT)).unwrap();

    let mut big = trigger("big");
    big["descriptor"]["limits"]["maxCheckpointBytes"] = json!(131072);
    rejected(
        &with_triggers(vec![big]),
        "big-checkpoint",
        "maxCheckpointBytes above 16384",
    );

    let mut foreign = trigger("x");
    foreign["descriptor"]["triggerId"] = json!("other.pkg/x");
    rejected(
        &with_triggers(vec![foreign]),
        "foreign",
        "is not in package",
    );

    let mut split = trigger("split");
    split["artifactIds"] = json!(["missing"]);
    rejected(&with_triggers(vec![split]), "artifact", "unknown artifact");
}

#[test]
fn tools_and_triggers_share_one_export_namespace() {
    let mut manifest = with_triggers(vec![trigger("lookup")]);
    manifest["tools"][0]["descriptor"]["toolId"] = json!("acme.orders/lookup");
    rejected(&manifest, "shared", "share one namespace");
    rejected(
        &with_triggers(vec![trigger("twice"), trigger("twice")]),
        "twice",
        "share",
    );
}
