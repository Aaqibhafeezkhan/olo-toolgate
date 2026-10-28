<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Protected local organization registration, separate from marketplace trust and online Gateway authorization. */
final readonly class LocalToolRegistration implements \JsonSerializable {
    public function __construct(
        public string $toolId,
        public string $action,
        public string $runtimeId,
        public string $entryPoint,
        public \stdClass $inputSchema,
        public \stdClass $outputSchema,
        public LocalRuntimeLimits $limits,
        public InstalledAuthorizationProfile $authorizationProfile,
        public ?LocalFileAccess $fileAccess = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['toolId', 'action', 'runtimeId', 'entryPoint', 'inputSchema', 'outputSchema', 'limits', 'authorizationProfile', 'fileAccess']) || array_diff(['toolId', 'action', 'runtimeId', 'entryPoint', 'inputSchema', 'outputSchema', 'limits', 'authorizationProfile'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['toolId'],
            $data['action'],
            $data['runtimeId'],
            $data['entryPoint'],
            (object) $data['inputSchema'],
            (object) $data['outputSchema'],
            LocalRuntimeLimits::fromArray($data['limits']),
            InstalledAuthorizationProfile::fromArray($data['authorizationProfile']),
            array_key_exists('fileAccess', $data) ? LocalFileAccess::from($data['fileAccess']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
