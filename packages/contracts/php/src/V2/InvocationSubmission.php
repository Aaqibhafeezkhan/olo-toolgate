<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** v2 body of POST /access/invocations (invocation-state-v2.md T1). The Gateway generates invocationId; Control evaluates policy, reserves the business key and decides whether a task exists. */
final readonly class InvocationSubmission implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public RequestContext $context,
        public CatalogScope $catalogScope,
        public string $toolId,
        public string $toolDescriptorDigest,
        public string $packageDigest,
        public \stdClass $arguments,
        public string $argumentsDigest,
        public TaskCapability $tasks,
        public ?BusinessKeyReservationRequest $businessKey = null,
        public ?string $requestState = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'context', 'catalogScope', 'toolId', 'toolDescriptorDigest', 'packageDigest', 'arguments', 'argumentsDigest', 'businessKey', 'tasks', 'requestState']) || array_diff(['invocationId', 'context', 'catalogScope', 'toolId', 'toolDescriptorDigest', 'packageDigest', 'arguments', 'argumentsDigest', 'tasks'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            RequestContext::fromArray($data['context']),
            CatalogScope::fromArray($data['catalogScope']),
            $data['toolId'],
            $data['toolDescriptorDigest'],
            $data['packageDigest'],
            (object) $data['arguments'],
            $data['argumentsDigest'],
            TaskCapability::fromArray($data['tasks']),
            array_key_exists('businessKey', $data) ? BusinessKeyReservationRequest::fromArray($data['businessKey']) : null,
            array_key_exists('requestState', $data) ? $data['requestState'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
