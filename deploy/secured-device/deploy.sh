#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
gateway_container='toolgate-debug-quickstart-1'
gateway_url=''
gateway_port=8443
network=''
ca_file=''
image='ololab/olo-toolgate-linux-secured-device:dev'
name='toolgate-secured-device'
project='toolgate-secured-device'
pull_policy=always
usage() {
  echo 'deploy.sh [--gateway-container NAME] [--gateway-url HTTPS_ORIGIN] [--gateway-port PORT] [--network NETWORK] [--ca-file PUBLIC_CA] [--image IMAGE] [--name NAME] [--project PROJECT] [--no-pull]'
}
while (($#)); do
  case "$1" in
    --no-pull) pull_policy=never; shift; continue ;;
    --help|-h) usage; exit 0 ;;
    --gateway-container|--gateway-url|--gateway-port|--network|--ca-file|--image|--name|--project)
      if (($# < 2)); then echo "Missing value for $1" >&2; exit 2; fi
      key="${1#--}"; key="${key//-/_}"; printf -v "$key" '%s' "$2"; shift 2 ;;
    *) usage >&2; exit 2 ;;
  esac
done
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || { echo 'Invalid Compose project' >&2; exit 2; }
[[ "$name" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}$ ]] || { echo 'Invalid device hostname' >&2; exit 2; }
[[ "$gateway_port" =~ ^[0-9]{1,5}$ ]] && ((10#$gateway_port >= 1 && 10#$gateway_port <= 65535)) || { echo 'Invalid Gateway port' >&2; exit 2; }
[[ $(docker inspect --format '{{.State.Running}}' "$gateway_container") == true ]] || { echo 'Start the Gateway first' >&2; exit 2; }
mapfile -t networks < <(docker inspect --format '{{range $name, $network := .NetworkSettings.Networks}}{{$name}} {{$network.IPAddress}}{{println}}{{end}}' "$gateway_container")
gateway_ip=''
for entry in "${networks[@]}"; do
  [[ -n "$entry" ]] || continue
  if [[ -z "$network" ]]; then
    [[ -z "$gateway_ip" ]] || { echo 'Specify --network for a Gateway with multiple networks' >&2; exit 2; }
    gateway_ip="${entry#* }"
  elif [[ "${entry%% *}" == "$network" ]]; then gateway_ip="${entry#* }"; fi
done
if [[ -z "$network" ]]; then network="${networks[0]%% *}"; fi
[[ -n "$gateway_ip" ]] || { echo 'Gateway needs an IPv4 address on the selected network' >&2; exit 2; }
if [[ -z "$gateway_url" ]]; then
  while IFS= read -r entry; do
    if [[ "$entry" == TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL=* ]]; then gateway_url="${entry#*=}"; fi
  done < <(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$gateway_container")
fi
[[ "$gateway_url" == https://* && "$gateway_url" != *,* ]] || { echo 'Specify --gateway-url with one advertised HTTPS device origin' >&2; exit 2; }
state="$script_dir/.state/$project"
mkdir -p -- "$state"
if [[ -n "$ca_file" ]]; then cp -- "$ca_file" "$state/gateway-ca.crt";
else docker cp "$gateway_container:/data/keys/device-ca.crt" "$state/gateway-ca.crt"; fi
export TOOLGATE_GATEWAY_CA_SOURCE="$state/gateway-ca.crt" TOOLGATE_GATEWAY_URLS="$gateway_url"
export TOOLGATE_SECURED_GATEWAY_IPV4="$gateway_ip" TOOLGATE_SECURED_GATEWAY_PORT="$gateway_port"
export TOOLGATE_DEVICE_NETWORK="$network" TOOLGATE_SECURED_DEVICE_IMAGE="$image"
export TOOLGATE_DEVICE_NAME="$name" TOOLGATE_DEVICE_PULL_POLICY="$pull_policy"
compose=(docker compose --project-name "$project" -f "$script_dir/compose.yaml")
"${compose[@]}" config --quiet
"${compose[@]}" up -d --force-recreate --wait --wait-timeout 180 device
echo "Secured device started for $gateway_url via $gateway_ip:$gateway_port."
"${compose[@]}" logs --tail 20 device
echo 'Approve the enrollment code/fingerprint in the Gateway console; the device then connects automatically.'
