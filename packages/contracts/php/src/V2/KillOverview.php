<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Authoritative current kill state, registered delivery status and retained archive counts. */
final readonly class KillOverview implements \JsonSerializable {
    public function __construct(
        public KillPush $state,
        public array $components,
        public int $retainedEventCount,
        public int $archivedEventCount,
        public int $observedAtUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['state', 'components', 'retainedEventCount', 'archivedEventCount', 'observedAtUnixMs']) || array_diff(['state', 'components', 'retainedEventCount', 'archivedEventCount', 'observedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            KillPush::fromArray($data['state']),
            array_map(static fn ($item) => KillComponentDelivery::fromArray($item), $data['components']),
            $data['retainedEventCount'],
            $data['archivedEventCount'],
            $data['observedAtUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
