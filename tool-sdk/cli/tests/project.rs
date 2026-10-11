// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! `toolgate init` and the project commands that need no build tool. The Java end-to-end run
//! (tool-sdk/java-sdk/e2e/run.sh) builds, checks, serves and packs what `init` writes.
mod common;
use common::scratch;
use std::path::Path;
use std::process::Command;

fn toolgate(dir: &Path, args: &[&str]) -> (i32, String) {
    let output = Command::new(env!("CARGO_BIN_EXE_toolgate"))
        .args(args)
        .current_dir(dir)
        .output()
        .unwrap();
    let text = String::from_utf8_lossy(&output.stdout).into_owned()
        + &String::from_utf8_lossy(&output.stderr);
    (output.status.code().unwrap(), text)
}

fn files(dir: &Path) -> Vec<String> {
    let mut out = Vec::new();
    let mut stack = vec![dir.to_path_buf()];
    while let Some(d) = stack.pop() {
        for entry in std::fs::read_dir(&d).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                stack.push(path);
            } else {
                out.push(
                    path.strip_prefix(dir)
                        .unwrap()
                        .to_string_lossy()
                        .replace('\\', "/"),
                );
            }
        }
    }
    out.sort();
    out
}

#[test]
fn init_writes_the_java_gradle_starter_with_every_placeholder_filled() {
    let dir = scratch("init-gradle");
    let (code, out) = toolgate(
        &dir,
        &[
            "init",
            "order-desk",
            "--id",
            "acme.order-desk",
            "--publisher",
            "Acme",
        ],
    );
    assert_eq!(0, code, "{out}");
    assert!(out.contains("java with gradle"), "{out}");
    assert!(
        out.contains("publishJavaSdkToMavenLocal"),
        "without --sdk-repo the note says where the SDK comes from: {out}"
    );
    let project = dir.join("order-desk");
    assert_eq!(
        vec![
            ".gitignore",
            "README.md",
            "build.gradle.kts",
            "gradle/bootstrap.ps1",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradlew",
            "gradlew.bat",
            "settings.gradle.kts",
            "src/main/java/acme/order_desk/OrderDeskTools.java",
            "src/main/resources/exchanges/item-1.json",
            "src/test/java/acme/order_desk/OrderDeskToolsTest.java",
            "toolgate.dev.json",
        ],
        files(&project)
    );
    for file in files(&project) {
        let text = std::fs::read_to_string(project.join(&file)).unwrap();
        assert!(!text.contains("{{"), "{file} has an unfilled placeholder");
    }
    let tools =
        std::fs::read_to_string(project.join("src/main/java/acme/order_desk/OrderDeskTools.java"))
            .unwrap();
    assert!(tools.starts_with("package acme.order_desk;"));
    assert!(tools.contains(
        "@ToolPackage(id = \"acme.order-desk\", name = \"Order Desk\", publisher = \"Acme\")"
    ));
    let build = std::fs::read_to_string(project.join("build.gradle.kts")).unwrap();
    assert!(build.contains(&format!("version \"{}\"", env!("CARGO_PKG_VERSION"))));
    assert!(build.contains("mavenLocal()"));
    assert!(std::fs::read_to_string(project.join(".gitignore"))
        .unwrap()
        .contains("toolgate.dev.json"));
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        assert_ne!(
            0,
            std::fs::metadata(project.join("gradlew"))
                .unwrap()
                .permissions()
                .mode()
                & 0o111
        );
    }
}

#[test]
fn init_writes_the_java_maven_starter_with_an_sdk_repository() {
    let dir = scratch("init-maven");
    let (code, out) = toolgate(
        &dir,
        &[
            "init",
            "orders",
            "--build",
            "maven",
            "--dir",
            "here",
            "--sdk-repo",
            "https://repo.example/maven",
            "--sdk-version",
            "1.2.3",
        ],
    );
    assert_eq!(0, code, "{out}");
    assert!(!out.contains("publishJavaSdkToMavenLocal"), "{out}");
    let project = dir.join("here");
    assert!(!project.join("gradlew").exists());
    let pom = std::fs::read_to_string(project.join("pom.xml")).unwrap();
    assert!(pom.contains("<url>https://repo.example/maven</url>"));
    assert!(pom.contains(
        "<artifactId>toolgate-maven-plugin</artifactId>\n        <version>1.2.3</version>"
    ));
    assert!(project
        .join("src/main/java/example/orders/OrdersTools.java")
        .is_file());
}

#[test]
fn init_refuses_bad_names_unknown_starters_and_a_non_empty_directory() {
    let dir = scratch("init-refuse");
    let refused = |args: &[&str], fragment: &str| {
        let (code, out) = toolgate(&dir, args);
        assert_eq!(1, code, "{args:?}: {out}");
        assert!(out.contains(fragment), "{args:?}: {out}");
    };
    refused(&["init", "Orders"], "project name");
    refused(&["init", "orders", "--id", "orders"], "package id");
    refused(
        &["init", "orders", "--lang", "cobol"],
        "no starter for language cobol; available: java",
    );
    refused(
        &["init", "orders", "--build", "ant"],
        "no java starter for ant; available: gradle, maven",
    );
    std::fs::create_dir_all(dir.join("taken")).unwrap();
    std::fs::write(dir.join("taken/file"), "x").unwrap();
    refused(&["init", "orders", "--dir", "taken"], "not empty");
    assert_eq!(2, toolgate(&dir, &["init"]).0);
    assert_eq!(2, toolgate(&dir, &["init", "orders", "--bogus"]).0);
}

#[test]
fn project_commands_say_what_is_missing() {
    let dir = scratch("project-none");
    let (code, out) = toolgate(&dir, &["dev"]);
    assert_eq!(1, code);
    assert!(
        out.contains("is not a tool project: no settings.gradle.kts"),
        "{out}"
    );
    std::fs::write(dir.join("pom.xml"), "<project/>").unwrap();
    std::fs::write(dir.join("build.gradle.kts"), "").unwrap();
    let (code, out) = toolgate(&dir, &["check"]);
    assert_eq!(1, code);
    assert!(
        out.contains("has projects for gradle and maven; choose one with --build"),
        "{out}"
    );
    let (code, out) = toolgate(&dir, &["pack", "--build", "ant"]);
    assert_eq!(1, code);
    assert!(out.contains("has no ant tool project"), "{out}");
}

#[test]
fn project_commands_run_the_projects_build_tool() {
    // A stand-in Gradle wrapper that records its arguments and environment proves the mapping without a JDK.
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let dir = scratch("project-gradle");
        std::fs::write(dir.join("settings.gradle.kts"), "").unwrap();
        let wrapper = dir.join("gradlew");
        std::fs::write(&wrapper, "#!/bin/sh\necho \"args: $*\"\necho \"mode: $TOOLGATE_MODE http: $TOOLGATE_HTTP_ADDRESS cli: ${TOOLGATE_CLI:+set}\"\n").unwrap();
        std::fs::set_permissions(&wrapper, std::fs::Permissions::from_mode(0o755)).unwrap();
        let (code, out) = toolgate(
            &dir,
            &["dev", "--http", "127.0.0.1:9000", "--", "--offline"],
        );
        assert_eq!(0, code, "{out}");
        assert!(
            out.contains("-q --console=plain toolgateDev --offline"),
            "{out}"
        );
        assert!(
            out.contains("mode: development http: 127.0.0.1:9000 cli: set"),
            "{out}"
        );
        let (code, out) = toolgate(&dir, &["validate", "."]);
        assert_eq!(0, code, "{out}");
        assert!(
            out.contains("--console=plain check") && out.contains("check passed"),
            "{out}"
        );
        let (code, out) = toolgate(&dir, &["pack"]);
        assert_eq!(1, code, "{out}");
        assert!(
            out.contains("build/toolgate"),
            "a build that writes no package is an error: {out}"
        );
    }
}
