# Contributor release notes

Add a YAML file under the active collection in `config.json`, currently
`v0.1.0-beta.1/`. Name it `<number>-<name>.yaml`, for example
`3-your-name.yaml`. Use your own name and optional GitHub login; do not include
email addresses or private details. Multiple files per contributor are allowed.
The number controls display order; equal numbers sort by filename.

The initial collection summarizes core contributors Rahul Nigam (`olo-labs`) and
Anupriya Srivastava (`olo-orgs`) using repository history and associated PRs.
Extend their `changes` lists as work lands. Integration/merge contributions are
described as such rather than attributed as original implementation work.

```yaml
schemaVersion: 1
contributor:
  name: Your Name
  github: your-login # optional, without @
changes:
  - type: fixed
    component: Gateway # optional
    summary: Describe the user-visible behavior and why it matters.
    pr: 123 # optional, positive PR number
```

Types, in output order: `breaking`, `added`, `changed`, `fixed`, `security`,
`docs`, `internal`. Add multiple changes to your file or add another fragment.
The [JSON Schema](fragment.schema.json) rejects unknown fields, missing author
details, invalid types and empty changes. Duplicate YAML keys are rejected.
Only numbered `.yaml`/`.yml` files belong inside a version directory.

From the repository root:

```sh
python -m pip install -r tools/release/notes-requirements.txt
python tools/release/notes.py --check
python tools/release/notes.py --output build/release-notes
python -m unittest discover -s tests/ci -p test_release_notes.py -v
```

Generation produces:

- `RELEASE-NOTES.md`: existing generated product notes plus contributor changes.
- `RELEASE-NOTES.generated.md`: contributor changes alone.
- `contributors.json`: the complete validated entries, contributor details and
  source filenames, suitable for downstream release automation.

The generator never edits source notes or consumes/deletes fragments. Output is
UTF-8 with LF newlines, stable category/file/change order and no timestamps.
Contributor text is escaped when rendered as Markdown. Repeated builds of the
same source produce identical bytes; changes accumulate throughout the branch.

`config.json` identifies the planned release collection independently of the
actual binary/build version (for example a `-dev.<run>` or allocated beta number).
Every build uses the config and fragments from its selected source commit, never
from a newer controller checkout. To start the next collection, add its version
directory with an initial fragment and update `config.json` in that source
branch. Preserve previous collections. Use `--version v0.1.0-beta.1` to preview
an archived collection explicitly. Invalid or empty configured collections fail
the build; historical source branches without `.changes/` retain baseline notes
and receive an empty contributor manifest.

Prepared releases validate fragments before expensive builds and generate assets
before bundle checksums and provenance. Manual development preparation carries
the rendered bytes in its source overlay; every manual artifact bundle includes
them even when building a release branch. Verified development/tag publication
also includes them. GitHub release bodies use the combined notes. Publication
reuses the collected bytes, retaining existing versioning, test gates, registry
publication, checksum and retry behavior. Embedded package documents retain the
existing product notes; the accumulated contributor documents are release assets.
Legacy tag publication retains `RELEASE-NOTES.md` byte-for-byte for foundation
evidence checks and attaches `RELEASE-NOTES.combined.md` for the combined body.
