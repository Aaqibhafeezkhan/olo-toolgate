<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Updated binding and the Gateway request context to install with the token; bindingId is the execution binding the agent's grants select, when exactly one does. */
final readonly class ControlWorkloadCredentialResult implements \JsonSerializable {
    public function __construct(
        public ControlWorkloadBinding $binding,
        public \OloLabs\ToolGate\Contracts\V2\RequestContext $context
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['binding', 'context']) || array_diff(['binding', 'context'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            ControlWorkloadBinding::fromArray($data['binding']),
            \OloLabs\ToolGate\Contracts\V2\RequestContext::fromArray($data['context'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
