# Marketplace

The OLO Marketplace is developed in a separate private repository and hosted at
[olo-labs.org](https://olo-labs.org). This repository contains no Marketplace
applications.

ToolGate Control can import packages from a configured external registry that
implements the [external registry API contract](../api/marketplace-api.md). Follow
[Marketplace integration](../control-plane/marketplace-integration.md) for the
current settings, trust checks and supported import path.

A community release signature establishes provenance only. It never grants
organization deployment authority or runtime permission. Tool authors should use the
[Java SDK and package workflow](../../TOOL-SDK-GUIDE.md).
