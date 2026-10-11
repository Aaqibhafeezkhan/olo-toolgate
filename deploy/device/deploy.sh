#!/usr/bin/env bash
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
gateways='https://host.docker.internal:18450,https://host.docker.internal:18451'
image='ololab/olo-toolgate-device:dev'
name='toolgate-device'
project='toolgate-device'
platform='linux/amd64'
ca_file=''
console_urls=''
network=''
noninteractive=false
declare -A supplied=()
usage() {
  echo 'deploy.sh [--gateways URLS] [--image IMAGE] [--name NAME] [--project PROJECT] [--platform linux/amd64] [--ca-file PATH] [--console-urls URLS] [--network EXISTING_NETWORK] [--non-interactive]'
}
while (($#)); do
  case "$1" in
    --non-interactive) noninteractive=true; shift; continue ;;
    --help|-h) usage; exit 0 ;;
    --gateways|--image|--name|--project|--platform|--ca-file|--console-urls|--network)
      if (($# < 2)); then echo "Missing value for $1" >&2; exit 2; fi
      key="${1#--}"; key="${key//-/_}"; printf -v "$key" '%s' "$2"; supplied["$key"]=true; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done
prompt() {
  local key="$1" label="$2" override='' display="${!1}"
  if $noninteractive || [[ ! -t 0 || ${supplied[$key]:-false} == true ]]; then return; fi
  read -r -p "$label [${display:-none / automatic}] (Enter keeps default): " override || true
  if [[ -n "$override" ]]; then printf -v "$key" '%s' "$override"; fi
}
prompt gateways 'Comma-separated gateway URLs or container_name:8443 entries'
prompt image 'Device image'
prompt name 'Device hostname'
prompt project 'Compose project (separate project means separate device identity)'
prompt platform 'Container platform'
prompt ca_file 'Optional public CA certificate file'
prompt console_urls 'Optional paired local console URLs (blank discovers Docker defaults)'
prompt network 'Existing Docker network for container-name gateways (blank uses a new network)'
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || { echo 'Invalid Compose project' >&2; exit 2; }
[[ "$name" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}$ ]] || { echo 'Invalid device hostname' >&2; exit 2; }
[[ "$platform" == linux/amd64 || "$platform" == linux/arm64 ]] || { echo 'Invalid platform' >&2; exit 2; }
export TOOLGATE_GATEWAY_URLS="$gateways" TOOLGATE_DEVICE_IMAGE="$image" TOOLGATE_DEVICE_NAME="$name"
export TOOLGATE_DEVICE_PLATFORM="$platform" TOOLGATE_GATEWAY_CONSOLE_URLS="$console_urls"
compose=(docker compose --project-name "$project" -f "$script_dir/compose.yaml")
if [[ -n "$network" ]]; then
  export TOOLGATE_DEVICE_NETWORK="$network"
  compose+=(-f "$script_dir/compose.network.yaml")
fi
if [[ -n "$ca_file" ]]; then
  [[ -f "$ca_file" ]] || { echo 'The CA must be a regular certificate file' >&2; exit 2; }
  export TOOLGATE_GATEWAY_CA_SOURCE="$(cd -- "$(dirname -- "$ca_file")" && pwd)/$(basename -- "$ca_file")"
  compose+=(-f "$script_dir/compose.ca.yaml")
fi
"${compose[@]}" config --quiet
"${compose[@]}" pull device
"${compose[@]}" up -d --force-recreate --wait --wait-timeout 180 device
echo "Device deployed from $image. It will connect when a gateway is available."
echo "View approval details: docker compose --project-name $project -f '$script_dir/compose.yaml' logs -f device"
