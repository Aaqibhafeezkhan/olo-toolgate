# Examples

Safe example packages, policies and integrations. Never include real credentials.

- [Govern an existing MCP server](mcp-proxy/README.md): register a scriptable MCP server, scan it
  from a Tool Host, approve two tools into a tenant package, call them, and see a change quarantined.
- [Python Hello World](../core-mcp/hello-world-python/README.md): the same
  server/device greetings as Java Hello World, packaged and invoked through the
  ordinary ToolGate flow by changing only the tool name.

- [Java example tools](java-tools/README.md): twelve ready-to-run tool packages built with the
  Java Tool SDK for everyday operations: run an allowlisted script and return its last lines
  of output, download a file, system health, CPU and processes, log search and error grouping,
  disk usage, endpoint checks, CSV queries, read-only database queries, webhook alerts and
  old-file cleanup.
- [Mocked tool examples](mocked-tools/README.md): seven tool packages that return fixed,
  fictional data for bank loan approval, a healthcare clinic, server crash recovery, customer
  support, an HR helpdesk, fraud detection and insurance claims, for designing and demoing
  agent flows before real systems are connected.
- [LangChain with Linux and Windows devices](langchain/README.md): Docker Compose
  runs the published Quickstart and a real Linux endpoint. An AI agent reads
  fictional support, operations or inventory data through ToolGate and writes a
  report into the selected device's HotFolder. Includes Windows `deploy.bat` and `execute.bat`
  launchers and a model-free device smoke check.
