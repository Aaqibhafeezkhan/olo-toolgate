<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# File download

Tools: `downloads.fetch`. Part of the [Java Tool SDK examples](../README.md).

Downloads `<folder>/<file>` from the file server the administrator approved into a local directory, and returns where it landed: the absolute path, a `file:` URL, a shared URL when the directory is published (for example behind a web server or a file share), the size and the SHA-256.

## Safety

- There is no URL argument. The server is the `DownloadHost` setting and the route allows only `/<folder>/<file>`, each a plain name, so a model cannot reach other hosts or internal services.
- Downloads are capped at 32 MiB (`maxResponseBytes` on the route) and 50 s.
- The file is written to a temporary file and moved into place atomically, under a validated name, and never replaces an existing file unless `overwrite` is true.
- Under ToolGate the Tool Host's broker makes the request; add `@HttpAuth` to the route if the server needs credentials.

## Settings

Globals unless marked. Set them in `toolgate.dev.json` for development, as `TOOLGATE_GLOBAL_<NAME>` (or `TOOLGATE_DESTINATION_<NAME>`, `TOOLGATE_SECRET_<NAME>`) for a standalone jar, or on the deployment under ToolGate.

| Setting | Default | Meaning |
|---|---|---|
| `DownloadHost` (destination) | none | Host of the file server, such as `files.example.com`. HTTPS on 443. |
| `DownloadDir` | `downloads` | Directory files are saved in. Absolute under a Tool Host. |
| `PublicBaseUrl` | `none` | URL at which `DownloadDir` is shared, such as `https://files.example.com/incoming/`. |

Your server lays files out deeper than one folder? Add a route for that shape, such as `/{a}/{b}/{file}`, and list it in the tool's `routes`. A route is part of the tool's descriptor, so an administrator approves any change.

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # then edit it; never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/file-download-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "Download reports/2026-10.csv and give me the link."
- "Fetch builds/app-1.4.2.zip again, replacing the old copy."
