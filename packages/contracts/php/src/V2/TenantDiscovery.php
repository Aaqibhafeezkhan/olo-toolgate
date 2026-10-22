<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Signed tenant discovery v2. Exact HTTPS origins, configured/pinned UUID, lifetime, key continuity, CA purposes and active issuer are verified before enrollment or local identity access. */
final readonly class TenantDiscovery implements \JsonSerializable {
    public function __construct(
        public int $protocolVersion,
        public string $tenantId,
        public string $issuer,
        public array $audiences,
        public string $serverId,
        public string $organization,
        public string $controlUrl,
        public string $gatewayUrl,
        public string $verificationUri,
        public string $minimumClientVersion,
        public int $issuedAtUnixMs,
        public int $expiresAtUnixMs,
        public int $keySetVersion,
        public DiscoveryKeySet $discoveryKeys,
        public string $deviceIssuerKeyId,
        public array $deviceAuthorities
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['protocolVersion', 'tenantId', 'issuer', 'audiences', 'serverId', 'organization', 'controlUrl', 'gatewayUrl', 'verificationUri', 'minimumClientVersion', 'issuedAtUnixMs', 'expiresAtUnixMs', 'keySetVersion', 'discoveryKeys', 'deviceIssuerKeyId', 'deviceAuthorities']) || array_diff(['protocolVersion', 'tenantId', 'issuer', 'audiences', 'serverId', 'organization', 'controlUrl', 'gatewayUrl', 'verificationUri', 'minimumClientVersion', 'issuedAtUnixMs', 'expiresAtUnixMs', 'keySetVersion', 'discoveryKeys', 'deviceIssuerKeyId', 'deviceAuthorities'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['protocolVersion'] !== 2) {
            throw new \InvalidArgumentException('protocolVersion must be 2');
        }
        return new self(
            $data['protocolVersion'],
            $data['tenantId'],
            $data['issuer'],
            array_map(static fn ($item) => $item, $data['audiences']),
            $data['serverId'],
            $data['organization'],
            $data['controlUrl'],
            $data['gatewayUrl'],
            $data['verificationUri'],
            $data['minimumClientVersion'],
            $data['issuedAtUnixMs'],
            $data['expiresAtUnixMs'],
            $data['keySetVersion'],
            DiscoveryKeySet::fromArray($data['discoveryKeys']),
            $data['deviceIssuerKeyId'],
            array_map(static fn ($item) => DiscoveryDeviceAuthority::fromArray($item), $data['deviceAuthorities'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
