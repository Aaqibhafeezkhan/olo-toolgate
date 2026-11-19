<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Current tenant-scoped publication blockers, without credentials or remote payloads. Observational only; publication rechecks readiness. */
final readonly class WorkflowProfileDiagnostics implements \JsonSerializable {
    public function __construct(
        public string $profileId,
        public string $profileDigest,
        public array $blockers
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['profileId', 'profileDigest', 'blockers']) || array_diff(['profileId', 'profileDigest', 'blockers'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['profileId'],
            $data['profileDigest'],
            array_map(static fn ($item) => $item, $data['blockers'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
