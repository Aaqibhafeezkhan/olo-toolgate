<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO Orgs Marketplace

The community marketplace for ToolGate tool packages: a Drupal site (**OLO Orgs**) where publishers upload `.tgpkg` packages, a helper service that validates and signs them, and a public API that ToolGate administrators import from.

**Status:** the helper (PR 1) is here. The Drupal site (PR 2) and the ToolGate admin side (PR 3) follow, as described in [docs/implementation-prompt.md](docs/implementation-prompt.md). The design is [docs/design.md](docs/design.md).

```bash
ci/run.sh                      # isolation check, seed build, helper tests, then the same in the split layout
scripts/init-signing-key.sh    # once per Marketplace; never replaces an existing key
```

## Layout

| Path | What |
|---|---|
| `TOOLGATE_SRC` | Path to the ToolGate source tree, relative to this folder: `..` today, `toolgate` once this folder is its own repository with `olo-toolgate` as a submodule |
| `docs/` | Design, implementation prompt, API contract and plans |
| `drupal/` | The OLO Orgs site; `modules.yml` is the list of Drupal modules the site image installs and enables |
| `drupal/web/themes/custom/olo_orgs/` | Theme assets (OLO logo and favicon) |
| `helper/` | Validation and signing service and its image, `olo-toolgate-marketplace-helper` ([helper/README.md](helper/README.md)) |
| `seed/` | Seed build (`build-seed.sh`, `Dockerfile.stage`) and `expected-packages.json`, the example packages that must be published at bring-up |
| `scripts/` | `toolgate-dir.sh` (the only resolver of `TOOLGATE_SRC`), `isolation-check.sh`, `split-simulation.sh`, `init-signing-key.sh` |
| `ci/` | `run.sh` (the Marketplace's own checks) and `publish-images.sh` (called by the two release workflows) |
| `deploy/` | Compose and Helm deployment (PR 2); `.env.example` lists the settings |

## Separation

This folder is built to move into its own repository. Nothing in ToolGate depends on files here, and everything here reaches ToolGate only through `TOOLGATE_SRC`. See §1 of the implementation prompt.
