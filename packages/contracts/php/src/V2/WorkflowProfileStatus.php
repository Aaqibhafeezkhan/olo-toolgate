<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Workflow profile lifecycle metadata; publication requires current authorization, reviewed content and dependency readiness. */
final readonly class WorkflowProfileStatus implements \JsonSerializable {
    public function __construct(
        public string $profileId,
        public string $profileDigest,
        public WorkflowProfileState $state,
        public int $generation,
        public bool $dependenciesReady,
        public ?string $reviewedBy = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['profileId', 'profileDigest', 'state', 'generation', 'dependenciesReady', 'reviewedBy']) || array_diff(['profileId', 'profileDigest', 'state', 'generation', 'dependenciesReady'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['profileId'],
            $data['profileDigest'],
            WorkflowProfileState::from($data['state']),
            $data['generation'],
            $data['dependenciesReady'],
            array_key_exists('reviewedBy', $data) ? $data['reviewedBy'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
