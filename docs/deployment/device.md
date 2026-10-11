<!-- Copyright 2026 OLO Labs -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Linux device container

`ololab/olo-toolgate-device:dev` contains the native Linux client from the matching
published Quickstart release. The existing manual and CI-backed development
release jobs publish it alongside Control, Gateway and Quickstart, using the same
commit and `dev` tags. The current published platform is Linux AMD64; the Dockerfile
also supports extracting the ARM64 client when built for that platform.

Run either deployment script from any directory:

```bat
deploy\device\deploy.bat
deploy\device\deploy.bat -Gateways "container_name_1:8443, container_name_2:8443" -Network existing_gateway_network
```

```sh
./deploy/device/deploy.sh
./deploy/device/deploy.sh --gateways 'container_name_1:8443, container_name_2:8443' --network existing_gateway_network
```

The default gateways are `https://host.docker.internal:18450` and
`https://host.docker.internal:18451`. Every omitted argument prompts for an override
in an interactive terminal; pressing Enter keeps its displayed default. Supplied
arguments are used directly. `-NonInteractive` / `--non-interactive`, or redirected
input, uses defaults without prompting.

| Windows argument | Shell argument | Default |
| --- | --- | --- |
| `-Gateways` | `--gateways` | Both Docker-host URLs above |
| `-Image` | `--image` | `ololab/olo-toolgate-device:dev` |
| `-Name` | `--name` | `toolgate-device` hostname |
| `-Project` | `--project` | `toolgate-device` Compose project and identity volumes |
| `-Platform` | `--platform` | `linux/amd64` |
| `-Network` | `--network` | New Compose network; supply the gateways' existing network for container names |
| `-CaFile` | `--ca-file` | No additional CA certificate |
| `-ConsoleUrls` | `--console-urls` | Automatic local Docker console discovery |

The scripts pull the image, recreate the container with its existing volumes, and
wait for the supervisor to start. Use a different project for an independent device.
Container-name entries use HTTPS automatically and resolve on the supplied Docker
network. Docker Desktop supplies `host.docker.internal` for the host defaults.

For direct Compose use, supply comma-separated gateway origins:

```sh
export TOOLGATE_GATEWAY_URLS='https://gateway-one.example.com,https://gateway-two.example.com'
docker compose -f deploy/device/compose.yaml up -d --force-recreate
docker compose -f deploy/device/compose.yaml logs -f device
```

Compose pulls the current `dev` image each time. Container recreation installs
that image's client while keeping the device key, enrollment and files in named
volumes. Set `TOOLGATE_DEVICE_IMAGE` to a commit tag to pin a release.

A new device checks every listed gateway in order, skipping unavailable ones.
If all are offline it retries every five seconds until one becomes available.
HTTPS certificates and hostnames are verified; redirects are rejected. Once it
selects a gateway, the native client begins enrollment automatically. Logs show
the device ID, enrollment code and key fingerprint. Approve that request in the
selected gateway's console. Access and tool allocation follow the usual Control
configuration; enrollment does not grant tools.

For the Docker-host defaults, the local consoles are discovered on HTTP ports
18090 and 18091. Container-name gateways use their HTTP console on port 8080.
These explicitly local Docker connections obtain the public CA and advertised
loopback gateway URL from `/api/public/v1/clients/local-trust`. A TCP relay preserves
the advertised TLS hostname and the native client verifies that CA before enrollment.
This trusts the selected Docker host/network for the initial public CA. Remote DNS
gateway URLs use their existing HTTPS trust chain. For different local console
ports, supply paired, comma-separated `ConsoleUrls` on the same gateway hosts.

The enrolled device reconnects to its selected gateway after outages and container
restarts. Enrollment is specific to that gateway: existing volumes never switch
identity to another gateway. Use separate volumes for a device enrolled elsewhere,
and keep the selected URL in `TOOLGATE_GATEWAY_URLS`.

For a gateway using a private CA, mount its public CA certificate and set
`TOOLGATE_GATEWAY_CA_FILE` on the first installation:

```sh
docker run -d --name toolgate-device --restart unless-stopped \
  --cap-drop=ALL --security-opt=no-new-privileges \
  --tmpfs /run:rw,nosuid,size=8m,mode=755 \
  -e TOOLGATE_GATEWAY_URLS='https://gateway.example.com:8443' \
  -e TOOLGATE_GATEWAY_CA_FILE=/etc/olo-toolgate/gateway-ca.crt \
  -v device-config:/etc/olo-toolgate -v device-state:/var/lib/olo-toolgate \
  --mount type=bind,src=/absolute/path/gateway-ca.crt,dst=/etc/olo-toolgate/gateway-ca.crt,readonly \
  ololab/olo-toolgate-device:dev
```

Keep this certificate mount on subsequent runs. Device config and state are private
to the root-owned service; mount no gateway private keys or Docker socket. The
container opens no inbound ports. Its health check reports an operating supervisor,
including while waiting for a gateway or enrollment approval. Actual connection
state is available in `/run/olo-toolgate/device-status.json` and the gateway console.

To validate a locally built image against a real isolated gateway:

```sh
docker build -f apps/device/Dockerfile -t olo-toolgate-device:module11 .
python tools/device/check.py --quickstart-image ololab/olo-toolgate-quickstart:dev
```
