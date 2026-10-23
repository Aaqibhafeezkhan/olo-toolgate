<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Append-only mutation metadata; payloads and credentials are excluded. */
final readonly class ControlAudit implements \JsonSerializable {
    public function __construct(
        public int $sequence,
        public string $tenantId,
        public string $actorId,
        public string $operation,
        public string $target,
        public int $revision,
        public string $requestId,
        public string $occurredAt,
        public string $requestDigest,
        public ?ControlAuditEvidence $evidence = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['sequence', 'tenantId', 'actorId', 'operation', 'target', 'revision', 'requestId', 'occurredAt', 'requestDigest', 'evidence']) || array_diff(['sequence', 'tenantId', 'actorId', 'operation', 'target', 'revision', 'requestId', 'occurredAt', 'requestDigest'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['sequence'],
            $data['tenantId'],
            $data['actorId'],
            $data['operation'],
            $data['target'],
            $data['revision'],
            $data['requestId'],
            $data['occurredAt'],
            $data['requestDigest'],
            array_key_exists('evidence', $data) ? ControlAuditEvidence::fromArray($data['evidence']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
