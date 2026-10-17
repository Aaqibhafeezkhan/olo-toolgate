# Marketplace status

The Marketplace API, worker and Drupal applications are buildable scaffolds.
They do not provide a complete hosted publication/moderation service.
Control does implement import from a configured external OLO Orgs registry;
follow [Marketplace integration](../control-plane/marketplace-integration.md) for
the current settings, trust checks and supported import path.
All remaining work is tracked in the [consolidated backlog](../planning/README.md).

The intended boundary keeps catalog, publisher and immutable version authority
in the API; isolated scan/build/sign/publish orchestration in the worker; and
community/publisher UI in Drupal. A community release signature must never grant
organization deployment or runtime permission.

Contributors can inspect the [external registry API contract](../api/marketplace-api.md),
[Control import guide](../control-plane/marketplace-integration.md),
and the [component map](../architecture/component-map.md). The external API contract and import guide apply to that integration, not to the scaffold applications. Current tool authors should use the
[Java SDK and package workflow](../../TOOL-SDK-GUIDE.md).
