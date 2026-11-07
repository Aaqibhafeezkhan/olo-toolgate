<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# HotFolder

Package `examples.hotfolder`. Tools (MCP name `hotfolder.<tool>`): `list`, `read_text`, `file_info`, `hash`, `search_text`, `write_text`, `append_text`, `mkdir`, `move`, `copy`. Client trigger `file-dropped` (`examples.hotfolder/file-dropped`, event type `examples.hotfolder.file-dropped.v1`). Part of the [Java Tool SDK examples](../README.md).

Text files in a protected folder on an enrolled device. Every tool is a `@ClientTool`: it runs only on the device the caller selects, and it reaches files only through `ToolContext.files()`. The package holds all of the tool behaviour; the device provides one generic, confined files area (ADR 027) and nothing specific to this package.

## Safety

- `@DeviceFileAccess` declares the access each tool needs (`DEVICE_FILES_READ`, or `DEVICE_FILES_WRITE` for the five that change files). The administrator sees and grants these permissions; Control signs them into the device's registration.
- The device confines every path to its one service-owned folder: no `..`, absolute paths, hidden names, links or other users' files, only allowed text file types, and bounded file sizes, folder capacity and file operations per call.
- A call may only touch the paths it was invoked with: `path` and `destination` must be arguments of that call.
- `move` and `copy` never overwrite an existing destination. A missing file is reported as data (`exists: false`, `moved: false`), so the examples hold in an empty folder, which is how a device self-tests the package.

## Trigger

`file-dropped` is a POLL client trigger ([ADR 058](../../../docs/adr/058-triggers.md)). Every 10 seconds it lists one folder of the HotFolder, set by the assignment config (`{"folder": "inbox"}`, optional `extension`, default `.txt`). It reports each file version there that it has not reported before. It is a `STATE_SNAPSHOT` source: a file dropped and moved away between two checks is never reported. Its checkpoint holds only the files still present, so a file moved to `processed/` is pruned. Trigger file access is read-only (`DEVICE_FILES_READ`). The [LangChain example](../../langchain/README.md#hot-folder-trigger-in-progress-issue-274) assigns it through a deployment profile and processes each event with a LangGraph agent.

## Try it

```sh
toolgate dev      # an MCP server over stdio
toolgate pack     # build/toolgate/hotfolder-0.1.0.tgpkg
```
