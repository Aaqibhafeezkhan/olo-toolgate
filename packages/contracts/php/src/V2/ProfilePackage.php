<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** One package the profile requires, pinned to an exact version. A MARKETPLACE entry must also pin its package digest; a PREBUILT_CORE entry resolves its digest from the sealed image lock, and a declared digest must equal it. hostIds names the Tool Hosts that receive its eligible ServerTool exports; ClientTool exports never deploy to a Tool Host. deviceGroupIds names the device groups whose active, approved devices receive its device-runnable exports as a signed client release (ADR 026), including devices that join later. An entry that activates exports needs hostIds or deviceGroupIds. bindingIds have their allowed package digests set to exactly the profile packages that name them. */
final readonly class ProfilePackage implements \JsonSerializable {
    public function __construct(
        public string $packageId,
        public ProfilePackageSource $source,
        public string $version,
        public bool $required,
        public ?string $packageDigest = null,
        public ?array $hostIds = null,
        public ?array $deviceGroupIds = null,
        public ?\stdClass $globals = null,
        public ?array $tools = null,
        public ?array $bindingIds = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['packageId', 'source', 'version', 'packageDigest', 'required', 'hostIds', 'deviceGroupIds', 'globals', 'tools', 'bindingIds']) || array_diff(['packageId', 'source', 'version', 'required'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['packageId'],
            ProfilePackageSource::from($data['source']),
            $data['version'],
            $data['required'],
            array_key_exists('packageDigest', $data) ? $data['packageDigest'] : null,
            array_key_exists('hostIds', $data) ? array_map(static fn ($item) => $item, $data['hostIds']) : null,
            array_key_exists('deviceGroupIds', $data) ? array_map(static fn ($item) => $item, $data['deviceGroupIds']) : null,
            array_key_exists('globals', $data) ? (object) $data['globals'] : null,
            array_key_exists('tools', $data) ? array_map(static fn ($item) => ProfileToolActivation::fromArray($item), $data['tools']) : null,
            array_key_exists('bindingIds', $data) ? array_map(static fn ($item) => $item, $data['bindingIds']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
