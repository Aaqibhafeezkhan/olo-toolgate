<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# 069: Local WorkflowStack administration and portable profiles

Status: accepted for the local evaluation stack.

The Compose operator already owns Docker access and private deployment custody.
Run its administration web server on loopback only, outside Control and the
workflow runtime. It owns a separate local administrator password and expiring
sessions. Browser mutations require the exact same origin and a session token.
It accepts configuration names, never shell commands or arbitrary host paths.
Control authentication and authorization remain unchanged. The portal links to
the selected Control console and displays its credential location.

One profile is active at a time. Switching stops the previous deployment while
retaining its data, then provisions the selected configuration. Replacement
removes only the selected deployment's generated state/data before reprovisioning.
Factory reset removes that deployment and starts an empty Control installation;
source configurations, models and the local operator account remain. No store
conversion or compatibility path is introduced. Actions are serialized, failures
are visible, and a failed switch does not claim successful activation.

Portable `.olo` bundles use ZIP compression and contain a configuration, checked asset hashes, model files and
Docker image archives. Imports are privileged operator actions: importing code
does not prove publisher trust. Validate the complete bounded archive and its
hashes before writing a new configuration; never overwrite a source configuration,
follow links, extract paths blindly, or import runtime databases/identities/secrets.
Image loading is explicit as part of trusted bundle import. Local credentials are
regenerated. Vendor secrets must be supplied separately; absence blocks applicable
provider operation. Matching images/assets reproduce configuration behavior, not
identical nondeterministic model responses or external provider state.

Multi-profile merge and simultaneous workflows are deferred by the user's current
one-profile-at-a-time scope. This local operator API is not a public Control or
Gateway protocol, and must not be exposed on a network interface.
