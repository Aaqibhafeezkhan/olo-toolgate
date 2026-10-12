<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO Orgs Marketplace

The community marketplace for ToolGate tool packages: a Drupal site (**OLO Orgs**) where publishers upload `.tgpkg` packages, a helper service that validates and signs them, and a public API that ToolGate administrators import from.

**Status:** planning and non-code assets only. The code arrives in three PRs described in [docs/implementation-prompt.md](docs/implementation-prompt.md); the design is [docs/design.md](docs/design.md).

## Layout

| Path | What |
|---|---|
| `TOOLGATE_SRC` | Path to the ToolGate source tree, relative to this folder: `..` today, `toolgate` once this folder is its own repository with `olo-toolgate` as a submodule |
| `docs/` | Design, implementation prompt, API contract and plans |
| `drupal/` | The OLO Orgs site; `modules.yml` is the list of Drupal modules the site image installs and enables |
| `drupal/web/themes/custom/olo_orgs/` | Theme assets (OLO logo and favicon) |
| `helper/` | Validation and signing service (PR 1) |
| `seed/expected-packages.json` | Example packages that must be published at bring-up |
| `deploy/` | Compose and Helm deployment (PR 2); `.env.example` lists the settings |

## Separation

This folder is built to move into its own repository. Nothing in ToolGate depends on files here, and everything here reaches ToolGate only through `TOOLGATE_SRC`. See §1 of the implementation prompt.
