# Glossary

| Term | Meaning |
|---|---|
| Tool | AI-callable capability backed by MCP, API, local code, or another controlled runtime |
| Package | Versioned portable definition of tools, artifacts, permissions, AI metadata, credentials requirements and compatibility |
| Gateway | Fast runtime/data-plane authorization and routing component |
| Control Plane | Organization administration, policy, deployment, vault binding and fleet management |
| Endpoint Client | Native service on Windows/Linux/macOS that installs and runs managed local tools |
| Marketplace | Community package registry and publishing platform |
| Resource | Real target of an action: file, repo, table, service, namespace, account, etc. |
| Resource Extractor | Converts tool arguments into normalized resource context |
| Permit | Short-lived signed authorization for one protected execution |
| Desired State | What the Control Plane wants installed/configured on a client |
| Reported State | What the client reports is actually installed and healthy |
| Files area | The one confined folder a device offers package tools that declare `@DeviceFileAccess` (ADR 027) |
| HotFolder | The `examples.hotfolder` package: file tools on a device's files area |
| Vault | Credential provider |
| ALLOW | Execute |
| ASK | Require approval |
| BLOCK | Deny |
