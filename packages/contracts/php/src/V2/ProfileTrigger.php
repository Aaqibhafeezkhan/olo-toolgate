<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A client trigger assignment the profile declares for one of its package's trigger exports (ADR 058). It targets the entry's deviceGroupIds; a profile that declares any trigger enables triggers for the deployment. */
final readonly class ProfileTrigger implements \JsonSerializable {
    public function __construct(
        public string $assignment,
        public string $triggerId,
        public \stdClass $config,
        public ?TriggerDesiredState $desiredState = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['assignment', 'triggerId', 'config', 'desiredState']) || array_diff(['assignment', 'triggerId', 'config'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['assignment'],
            $data['triggerId'],
            (object) $data['config'],
            array_key_exists('desiredState', $data) ? TriggerDesiredState::from($data['desiredState']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
