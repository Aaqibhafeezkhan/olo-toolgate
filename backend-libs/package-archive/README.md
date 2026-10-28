<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Package archive

The `.tgpkg` validator Control runs on every package upload (platform-requirements.md §7.2, §7.6), as a library with no dependencies
beyond the JDK. Control uses it through `apps/control-plane/.../application/PackageArchive.java`, and the OLO Orgs
Marketplace helper compiles the same sources with plain `javac --release 17`, so both apply exactly the same rules.

```java
PackageArchive.Verified verified = PackageArchive.validate(bytes); // throws PackageRejected
verified.manifest();            // Json, parsed as strictly as Control's codec
verified.packageDigest();       // sha256 of CHECKSUMS.sha256
verified.descriptorSetDigest();
```

`PackageRejected.status()` is 413 for a package over 64 MiB and 400 otherwise; its message names the rule that failed.

## Regression baseline

`tests/fixtures/packages/baseline.json` records the decision and digests for every fixture in
`tests/fixtures/packages/cases` and `tool-sdk/conformance/fixtures/packages`. It was recorded on Control's own
`PackageArchive` before the extraction, and both `PackageArchiveBaselineTest` (Control) and `PackageArchiveTest`
(this library) check it. Regenerate the fixtures with `python3 tests/fixtures/packages/generate.py`.

The extraction made two rejections explicit: a descriptor whose keys collide in NFC, and one with a number outside the
double range, used to escape validation as an `IllegalArgumentException` (which Control's error mapper already answered
with 400) and are now a `PackageRejected` with a reason, so the helper rejects them the same way.

```bash
./gradlew :package-archive:test :control-plane:test --tests io.ololabs.toolgate.control.PackageArchiveBaselineTest
```
