<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Agent Group grant and catalog scope. */
final readonly class DeploymentGrant implements \JsonSerializable {
    public function __construct(
        public string $agentGroupId,
        public array $toolIds
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['agentGroupId', 'toolIds']) || array_diff(['agentGroupId', 'toolIds'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['agentGroupId'],
            array_map(static fn ($item) => $item, $data['toolIds'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
