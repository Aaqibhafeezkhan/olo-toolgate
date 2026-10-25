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
Both initial records use `roles: [core-contributor]`.

```yaml
schemaVersion: 1
contributor:
  name: Your Name
  github: your-login # optional, without @
  roles: [contributor] # optional, may contain multiple roles
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

## Shared work and release-ready context

The fragment's `contributor` records its primary author. A change can additionally
credit multiple `contributors`, identify multiple accountable `owners`, reference
multiple `pullRequests`, and link `tickets` from Jira, GitHub Issues, or another
tracker. Each ticket can carry its own owner, collaborators, and an owner-provided
summary with `summaryAuthor` attribution. This supports work handled by several
people without requiring duplicate release-note entries.

Ask the ticket owner for a concise, approved, user-facing `summary`; capture the
benefit in `impact`, upgrade steps in `migration`, and affected readers in
`audience`. These fields are optional so small fixes remain easy to submit.
For shared work, list each person's name and optional GitHub login explicitly.
`pr` remains supported; it is merged with `pullRequests` and displayed once.

People may have multiple `roles`: `core-contributor`, `core-maintainer`,
`maintainer`, `contributor`, `author`, `reviewer`, `tester`, `documentation`,
`release-manager`, or `product-owner`. Roles can describe a person's standing in
their primary contributor record or their participation in a particular feature,
PR or ticket. They are attribution metadata and do not grant repository access.
A feature's `owners` and each PR's `owners` are independent lists, so shared
ownership can be recorded at both levels. Structured PR entries can also include
`title`, HTTPS `url`, `contributors` and `reviewers`.

Example change (ticket and PR references below are illustrative):

```yaml
  - type: fixed
    component: Tool Host
    summary: Resume interrupted tasks without duplicating completed effects.
    impact: Operators can recover work after a worker restart.
    migration: Restart workers after updating.
    audience: [operators, developers]
    pullRequests:
      - number: 123
        title: Recover interrupted tasks
        owners: # multiple owners for this PR, independent of feature owners
          - name: Rahul Nigam
            github: olo-labs
            roles: [core-contributor, author]
          - name: Anupriya Srivastava
            github: olo-orgs
            roles: [core-contributor, reviewer]
        reviewers:
          - name: Anupriya Srivastava
            github: olo-orgs
            roles: [reviewer]
      - 124 # a bare PR number is also supported
    owners:
      - name: Rahul Nigam
        github: olo-labs
    contributors:
      - name: Rahul Nigam
        github: olo-labs
      - name: Anupriya Srivastava
        github: olo-orgs
    tickets:
      - system: jira
        id: TG-123
        url: https://jira.example.com/browse/TG-123
        summary: Recover in-progress tasks after an interrupted deployment.
        owner:
          name: Rahul Nigam
          github: olo-labs
        summaryAuthor:
          name: Rahul Nigam
          github: olo-labs
        contributors:
          - name: Anupriya Srivastava
            github: olo-orgs
```

Ticket `system` is `jira`, `github`, or `other`. A ticket needs an `id` and HTTPS
`url`; owner, summary author and collaborators use the same person structure as
the primary contributor. Add only references and descriptions suitable for the
release audience. All supplied fields appear in the JSON artifact; contextual
details and ticket links also appear under the change in the rendered notes.

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
