<!-- Copyright 2026 OLO Labs -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Linux secured device

`ololab/olo-toolgate-linux-secured-device:dev` is the native ToolGate Linux device
with a Gateway-only network policy. It belongs to ToolGate and uses the matching
`olo-toolgate-device` release. The development and tagged release pipelines build,
test, scan and publish the additional image; the explicit manual development path
also builds and publishes it.

Start the Gateway stack, then spawn a secured device on its Docker network:

```bat
deploy\secured-device\deploy.bat -GatewayContainer toolgate-debug-quickstart-1
```

```sh
./deploy/secured-device/deploy.sh --gateway-container toolgate-debug-quickstart-1
```

The default container is the local debug Quickstart. The launcher reads its
advertised HTTPS device URL, pins its current IPv4 address and copies only its
public device CA certificate. It starts the secured device and submits enrollment
automatically. Review the enrollment code and fingerprint in the Gateway console
and approve it. The device then connects with its native mTLS identity and
reconnects automatically after outages. Enrollment does not allocate tools.

Use another `-Project` / `--project` for each independent device. Named volumes
retain its private key and identity across container recreation. Rerun the launcher
when a recreated Gateway obtains a different Docker IP. A replacement Gateway CA
or identity needs a new enrollment and separate device volumes.

For a Gateway deployed with Control behind a TLS proxy, choose the container that
serves the advertised HTTPS **device endpoint**, its internal TLS port and its
public CA. The standalone authorization Gateway's port 8081 is not the native
device enrollment endpoint. For example:

```sh
./deploy/secured-device/deploy.sh --gateway-container toolgate-gateway-control-proxy-1 \
  --gateway-url https://gateway.example.com:8444 --gateway-port 443 \
  --network toolgate-gateway-control_backend --ca-file /absolute/path/public-ca.crt
```

Windows uses `-GatewayUrl`, `-GatewayPort`, `-Network` and `-CaFile` for the same
options. Supply `-Image` / `--image` to pin a release, and `-NoPull` / `--no-pull`
for a locally built image. The certificate must cover the advertised URL's hostname.

## Network policy

Before opening any network socket, the entrypoint installs an atomic nftables
policy in the container's private network namespace:

- Outbound traffic: TCP to exactly one pinned Gateway IPv4 address and port.
- Inbound traffic: established replies to that device-initiated Gateway connection.
- Internal traffic: a byte-preserving loopback TLS relay on the advertised Gateway
  port; the native client still verifies the Gateway certificate and hostname.
- All other input, output and forwarding is dropped, including IPv6, UDP, DNS,
  other containers, other Gateway ports and unsolicited connections. There are no
  published device ports, and DNS is never used to resolve the Gateway.

The container needs `NET_ADMIN` and `SETPCAP` only for bootstrap. The entrypoint
requires exactly those capabilities and `no-new-privileges`, installs the firewall,
then removes **all** capabilities from the service's effective, permitted,
inheritable, ambient and bounding sets. The service and its child tool processes
cannot change the firewall. Failure to validate the destination, trust certificate,
capabilities or firewall stops startup before the client runs.

Use the supplied Compose configuration with its private container network namespace.
The Docker host/operator controls that namespace and container startup; overriding
the entrypoint, attaching privileged peers to the namespace, `docker exec` with
bootstrap capabilities, host networking or privileged containers is outside this
confinement boundary. Do not mount the Docker socket or Gateway private keys.

The CA is supplied at deployment so no HTTP console exception is required.
Gateway traffic remains TLS-verified. The policy confines network destinations;
authorization of actions at the permitted Gateway remains ToolGate's usual policy.

## Local validation

```sh
docker build -f apps/device/secured.Dockerfile \
  --build-arg DEVICE_IMAGE=ololab/olo-toolgate-device:dev \
  -t olo-toolgate-linux-secured-device:local .
./deploy/secured-device/deploy.sh --image olo-toolgate-linux-secured-device:local --no-pull
python tools/device/secured_check.py --image olo-toolgate-linux-secured-device:local \
  --quickstart-image ololab/olo-toolgate-quickstart:dev
```

The gate uses isolated real containers, reviews enrollment, verifies mTLS check-in,
tests reachable TCP/UDP/IPv6 peers and Docker DNS, tests ingress against live
listeners, proves the unprivileged service cannot change the firewall, and checks
retained identity and reconnection. It writes `build/secured_device/smoke.json`.
