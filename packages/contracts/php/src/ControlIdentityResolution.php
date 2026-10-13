<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** How one required identity is resolved on import. MAP needs targetUserId; teamIds are added, never replaced. */
final readonly class ControlIdentityResolution implements \JsonSerializable {
    public function __construct(
        public ControlIdentityResolutionAction $action,
        public ?string $targetUserId = null,
        public ?array $teamIds = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['action', 'targetUserId', 'teamIds']) || array_diff(['action'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            ControlIdentityResolutionAction::from($data['action']),
            array_key_exists('targetUserId', $data) ? $data['targetUserId'] : null,
            array_key_exists('teamIds', $data) ? array_map(static fn ($item) => $item, $data['teamIds']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
