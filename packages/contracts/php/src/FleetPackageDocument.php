<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. A package carries at least one tool or trigger. */
final readonly class FleetPackageDocument implements \JsonSerializable {
    public function __construct(
        public int $formatVersion,
        public string $packageId,
        public string $version,
        public array $platforms,
        public array $architectures,
        public string $minimumClientVersion,
        public array $runtimes,
        public array $tools,
        public array $selfTests,
        public ?array $triggers = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['formatVersion', 'packageId', 'version', 'platforms', 'architectures', 'minimumClientVersion', 'runtimes', 'tools', 'selfTests', 'triggers']) || array_diff(['formatVersion', 'packageId', 'version', 'platforms', 'architectures', 'minimumClientVersion', 'runtimes', 'tools', 'selfTests'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['formatVersion'],
            $data['packageId'],
            $data['version'],
            array_map(static fn ($item) => ClientPlatform::from($item), $data['platforms']),
            array_map(static fn ($item) => FleetArchitecture::from($item), $data['architectures']),
            $data['minimumClientVersion'],
            array_map(static fn ($item) => ManagedRuntime::fromArray($item), $data['runtimes']),
            array_map(static fn ($item) => LocalToolRegistration::fromArray($item), $data['tools']),
            array_map(static fn ($item) => FleetSelfTest::fromArray($item), $data['selfTests']),
            array_key_exists('triggers', $data) ? array_map(static fn ($item) => FleetTriggerRegistration::fromArray($item), $data['triggers']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
