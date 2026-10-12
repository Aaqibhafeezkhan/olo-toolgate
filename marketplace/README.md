<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO Orgs Marketplace

The community marketplace for ToolGate tool packages: a Drupal site (**OLO Orgs**) where publishers upload `.tgpkg` packages, a helper service that validates and signs them, and a public API that ToolGate administrators import from.

**Status:** the helper (PR 1) and the Drupal site with its deployment (PR 2) are here. The ToolGate admin side (PR 3) follows, as described in [docs/implementation-prompt.md](docs/implementation-prompt.md). The design is [docs/design.md](docs/design.md); deployment is [docs/deployment.md](docs/deployment.md).

```bash
ci/run.sh                      # isolation check, seed build, helper tests, then the same in the split layout
scripts/init-signing-key.sh    # once per Marketplace; never replaces an existing key
scripts/compose.sh up -d       # the whole site on one host (docs/deployment.md)
drupal/scripts/run-tests.sh    # the Drupal module's functional tests against a real helper
scripts/stateless-test.py      # log in on one replica, upload on another, kill and scale replicas
```

## Layout

| Path | What |
|---|---|
| `TOOLGATE_SRC` | Path to the ToolGate source tree, relative to this folder: `..` today, `toolgate` once this folder is its own repository with `olo-toolgate` as a submodule |
| `docs/` | Design, implementation prompt, API contract and plans |
| `drupal/` | The OLO Orgs site image: `modules.yml` (the contributed modules, the single input), `composer.json`, `config/sync`, `Dockerfile` and the deploy, cron and test scripts |
| `drupal/web/modules/custom/toolgate_marketplace/` | The marketplace module: publishers, packages and versions, the publish flow, the public API and pages, the seed |
| `drupal/web/themes/custom/olo_orgs/` | The OLO Orgs theme, with the OLO logo and favicon |
| `helper/` | Validation and signing service and its image, `olo-toolgate-marketplace-helper` ([helper/README.md](helper/README.md)) |
| `seed/` | Seed build (`build-seed.sh`, `Dockerfile.stage`) and `expected-packages.json`, the example packages that must be published at bring-up |
| `scripts/` | `toolgate-dir.sh` (the only resolver of `TOOLGATE_SRC`), `isolation-check.sh`, `split-simulation.sh`, `init-signing-key.sh`, `compose.sh`, `stateless-test.py` |
| `ci/` | `run.sh` (the Marketplace's own checks) and `publish-images.sh` (called by the two release workflows) |
| `deploy/` | `compose.yaml`, `Caddyfile`, the Helm chart `helm/olo-orgs-marketplace`, and `.env.example` with every setting |

## Separation

This folder is built to move into its own repository. Nothing in ToolGate depends on files here, and everything here reaches ToolGate only through `TOOLGATE_SRC`. See §1 of the implementation prompt.
