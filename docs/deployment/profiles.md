# Deployment profiles

A deployment profile declares the packages a deployment needs, pinned exactly, and what to activate. Control applies it
at startup through one application service; scripts and charts only choose the file. Building an image never uploads
a package, enables a tool or creates a grant.

Contract: `DeploymentProfile` and `ProvisioningStatus` in `packages/contracts/schemas/v2/profile.schema.json`.
The Quickstart image ships `deploy/profiles/fresh.json` and selects it with `TOOLGATE_CONTROL_PROFILE_PATH`; mount
another file and set that variable to use a different profile with the same image.

```json
{
  "profileVersion": 1,
  "profileId": "fresh",
  "packages": [
    {
      "packageId": "ololabs.hello-world",
      "source": "PREBUILT_CORE",
      "version": "1.0.0",
      "required": true,
      "hostIds": ["tool-host-local"],
      "tools": [{"toolId": "ololabs.hello-world/server_greet", "toolGroupId": "default-tools"}]
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `source` | `PREBUILT_CORE` resolves only to the sealed artifact in the image's core lock (`TOOLGATE_CONTROL_CORE_PACKAGES_LOCK_PATH`); its digest comes from that lock and a declared `packageDigest` must equal it. `MARKETPLACE` downloads the exact signed release from the configured Marketplace and requires `packageDigest`; a release with another digest is refused before download. |
| `hostIds` | Tool Hosts that receive the package's eligible ServerTool exports. ClientTool exports are never deployed to a Tool Host. |
| `globals` | Package global values for the deployment. |
| `tools` | Installed exports to enable, each in a reviewed Tool Group that must already exist. |
| `bindingIds` | Existing execution bindings whose allowed package digests become exactly the profile packages that name them. |
| `required` | A required package that fails makes the profile `FAILED`; an optional one is reported without blocking readiness. |
| `readiness` | Calls a deployment script makes through the Gateway, as the named agent, before it declares the profile ready. |

## How Control applies a profile

1. The whole profile and the core lock are validated before anything changes. An invalid profile stops startup.
2. Each package is imported through the ordinary package verifier, as the installation's Super Admin resolved through
   the central role resolver. Importing never grants execution.
3. Packages with `hostIds` are deployed once every named Tool Host has registered; until then the package and the
   profile are `PENDING` and Control retries every 15 seconds.
4. Declared exports are enabled in their Tool Groups and declared bindings are updated in reviewed directory changes,
   each audited. An export that is not installed from the pinned package blocks the profile; nothing is created to
   satisfy a reference.
5. The outcome is recorded with the profile digest and a generation. An identical repeat changes nothing. A changed
   profile records the digest it replaced. Removing an entry never uninstalls a package; uninstalling is an explicit
   operation.

`GET /api/control/v1/packages/provisioning/{profileId}` (requires `package.read`) returns the latest status: `READY`,
`PENDING` with the target it waits for, or `FAILED` with the reason. A failed profile keeps Control running for repair and
is retried every minute; nothing falls back to another version or to a built-in tool. Grants, policies and approvals stay
separate reviewed records: a READY profile has installed tools, not given anyone access to run them.
