# Endpoint Client

The Endpoint Client is a native managed service for Windows, Linux and macOS.

Use the current [device registry flow](../control-plane/device-registry.md) for
pending requests, owner binding, timed/unlimited approval and independent
enablement. All client tool calls pass these gates and Gateway filters before
protected effects. Installation, connection status and package assignment alone
grant no execution permission.

## Owns

- device identity.
- enrollment.
- The device files area and the HotFolder package.
- managed package install/update/remove.
- runtime supervision.
- local tool invocation.
- local privilege separation.
- reported state.
- Gateway authorization before protected execution.

## Trust Rule

A community Marketplace signature alone is never enough to install. The client also requires an organization deployment assignment/signature.

Module 09 signed fleet lifecycle: [production usage, configuration and debugging](package-deployment.md).
