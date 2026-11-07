<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# HotFolder

Package `examples.hotfolder`. Tools (MCP name `hotfolder.<tool>`): `list`, `read_text`, `file_info`, `hash`, `search_text`, `write_text`, `append_text`, `mkdir`, `move`, `copy`. Part of the [Java Tool SDK examples](../README.md).

Text files in a protected folder on an enrolled device. Every tool is a `@ClientTool`: it runs only on the device the caller selects, and it reaches files only through `ToolContext.files()`. The package holds all of the tool behaviour; the device provides one generic, confined files area (ADR 027) and nothing specific to this package.

## Safety

- `@DeviceFileAccess` declares the access each tool needs (`DEVICE_FILES_READ`, or `DEVICE_FILES_WRITE` for the five that change files). The administrator sees and grants these permissions; Control signs them into the device's registration.
- The device confines every path to its one service-owned folder: no `..`, absolute paths, hidden names, links or other users' files, only allowed text file types, and bounded file sizes, folder capacity and file operations per call.
- A call may only touch the paths it was invoked with: `path` and `destination` must be arguments of that call.
- `move` and `copy` never overwrite an existing destination. A missing file is reported as data (`exists: false`, `moved: false`), so the examples hold in an empty folder, which is how a device self-tests the package.

## Try it

```sh
toolgate dev      # an MCP server over stdio
toolgate pack     # build/toolgate/hotfolder-0.1.0.tgpkg
```
