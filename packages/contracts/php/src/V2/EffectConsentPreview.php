<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Immutable exact-effect preview created by Control from authenticated original arguments and pinned reviewed mappings. Its canonical digest must be explicitly approved; ordinary invocation approval is not exact consent. Validity never exceeds original operation and credential deadlines. */
final readonly class EffectConsentPreview implements \JsonSerializable {
    public function __construct(
        public string $consentId,
        public string $tenantId,
        public string $operationId,
        public string $runId,
        public string $toolId,
        public string $action,
        public string $argumentsDigest,
        public array $resources,
        public string $profileDigest,
        public int $notBeforeUnixMs,
        public int $expiresAtUnixMs,
        public array $slots
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['consentId', 'tenantId', 'operationId', 'runId', 'toolId', 'action', 'argumentsDigest', 'resources', 'profileDigest', 'notBeforeUnixMs', 'expiresAtUnixMs', 'slots']) || array_diff(['consentId', 'tenantId', 'operationId', 'runId', 'toolId', 'action', 'argumentsDigest', 'resources', 'profileDigest', 'notBeforeUnixMs', 'expiresAtUnixMs', 'slots'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['consentId'],
            $data['tenantId'],
            $data['operationId'],
            $data['runId'],
            $data['toolId'],
            $data['action'],
            $data['argumentsDigest'],
            array_map(static fn ($item) => \OloLabs\ToolGate\Contracts\ResourceDescriptor::fromArray($item), $data['resources']),
            $data['profileDigest'],
            $data['notBeforeUnixMs'],
            $data['expiresAtUnixMs'],
            array_map(static fn ($item) => EffectConsentSlot::fromArray($item), $data['slots'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
