<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Effective and superseded events. */
final readonly class KillEventPage implements \JsonSerializable {
    public function __construct(
        public array $kills,
        public array $recoveries,
        public int $authorizationEpoch
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kills', 'recoveries', 'authorizationEpoch']) || array_diff(['kills', 'recoveries', 'authorizationEpoch'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            array_map(static fn ($item) => KillEvent::fromArray($item), $data['kills']),
            array_map(static fn ($item) => RecoveryEvent::fromArray($item), $data['recoveries']),
            $data['authorizationEpoch']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
