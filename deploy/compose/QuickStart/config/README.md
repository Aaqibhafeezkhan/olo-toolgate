# Configuration folder

Mounted read-only at `/config/import`. Put a configuration file here (an exported snapshot,
optionally with `settings`, `visitorPool` and `reservedRecords`) and set
`TOOLGATE_INITIALIZE_FILE=/config/import/<name>.json` before the first start. It is applied
once, on a fresh `/data` volume. See [Quickstart deployment](../../../../docs/deployment/quickstart.md).
